package app.monokular.sales

import app.monokular.core.AppIdentity
import app.monokular.core.Connection
import app.monokular.core.MonokuloApi
import app.monokular.core.SaleEvent
import app.monokular.core.SalePhase
import app.monokular.receipt.Receipt
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A POS that has the sales it's given. */
class FakeSource(var sales: List<FoundSale>, private val receipt: Receipt? = null) : SaleSource {
    val handledIds = mutableListOf<String>()
    val refunded = mutableListOf<String>()
    override suspend fun newSales() = sales.filter { it.id !in handledIds }
    override suspend fun receipt(sale: FoundSale) = receipt
    override suspend fun refund(sale: FoundSale) {
        refunded += sale.id
    }
    override fun handled(sale: FoundSale) {
        handledIds += sale.id
    }
}

class TillTest {
    private val server = MockWebServer()
    private lateinit var monokulo: MonokuloApi
    private lateinit var connection: Connection
    private val sale = FoundSale("pay_monero", 1388, "USD", "bP9m", "ord_sq_1", "2026-10-22T16:07:00Z", "dev_front")
    private val receipt = Receipt(
        receipt = Receipt.Info("bP9m", "2026-10-22T09:07:00-07:00"),
        merchant = Receipt.Merchant("The Bean Room", "US"),
        lineItems = listOf(Receipt.LineItem("Flat white", 2.0, 4.5, 9.0), Receipt.LineItem("Croissant", 1.0, 3.75, 3.75)),
        tax = Receipt.Tax(listOf(Receipt.TaxLine(0.08875, 1.13))),
        total = 13.88,
        currency = "USD",
    )

    @BeforeTest
    fun start() {
        server.start()
        monokulo = MonokuloApi(OkHttpClient(), AppIdentity("monokular", "Monokular", "0.1.0", "android", "Pixel"))
        connection = Connection("ak_1", server.url("/api/v1").toString())
        // The API's entry, which the client reads before its first order and keeps for an hour.
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Cache-Control", "private, max-age=3600").body(
                """{"_links":{"self":{"href":"/api/v1"}},
                   "_templates":{"create-order":{"method":"POST","target":"/api/v1/orders","properties":[]}}}""",
            ).build(),
        )
    }

    @AfterTest fun stop() = server.close()

    private fun order() = MockResponse.Builder().code(201).body(
        """{"order_id":"ord_1","status":"pending","is_final":false,"address":"8Bx","xmr_amount_piconero":80000000000,
           "amount":"13.88","currency":"USD","merchant_order_id":"bP9m","expires_at":1761312000,
           "_links":{"self":{"href":"/api/v1/orders/ord_1"}}}""",
    ).build()

    @Test
    fun `a Monero sale becomes an order whose code carries its receipt`() = runBlocking {
        val source = FakeSource(listOf(sale), receipt)
        val till = Till(source, monokulo, connection)
        server.enqueue(order())
        till.check()
        val shown = till.sales.value.sales.single()
        assertEquals("ord_1", shown.orderId)
        assertTrue(shown.code.hasReceipt, shown.code.why ?: "")
        assertTrue(shown.code.uri.startsWith("monero:8Bx?tx_amount=0.08&orf="), shown.code.uri)
        assertEquals(listOf("pay_monero"), source.handledIds)
        assertEquals("8Bx", shown.address, "Copy address copies the order's address")
        assertEquals(server.url("/api/v1/orders/ord_1").toString(), shown.url, "it is read again at its self link")
        assertEquals("/api/v1", server.takeRequest().url.encodedPath)
        val create = server.takeRequest()
        assertEquals("/api/v1/orders", create.url.encodedPath)
        val sent = create.body!!.utf8()
        assertTrue(sent.contains(""""amount":"13.88"""") && sent.contains(""""merchant_order_id":"bP9m""""), sent)
        assertTrue(sent.contains(""""idempotency_key":"${Till.idempotencyKey(sale)}""""), sent)
        assertFalse(sent.contains("Flat white"), "nothing bought goes to Monokulo: $sent")
    }

    @Test
    fun `a sale whose order fails is tried again, and only marked handled once it has one`() = runBlocking {
        val source = FakeSource(listOf(sale))
        val till = Till(source, monokulo, connection)
        server.enqueue(MockResponse.Builder().code(503).setHeader("Content-Type", "application/problem+json")
            .body("""{"type":"https://docs.monokulo.com/problems/engine-unavailable","title":"Payment engine unavailable","status":503,"detail":"engine down"}""").build())
        till.check()
        assertTrue(till.sales.value.sales.isEmpty())
        assertNotNull(till.sales.value.problem)
        assertTrue(source.handledIds.isEmpty())
        server.enqueue(order())
        till.check()
        assertEquals(1, till.sales.value.sales.size)
        assertEquals(null, till.sales.value.problem)
        assertEquals(Till.idempotencyKey(sale), Till.idempotencyKey(sale.copy(amountMinor = 1)), "the key is the POS payment's alone")
    }

    @Test
    fun `without a receipt the plain code is shown, and another device's sales are left alone`() = runBlocking {
        val other = sale.copy(id = "pay_2", deviceId = "dev_back")
        val source = FakeSource(listOf(sale, other))
        val till = Till(source, monokulo, connection, deviceId = "dev_front")
        server.enqueue(order())
        till.check()
        val shown = till.sales.value.sales.single()
        assertFalse(shown.code.hasReceipt)
        assertEquals("monero:8Bx?tx_amount=0.08", shown.code.uri)
        assertEquals(listOf("pay_monero", "pay_2"), source.handledIds)
        assertEquals(2, server.requestCount, "the entry, then one order")
    }

    @Test
    fun `events move sales along, and other tills' sales appear without receipts`() = runBlocking {
        val source = FakeSource(listOf(sale), receipt)
        val till = Till(source, monokulo, connection)
        server.enqueue(order())
        till.check()
        fun event(name: String, order: String) =
            SaleEvent(name, order, "5.00", "USD", "8Cy", 30000000000, null, 99, "Back", 1, orderUrl = "https://pay.example/monokulo/api/v1/orders/$order")
        till.onEvent(event("sale.paid", "ord_1"))
        assertEquals(SalePhase.Paid, till.sales.value.sales.single().phase)
        till.onEvent(event("sale.waiting", "ord_2"))
        val theirs = till.sales.value.sales.first()
        assertEquals("ord_2", theirs.orderId)
        assertFalse(theirs.code.hasReceipt)
        assertEquals("monero:8Cy?tx_amount=0.03", theirs.code.uri)
        assertEquals("8Cy", theirs.address)
        assertEquals("Back", theirs.deviceName, "another till's sale says which device made it")
        assertEquals("https://pay.example/monokulo/api/v1/orders/ord_2", theirs.url, "read again at its event's mk:order link")
        // An update for a sale never seen here is ignored.
        till.onEvent(event("sale.paid", "ord_3"))
        assertEquals(2, till.sales.value.sales.size)
    }

    @Test
    fun `a sale another till already made keeps the phase the store's events gave it`() = runBlocking {
        val source = FakeSource(listOf(sale), receipt)
        val till = Till(source, monokulo, connection)
        // The other till's order arrives over the stream first, and is paid.
        till.onEvent(SaleEvent("sale.waiting", "ord_1", "13.88", "USD", "8Bx", 80000000000, "bP9m", 99, "Back", 1))
        till.onEvent(SaleEvent("sale.paid", "ord_1", "13.88", "USD", "8Bx", 80000000000, "bP9m", 99, "Back", 2))
        // Then this till finds the same Square sale and gets the same order back.
        server.enqueue(order())
        till.check()
        val shown = till.sales.value.sales.single()
        assertEquals(SalePhase.Paid, shown.phase)
        assertNotNull(shown.found, "now this till can refund it too")
        Unit
    }

    @Test
    fun `an unpaid sale is refunded in the POS`() = runBlocking {
        val source = FakeSource(listOf(sale))
        val till = Till(source, monokulo, connection)
        server.enqueue(order())
        till.check()
        till.onStatus("ord_1", SalePhase.Expired)
        till.refund("ord_1")
        assertEquals(listOf("pay_monero"), source.refunded)
        assertTrue(till.sales.value.sales.single().refunded)
    }
}
