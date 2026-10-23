package app.monokular.core

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The client against a stand-in Monokulo answering as `protocol/openapi.yaml` says. */
class MonokuloApiTest {
    private val server = MockWebServer()
    private val identity = AppIdentity("monokular", "Monokular", "0.1.0", "android", "Pixel 8")
    private val api = MonokuloApi(OkHttpClient(), identity)
    private val token = "ct_" + "ab".repeat(32)

    @BeforeTest fun start() = server.start()

    @AfterTest fun stop() = server.close()

    private fun base() = server.url("/").toString().trimEnd('/')

    private fun connection() = Connection(
        publicKey = "pk_1", appKey = "ak_secret", endpoint = base(), appId = "app_1",
        deviceName = "Front counter", store = Connection.Store("Bakery", "USD"),
    )

    @Test
    fun `connecting redeems the code and keeps what the store said`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"public_key":"pk_1","app_key":"ak_x","endpoint":"https://pay.example","app_id":"app_1",
                   "device_name":"Front counter","store":{"name":"Bakery","base_currency":"USD"},
                   "wallet":{"name":"Shop","network":"mainnet","address_short":"4Ad…7s"}}""",
            ).build(),
        )
        val connection = api.connect(ConnectCode(base(), token))
        assertEquals("ak_x", connection.appKey)
        assertEquals("mainnet", connection.wallet?.network)
        assertNull(connection.wallet?.address, "an older Monokulo sends only the short form")
        val request = server.takeRequest()
        assertEquals("/apps/connect", request.url.encodedPath)
        assertEquals("monokular/0.1.0", request.headers["Monokulo-Client"])
        val sent = request.body!!.utf8()
        assertTrue(sent.contains(""""token":"$token"""") && sent.contains(""""device_name":"Pixel 8""""), sent)
    }

    @Test
    fun `connecting keeps the wallet's whole address when the store sends it`() = runBlocking {
        val address = "4" + "A".repeat(93) + "Q2"
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"public_key":"pk_1","app_key":"ak_x","endpoint":"https://pay.example","app_id":"app_1",
                   "device_name":"Front counter","store":{"name":"Bakery","base_currency":"USD"},
                   "wallet":{"name":"Shop","network":"mainnet","address":"$address","address_short":"4AAA…AAQ2"}}""",
            ).build(),
        )
        val wallet = api.connect(ConnectCode(base(), token)).wallet!!
        assertEquals(address, wallet.address)
        assertEquals("4AAA…AAQ2", wallet.addressShort)
    }

    @Test
    fun `a refused code says why`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).body("""{"error":"no public address yet"}""").build())
        val e = assertFailsWith<MonokuloException> { api.connect(ConnectCode(base(), token)) }
        assertEquals(503, e.status)
        assertEquals("no public address yet", e.message)
    }

    @Test
    fun `orders are made with the app's key and no description`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"order_id":"ord_1","address":"8Bx","xmr_amount_piconero":500000000000,"amount":"24.50",
                   "currency":"USD","merchant_order_id":"#1042","show_merchant_order_id":true,"expires_at":99}""",
            ).build(),
        )
        val order = api.createOrder(connection(), "24.50", "USD", "#1042", "sq:abc")
        assertEquals("monero:8Bx?tx_amount=0.5", order.paymentUri)
        val request = server.takeRequest()
        assertEquals("/pay/pk_1/orders", request.url.encodedPath)
        assertEquals("Bearer ak_secret", request.headers["Authorization"])
        val sent = request.body!!.utf8()
        assertTrue(sent.contains(""""idempotency_key":"sq:abc""""), sent)
        assertFalse(sent.contains("description"), sent)
    }

    @Test
    fun `status and disconnect use the app's key`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"status":"confirming","confirmations":1,"confirmations_required":2,"is_terminal":false,"error":null}""")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(204).build())
        assertEquals(SalePhase.Confirming, api.status(connection(), "ord_1").phase)
        api.disconnect(connection())
        assertEquals("/pay/pk_1/orders/ord_1/status", server.takeRequest().url.encodedPath)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/pay/pk_1/app", delete.url.encodedPath)
    }

    @Test
    fun `the event stream gives ready, then sales, skipping what it doesn't know`() = runBlocking {
        val sale = """{"event":"sale.waiting","order_id":"ord_1","amount":"1","currency":"XMR","address":"8Bx",
            "xmr_amount_piconero":1000000000000,"expires_at":9,"device_name":"Till","at":1}""".replace("\n", "")
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "text/event-stream").body(
                "event: ready\ndata: {\"at\":1}\n\n" +
                    ": keep-alive\n\n" +
                    "event: something.new\ndata: {}\n\n" +
                    "event: sale.waiting\ndata: $sale\n\n" +
                    "event: lagged\ndata: {\"missed\":3}\n\n",
            ).build(),
        )
        val events = api.events(connection()).take(3).toList()
        assertEquals(StreamEvent.Ready, events[0])
        assertIs<StreamEvent.Sale>(events[1])
        assertEquals("ord_1", (events[1] as StreamEvent.Sale).event.orderId)
        assertEquals(StreamEvent.Lagged, events[2])
        assertEquals("Bearer ak_secret", server.takeRequest().headers["Authorization"])
    }
}
