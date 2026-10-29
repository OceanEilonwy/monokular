package app.monokular.sales

import app.monokular.core.AppIdentity
import app.monokular.core.Connection
import app.monokular.core.MonokuloApi
import app.monokular.core.Order
import app.monokular.core.SaleEvent
import app.monokular.core.SalePhase
import app.monokular.receipt.Receipt
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.time.Instant

/** A POS that has the sales it's given. */
class FakeSource(var sales: List<FoundSale>, private val receipt: Receipt? = null) : SaleSource {
    val handledIds = mutableListOf<String>()
    val refunded = mutableListOf<String>()
    /** The POS is down: refunds fail with this. */
    var refusal: String? = null
    override suspend fun newSales() = sales.filter { it.id !in handledIds }
    override suspend fun receipt(sale: FoundSale) = receipt
    override suspend fun refund(sale: FoundSale) {
        refusal?.let { throw SquareException(503, it) }
        refunded += sale.id
    }
    override fun handled(sale: FoundSale) {
        handledIds += sale.id
    }
}

/** A saved shift in memory, kept as the JSON the app writes, so what's saved must round-trip. */
class MemoryShift(var saved: String? = null) : ShiftStore {
    var saves = 0
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    override fun load(): List<TillSale> = saved?.let { json.decodeFromString(ListSerializer(TillSale.serializer()), it) }.orEmpty()
    override fun save(sales: List<TillSale>) {
        saves++
        saved = json.encodeToString(ListSerializer(TillSale.serializer()), sales)
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

    /**
     * The problem [name] as Monokulo recorded it for `POST /api/v1/orders`,
     * from its OpenAPI document at the pinned commit (see :core's
     * MonokuloContractTest): its status, `Retry-After` when the response
     * lists one, and the example body.
     */
    private fun recordedOrderProblem(name: String): MockResponse {
        for ((code, response) in createOrder["responses"]!!.jsonObject) {
            val examples = (response.jsonObject["content"] as? JsonObject)?.get("application/problem+json")
                ?.jsonObject?.get("examples") as? JsonObject ?: continue
            val body = examples[name]?.jsonObject?.get("value")?.jsonObject ?: continue
            val headers = (response.jsonObject["headers"] as? JsonObject)?.keys.orEmpty()
            return MockResponse.Builder().code(code.toInt()).apply {
                setHeader("Content-Type", "application/problem+json")
                if ("Retry-After" in headers) body["retry_after"]?.let { setHeader("Retry-After", it.jsonPrimitive.content) }
                body(body.toString())
            }.build()
        }
        error("Monokulo recorded no $name problem for POST /api/v1/orders")
    }

    /** `POST /api/v1/orders` in Monokulo's OpenAPI document at the pinned commit. */
    private val createOrder: JsonObject by lazy {
        val path = System.getProperty("monokulo.openapi")
            ?: error("monokulo.openapi isn't set: run the tests with Gradle, which generates Monokulo's OpenAPI document")
        Json.parseToJsonElement(File(path).readText()).jsonObject["paths"]!!.jsonObject["/api/v1/orders"]!!
            .jsonObject["post"]!!.jsonObject
    }

    /** The name of every problem Monokulo recorded for `POST /api/v1/orders`. */
    private fun recordedOrderProblems(): List<String> = createOrder["responses"]!!.jsonObject.values.flatMap { response ->
        ((response.jsonObject["content"] as? JsonObject)?.get("application/problem+json")?.jsonObject?.get("examples") as? JsonObject)
            ?.keys.orEmpty()
    }

    private val entry = MockResponse.Builder().code(200).setHeader("Cache-Control", "private, max-age=3600").body(
        """{"_links":{"self":{"href":"/api/v1"}},"_templates":{"create-order":{"method":"POST","target":"/api/v1/orders","properties":[]}}}""",
    ).build()

    /**
     * Answers the next order with the recorded problem [name], as often as
     * the client asks: twice for `challenge-required` (it solves the
     * challenge and asks again) and for `unauthorized` (it reads the entry
     * again and retries).
     */
    private fun answerOrderWith(name: String) {
        server.enqueue(recordedOrderProblem(name))
        when (name) {
            "challenge-required" -> server.enqueue(recordedOrderProblem(name))
            "unauthorized" -> {
                server.enqueue(entry)
                server.enqueue(recordedOrderProblem(name))
            }
        }
    }

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
        server.enqueue(recordedOrderProblem("engine-unavailable"))
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

    @Test
    fun `a paid sale is never refunded, nor a refunded one twice`() = runBlocking {
        val source = FakeSource(listOf(sale))
        val till = Till(source, monokulo, connection)
        server.enqueue(order())
        till.check()
        till.onEvent(SaleEvent("sale.paid", "ord_1", "13.88", "USD", "8Bx", 80000000000, "bP9m", 99, "", 2))
        till.refund("ord_1")
        val paid = till.sales.value.sales.single()
        assertEquals(SalePhase.Paid, paid.phase)
        assertEquals(null, paid.refundedAt, "the payment landed as the cashier said no")
        assertTrue(source.refunded.isEmpty())

        val other = FakeSource(listOf(sale))
        val again = Till(other, monokulo, connection)
        server.enqueue(order())
        again.check()
        again.onStatus("ord_1", SalePhase.Expired)
        again.refund("ord_1")
        again.refund("ord_1")
        assertEquals(listOf("pay_monero"), other.refunded, "one refund in the POS")
    }

    @Test
    fun `a cancelled order ends its sale unpaid, and can be refunded`() = runBlocking {
        val source = FakeSource(listOf(sale))
        val till = Till(source, monokulo, connection)
        server.enqueue(order())
        till.check()
        till.onStatus("ord_1", Order.phaseOf("cancelled"))
        val shown = till.sales.value.sales.single()
        assertEquals(SalePhase.Cancelled, shown.phase)
        assertTrue(shown.phase.isFinal && shown.phase.isUnpaid)
        till.refund("ord_1")
        assertEquals(listOf("pay_monero"), source.refunded)
    }

    @Test
    fun `an expired sale stays open while Monokulo watches it, and a late payment pays it, refunded or not`() = runBlocking {
        val source = FakeSource(listOf(sale))
        val till = Till(source, monokulo, connection)
        server.enqueue(order())
        till.check()
        till.onStatus("ord_1", SalePhase.Expired, settled = false)
        assertTrue(till.sales.value.sales.single().open, "Monokulo still watches it for a late payment")
        till.refund("ord_1")
        till.onEvent(SaleEvent("sale.paid", "ord_1", "13.88", "USD", "8Bx", 80000000000, "bP9m", 99, "Back", 2))
        val shown = till.sales.value.sales.single()
        assertEquals(SalePhase.Paid, shown.phase)
        assertTrue(shown.refunded, "the till still knows the Square sale was refunded")
        assertFalse(shown.open)
        till.onStatus("ord_1", SalePhase.Expired, settled = true)
        assertFalse(till.sales.value.sales.single().open, "nothing more will happen once Monokulo says it's final")
    }

    @Test
    fun `no rate or a busy engine says so at the till, and the next check asks again with the same key`() = runBlocking {
        val source = FakeSource(listOf(sale))
        val till = Till(source, monokulo, connection)
        server.enqueue(recordedOrderProblem("rates-unavailable"))
        till.check()
        assertEquals("Monokulo has no exchange rate right now, so there's no payment code yet. Trying again shortly.", till.sales.value.problem)
        server.enqueue(recordedOrderProblem("engine-unavailable"))
        till.check()
        assertEquals("Monokulo's payment engine is busy, so there's no payment code yet. Trying again shortly.", till.sales.value.problem)
        assertTrue(source.handledIds.isEmpty(), "no order was made, so the sale is still to do")
        server.enqueue(order())
        till.check()
        assertEquals(null, till.sales.value.problem)
        assertEquals(1, till.sales.value.sales.size)
        server.takeRequest()
        val keys = List(3) { Regex("\"idempotency_key\":\"([^\"]+)\"").find(server.takeRequest().body!!.utf8())!!.groupValues[1] }
        assertEquals(List(3) { Till.idempotencyKey(sale) }, keys)
    }

    @Test
    fun `a store whose settings Monokulo can't read says so at the till, and who can fix it`() = runBlocking {
        val source = FakeSource(listOf(sale))
        val till = Till(source, monokulo, connection)
        answerOrderWith("store-unavailable")
        till.check()
        assertEquals(
            "Monokulo can't read this store's settings right now, so there's no payment code yet. " +
                "Trying again shortly; if it keeps happening, ask Monokulo's admin to check its database.",
            till.sales.value.problem,
        )
        assertTrue(source.handledIds.isEmpty(), "no order was made, so the sale is still to do")
        server.enqueue(order())
        till.check()
        assertEquals(null, till.sales.value.problem)
        assertEquals(1, till.sales.value.sales.size)
    }

    @Test
    fun `a sale Monokulo can't price, or whose key it used for another order, tells the cashier to take another payment`() = runBlocking {
        val till = Till(FakeSource(listOf(sale)), monokulo, connection)
        answerOrderWith("invalid-amount")
        till.check()
        assertEquals(
            "Monokulo can't price this sale (unknown currency: NOPE), so it has no payment code. Take another payment for it and refund it in the POS.",
            till.sales.value.problem,
        )
        answerOrderWith("idempotency-conflict")
        till.check()
        assertEquals(
            "Monokulo already has a different order for this POS sale, so it made no payment code. Take another payment for it and refund it in the POS.",
            till.sales.value.problem,
        )
    }

    @Test
    fun `a busy store says how long it asked the till to wait`() = runBlocking {
        val till = Till(FakeSource(listOf(sale)), monokulo, connection)
        answerOrderWith("rate-limited")
        till.check()
        assertEquals("The store is busy and asked for 120 seconds' wait before the next try.", till.sales.value.problem)
    }

    @Test
    fun `every problem making an order can answer has its own words at the till, and leaves the sale to do`() = runBlocking {
        val source = FakeSource(listOf(sale))
        val till = Till(source, monokulo, connection)
        val names = recordedOrderProblems()
        assertTrue(names.containsAll(listOf("store-unavailable", "rates-unavailable", "engine-unavailable", "forbidden", "internal")), "$names")
        val said = mutableSetOf<String>()
        for (name in names) {
            answerOrderWith(name)
            till.check()
            val problem = assertNotNull(till.sales.value.problem, name)
            assertFalse(problem.startsWith("Couldn't make the payment code"), "$name: $problem")
            said += problem
        }
        assertTrue(source.handledIds.isEmpty(), "no order was made, so the sale is still to do")
        assertTrue(said.size >= names.size - 2, "each problem says its own thing (the three bad-request ones share words): $said")
    }

    @Test
    fun `closing a sale for now takes its code down and keeps it in the shift, where a late payment still pays it`() = runBlocking {
        var clock = 1_000L
        val till = Till(FakeSource(listOf(sale), receipt), monokulo, connection, now = { clock })
        server.enqueue(order())
        till.check()
        val shown = till.sales.value.sales.single()
        assertEquals(Instant.parse(sale.createdAt).epochSecond, shown.createdAt, "rung up at the POS's time")
        assertEquals(receipt, shown.receipt, "what was bought stays on the till, for its own screens")
        assertEquals(Standing.Live(SalePhase.Waiting), shown.standing)
        assertTrue(shown.live)
        assertFalse(shown.needsAction)
        clock = 1_060
        till.close("ord_1")
        till.close("ord_1")
        val closed = till.sales.value.sales.single()
        assertEquals(Standing.Closed, closed.standing)
        assertEquals(1_060L, closed.closedAt, "closed once, at the first close")
        assertFalse(closed.live)
        assertTrue(closed.needsAction, "Square has it as Monero: refund it or wait")
        assertTrue(closed.open, "Monokulo still watches it")
        till.onEvent(SaleEvent("sale.paid", "ord_1", "13.88", "USD", "8Bx", 80000000000, "bP9m", 99, "", 2_000))
        val paid = till.sales.value.sales.single()
        assertEquals(Standing.Paid(late = true), paid.standing)
        assertEquals(2_000L, paid.paidAt, "paid at the event's time")
        assertFalse(paid.needsAction)
    }

    @Test
    fun `show the code again reopens a closed sale`() = runBlocking {
        val till = Till(FakeSource(listOf(sale)), monokulo, connection)
        server.enqueue(order())
        till.check()
        till.close("ord_1")
        till.reopen("ord_1")
        val shown = till.sales.value.sales.single()
        assertEquals(Standing.Live(SalePhase.Waiting), shown.standing)
        assertEquals(null, shown.closedAt)
    }

    @Test
    fun `refunding a closed sale marks both, and a late payment then says paid after the refund`() = runBlocking {
        var clock = 10L
        val source = FakeSource(listOf(sale))
        val till = Till(source, monokulo, connection, now = { clock })
        server.enqueue(order())
        till.check()
        till.close("ord_1")
        clock = 20
        till.refund("ord_1")
        val refunded = till.sales.value.sales.single()
        assertEquals(Standing.Refunded, refunded.standing)
        assertEquals(10L, refunded.closedAt)
        assertEquals(20L, refunded.refundedAt)
        assertFalse(refunded.needsAction, "nothing more to do")
        assertEquals(listOf("pay_monero"), source.refunded)
        till.onEvent(SaleEvent("sale.paid", "ord_1", "13.88", "USD", "8Bx", 80000000000, "bP9m", 99, "", 30))
        assertEquals(Standing.Paid(late = true, afterRefund = true), till.sales.value.sales.single().standing)
        assertTrue(till.sales.value.sales.single().needsAction, "ring it up again, or return the payment")
    }

    @Test
    fun `an expired or reversed sale needs action until refunded, while another till's never does`() = runBlocking {
        var clock = 100L
        val till = Till(FakeSource(listOf(sale)), monokulo, connection, now = { clock })
        server.enqueue(order())
        till.check()
        till.onStatus("ord_1", SalePhase.Seen)
        clock = 200
        till.onStatus("ord_1", SalePhase.Expired)
        val expired = till.sales.value.sales.single()
        assertEquals(Standing.Expired, expired.standing)
        assertEquals(100L, expired.seenAt, "the first time it was seen is kept")
        assertEquals(200L, expired.endedAt)
        assertTrue(expired.needsAction)
        till.onStatus("ord_1", SalePhase.DoubleSpend)
        assertEquals(Standing.Reversed, till.sales.value.sales.single().standing)
        assertEquals(200L, till.sales.value.sales.single().endedAt, "ended once")
        till.refund("ord_1")
        assertEquals(Standing.Refunded, till.sales.value.sales.single().standing)
        // Another till's sale: this till can't refund it, so it never needs action here.
        till.onEvent(SaleEvent("sale.waiting", "ord_2", "5.00", "USD", "8Cy", 30000000000, null, 99, "Back till", 300))
        till.onEvent(SaleEvent("sale.seen", "ord_2", "5.00", "USD", "8Cy", 30000000000, null, 99, "Back till", 310))
        till.onEvent(SaleEvent("sale.expired", "ord_2", "5.00", "USD", "8Cy", 30000000000, null, 99, "Back till", 320))
        val theirs = till.sales.value.sales.first { it.orderId == "ord_2" }
        assertEquals(Standing.OtherTill, theirs.standing)
        assertEquals(300L, theirs.createdAt)
        assertEquals(310L, theirs.seenAt)
        assertEquals(320L, theirs.endedAt)
        assertFalse(theirs.needsAction)
        assertEquals(null, theirs.receipt, "another till's sale has no items here")
        till.close("ord_2")
        assertEquals(Standing.OtherTill, till.sales.value.sales.first { it.orderId == "ord_2" }.standing, "closing it here changes nothing about who has its Square sale")
    }

    @Test
    fun `a till started on a saved shift shows its sales, marks and receipts, and saves every change`() = runBlocking {
        val shift = MemoryShift()
        val first = Till(FakeSource(listOf(sale), receipt), monokulo, connection, now = { 50 }, store = shift)
        server.enqueue(order())
        first.check()
        first.close("ord_1")
        assertTrue(shift.saves >= 2, "the new sale, then its close")
        assertFalse(shift.saved!!.contains("problem"), "only the sales are saved")
        // Android ended the process: the next run starts from what was saved.
        val again = Till(FakeSource(emptyList()), monokulo, connection, store = shift)
        val shown = again.sales.value.sales.single()
        assertEquals("ord_1", shown.orderId)
        assertEquals(50L, shown.closedAt)
        assertEquals(Standing.Closed, shown.standing)
        assertEquals(receipt, shown.receipt, "what was bought is back for the till's own screens")
        assertEquals(sale, shown.found, "and it can still be refunded")
        assertTrue(shown.code.hasReceipt)
        // A check that finds nothing changes nothing, so writes nothing.
        val saves = shift.saves
        again.check()
        assertEquals(saves, shift.saves)
        again.onStatus("ord_1", SalePhase.Expired, settled = true)
        assertEquals(Standing.Expired, MemoryShift(shift.saved).load().single().standing)
    }

    @Test
    fun `a shift keeps its newest sales up to the cap`() = runBlocking {
        val shift = MemoryShift()
        val till = Till(FakeSource(emptyList()), monokulo, connection, store = shift)
        repeat(Till.MAX_SALES + 3) { i ->
            till.onEvent(SaleEvent("sale.waiting", "ord_$i", "5.00", "USD", "8Cy", 30000000000, null, 99, "Back", i.toLong()))
        }
        val sales = till.sales.value.sales
        assertEquals(Till.MAX_SALES, sales.size)
        assertEquals("ord_${Till.MAX_SALES + 2}", sales.first().orderId, "newest first")
        assertEquals("ord_3", sales.last().orderId, "the oldest three dropped off")
        assertEquals(Till.MAX_SALES, MemoryShift(shift.saved).load().size)
        // A saved shift longer than the cap (an older build's) is cut down as it loads.
        val long = MemoryShift().apply { save(sales + sales.map { it.copy(orderId = it.orderId + "b") }) }
        assertEquals(Till.MAX_SALES, Till(FakeSource(emptyList()), monokulo, connection, store = long).sales.value.sales.size)
    }

    @Test
    fun `a refund the POS refuses leaves the sale closed and unrefunded, says why on that sale, and can be tried again`() = runBlocking {
        val source = FakeSource(listOf(sale))
        val till = Till(source, monokulo, connection)
        server.enqueue(order())
        till.check()
        // The close sheet's "No": close, then refund.
        till.close("ord_1")
        source.refusal = "Square is down"
        till.refund("ord_1")
        val failed = till.sales.value.sales.single()
        assertEquals(Standing.Closed, failed.standing)
        assertFalse(failed.refunded)
        assertTrue(failed.needsAction, "still to refund")
        assertEquals("Couldn't refund it in the POS: Square is down. Refund it there instead.", till.sales.value.refundProblems["ord_1"])
        till.check()
        assertEquals(null, till.sales.value.problem)
        assertNotNull(till.sales.value.refundProblems["ord_1"], "a check doesn't wipe the sale's refund problem")
        source.refusal = null
        till.refund("ord_1")
        assertEquals(Standing.Refunded, till.sales.value.sales.single().standing)
        assertEquals(emptyMap(), till.sales.value.refundProblems)
    }

    @Test
    fun `a closed sale keeps its marks and times through the stream and a resync`() = runBlocking {
        var clock = 10L
        val till = Till(FakeSource(listOf(sale)), monokulo, connection, now = { clock })
        server.enqueue(order())
        till.check()
        till.onEvent(SaleEvent("sale.seen", "ord_1", "13.88", "USD", "8Bx", 80000000000, "bP9m", 99, "", 5))
        clock = 20
        till.close("ord_1")
        clock = 30
        till.onStatus("ord_1", SalePhase.Seen)
        till.onEvent(SaleEvent("sale.expired", "ord_1", "13.88", "USD", "8Bx", 80000000000, "bP9m", 99, "", 40))
        val shown = till.sales.value.sales.single()
        assertEquals(5L, shown.seenAt)
        assertEquals(20L, shown.closedAt)
        assertEquals(40L, shown.endedAt)
        assertEquals(Standing.Expired, shown.standing, "the code running out outranks the close")
    }

    @Test
    fun `a reversed sale stays reversed through a resync that reads pending, and in the saved shift`() = runBlocking {
        val shift = MemoryShift()
        var clock = 10L
        val till = Till(FakeSource(listOf(sale), receipt), monokulo, connection, now = { clock }, store = shift)
        server.enqueue(order())
        till.check()
        till.onEvent(SaleEvent("sale.seen", "ord_1", "13.88", "USD", "8Bx", 80000000000, "bP9m", 99, "", 5))
        till.onEvent(SaleEvent("sale.double_spend", "ord_1", "13.88", "USD", "8Bx", 80000000000, "bP9m", 99, "", 7))
        assertEquals(Standing.Reversed, till.sales.value.sales.single().standing)
        // The app was restarted: the next run loads the saved shift, and the stream's resync reads the
        // order, which says pending again once the double spend voided its payment.
        clock = 20
        val again = Till(FakeSource(emptyList()), monokulo, connection, now = { clock }, store = shift)
        again.onStatus("ord_1", Order.phaseOf("pending"), settled = false)
        val shown = again.sales.value.sales.single()
        assertEquals(Standing.Reversed, shown.standing, "never back to waiting")
        assertFalse(shown.live, "no code, bubble or chip")
        assertTrue(shown.needsAction)
        assertEquals(7L, shown.endedAt)
        assertEquals(Standing.Reversed, MemoryShift(shift.saved).load().single().standing)
        // Nor does it read as expired once its code runs out: it still mustn't be handed over.
        again.onStatus("ord_1", SalePhase.Expired, settled = false)
        assertEquals(Standing.Reversed, again.sales.value.sales.single().standing)
        // Only a payment moves it on.
        again.onStatus("ord_1", SalePhase.Paid, settled = true)
        assertEquals(Standing.Paid(late = true), again.sales.value.sales.single().standing)
    }

    @Test
    fun `an expired sale never goes back to waiting, whatever a resync reads`() = runBlocking {
        val till = Till(FakeSource(listOf(sale)), monokulo, connection, now = { 10 })
        server.enqueue(order())
        till.check()
        till.onStatus("ord_1", SalePhase.Expired)
        till.onStatus("ord_1", SalePhase.Waiting)
        till.onStatus("ord_1", SalePhase.Seen)
        assertEquals(Standing.Expired, till.sales.value.sales.single().standing)
        assertEquals(null, till.sales.value.sales.single().seenAt, "a step it didn't take isn't timed")
    }

    @Test
    fun `a code whose time ran out is expired by the clock, and a payment still pays it`() = runBlocking {
        var clock = 1761311999L
        val till = Till(FakeSource(listOf(sale)), monokulo, connection, now = { clock })
        server.enqueue(order())
        till.check()
        assertEquals(1761312000L, till.nextLapse(till.sales.value))
        till.lapse()
        assertTrue(till.sales.value.sales.single().live, "not before its time")
        clock = 1761312000
        till.lapse()
        val lapsed = till.sales.value.sales.single()
        assertEquals(Standing.Expired, lapsed.standing)
        assertFalse(lapsed.live, "so it never pulses, takes the panel or waits")
        assertEquals(1761312000L, lapsed.endedAt)
        assertEquals(null, till.nextLapse(till.sales.value))
        till.onStatus("ord_1", SalePhase.Waiting)
        assertEquals(Standing.Expired, till.sales.value.sales.single().standing, "a server late to expire it doesn't bring it back")
        till.onStatus("ord_1", SalePhase.Paid)
        assertEquals(SalePhase.Paid, till.sales.value.sales.single().phase)
    }

    @Test
    fun `a code is ended on time even when the till's clock reads a second behind the wait's`() = runTest {
        // The wait runs on the test's clock in milliseconds; the till stamps a second behind it, as two clocks may.
        val start = 1761311990_000L
        val wall = { start + testScheduler.currentTime }
        val till = Till(FakeSource(listOf(sale)), monokulo, connection, now = { (wall() - 1_000) / 1000 })
        server.enqueue(order())
        till.check()
        val before = till.sales.value.sales.single()
        backgroundScope.launch { till.lapseOnTime(nowMs = wall) }
        advanceTimeBy(10_000 + 1)
        assertTrue(till.sales.value.sales.single().live, "at its end by the wait's clock, the till's own says a second to go")
        advanceTimeBy(Till.LAPSE_RETRY_MS + 1)
        val lapsed = till.sales.value.sales.single()
        assertEquals(Standing.Expired, lapsed.standing, "ended within two seconds, with nothing else changing")
        assertEquals(before.copy(phase = SalePhase.Expired, endedAt = lapsed.endedAt), lapsed, "only the clock ended it")
        assertEquals(1761312000L, lapsed.endedAt)
    }

    @Test
    fun `stopping watching closes every live sale, which a late payment still pays`() = runBlocking {
        val till = Till(FakeSource(listOf(sale)), monokulo, connection, now = { 30 })
        server.enqueue(order())
        till.check()
        till.onEvent(SaleEvent("sale.waiting", "ord_2", "5.00", "USD", "8Cy", 30000000000, null, 99, "Back till", 20))
        till.closeLive()
        val (theirs, ours) = till.sales.value.sales
        assertEquals(Standing.Closed, ours.standing)
        assertEquals(30L, ours.closedAt)
        assertTrue(ours.needsAction, "to refund")
        assertEquals(Standing.OtherTill, theirs.standing)
        assertFalse(theirs.live)
        till.onStatus("ord_1", SalePhase.Paid, settled = true)
        assertEquals(Standing.Paid(late = true), till.sales.value.sales.first { it.orderId == "ord_1" }.standing)
    }
}
