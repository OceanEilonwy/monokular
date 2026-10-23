package app.monokular.core

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client against a stand-in Monokulo that answers as Monokulo's API
 * does: HAL-FORMS with relative links, problem+json errors. The answers
 * themselves are checked against Monokulo's own OpenAPI document in
 * [MonokuloContractTest].
 */
class MonokuloApiTest {
    private val server = MockWebServer()
    private val identity = AppIdentity("monokular", "Monokular", "0.1.0", "android", "Pixel 8")
    private val api = MonokuloApi(OkHttpClient(), identity)
    private val token = "ct_" + "ab".repeat(32)
    private val address = "4" + "A".repeat(85) + "Q2xyz789"

    /** What the stand-in answers, by method and path; anything else is a 404 problem. */
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()

    @BeforeTest
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return routes["${request.method} ${request.url.encodedPath}"]?.invoke()
                    ?: problem(404, "Not found", "No such thing.")
            }
        }
        server.start()
        routes["GET /api/v1"] = { hal(ENTRY) }
    }

    @AfterTest fun stop() = server.close()

    /** The server by name (`localhost`), as a till that connected to that name reaches it. */
    private fun base() = "http://localhost:${server.port}"

    private fun connection() = Connection(appKey = "ak_secret", api = "${base()}/api/v1")

    private fun hal(body: String, code: Int = 200) = MockResponse.Builder().code(code)
        .setHeader("Content-Type", "application/prs.hal-forms+json").body(body).build()

    private fun problem(status: Int, title: String, detail: String) = MockResponse.Builder().code(status)
        .setHeader("Content-Type", "application/problem+json")
        .body("""{"type":"https://docs.monokulo.com/problems/x","title":"$title","status":$status,"detail":"$detail"}""")
        .build()

    private fun order(status: String = "pending") = """{"order_id":"order_1","status":"$status","is_final":false,
        "address":"8Bx","xmr_amount_piconero":500000000000,"amount_received_piconero":0,"amount":"24.50",
        "currency":"USD","merchant_order_id":"#1042","show_merchant_order_id":true,"confirmations":0,
        "confirmations_required":10,"created_at":1,"expires_at":99,
        "_links":{"self":{"href":"/api/v1/orders/order_1"},"mk:order-events":{"href":"/api/v1/orders/order_1/events"}}}"""

    @Test
    fun `connecting redeems the code for a key and the API's entry at the address it used`() = runBlocking {
        routes["POST /apps/connect"] = { MockResponse.Builder().code(200).body("""{"app_key":"ak_x","api":"/api/v1"}""").build() }
        val connection = api.connect(ConnectCode(base(), token))
        assertEquals(Connection("ak_x", "${base()}/api/v1"), connection)
        val request = seen.single()
        assertEquals("monokular/0.1.0", request.headers["Monokulo-Client"])
        val sent = request.body!!.utf8()
        assertTrue(sent.contains(""""token":"$token"""") && sent.contains(""""device_name":"Pixel 8""""), sent)
    }

    @Test
    fun `a refused code says why`() = runBlocking {
        routes["POST /apps/connect"] = { MockResponse.Builder().code(503).body("""{"error":"no public address yet"}""").build() }
        val e = assertFailsWith<MonokuloException> { api.connect(ConnectCode(base(), token)) }
        assertEquals(503, e.status)
        assertEquals("no public address yet", e.message)
    }

    @Test
    fun `the store and its wallet are read live, found from the entry`() = runBlocking {
        routes["GET /api/v1/store"] = {
            hal("""{"name":"Bakery","base_currency":"EUR","wallet":{"name":"Shop","network":"stagenet","address":"$address"},"_links":{}}""")
        }
        val store = api.store(connection())
        assertEquals("Bakery", store.name)
        assertEquals("EUR", store.baseCurrency)
        assertEquals(address, store.wallet?.address)
        assertEquals("4AAAAAAA…Q2xyz789", store.wallet?.addressShort)
        assertEquals(listOf("GET /api/v1", "GET /api/v1/store"), seen.map { "${it.method} ${it.url.encodedPath}" })
        seen.forEach {
            assertEquals("Bearer ak_secret", it.headers["Authorization"])
            assertEquals("localhost:${server.port}", it.headers["Host"], "links resolve against the address the till used")
        }
    }

    @Test
    fun `a store without a wallet says so`() = runBlocking {
        routes["GET /api/v1/store"] = { hal("""{"name":"Bakery","base_currency":"EUR","wallet":null}""") }
        assertNull(api.store(connection()).wallet)
    }

    @Test
    fun `the entry is read once per connection`() = runBlocking {
        routes["GET /api/v1/store"] = { hal("""{"name":"Bakery","base_currency":"EUR"}""") }
        routes["GET /api/v1/app"] = { hal("""{"app_id":"app_1","device_name":"Front counter","platform":"android","connected_at":5}""") }
        api.store(connection())
        assertEquals("Front counter", api.app(connection()).deviceName)
        assertEquals(1, seen.count { it.url.encodedPath == "/api/v1" })
    }

    @Test
    fun `orders are made with the create-order form, with no description`() = runBlocking {
        routes["POST /api/v1/orders"] = { hal(order(), 201).newBuilder().setHeader("Location", "/api/v1/orders/order_1").build() }
        val order = api.createOrder(connection(), "24.50", "USD", "#1042", "sq:abc")
        assertEquals("monero:8Bx?tx_amount=0.5", order.paymentUri)
        assertEquals("${base()}/api/v1/orders/order_1", order.url)
        assertEquals(SalePhase.Waiting, order.phase)
        val request = seen.last()
        assertEquals("POST", request.method)
        val sent = request.body!!.utf8()
        assertTrue(sent.contains(""""idempotency_key":"sq:abc"""") && sent.contains(""""currency":"USD""""), sent)
        assertFalse(sent.contains("description"), sent)
    }

    @Test
    fun `an order is read again at its self link, or by id for another till's`() = runBlocking {
        routes["GET /api/v1/orders/order_1"] = { hal(order("confirming")) }
        val mine = api.order(connection(), "order_1", "${base()}/api/v1/orders/order_1")
        assertEquals(SalePhase.Confirming, mine.phase)
        val theirs = api.order(connection(), "order_1")
        assertEquals(SalePhase.Confirming, theirs.phase)
        assertEquals(2, seen.count { it.url.encodedPath == "/api/v1/orders/order_1" })
    }

    @Test
    fun `disconnecting uses the disconnect form`() = runBlocking {
        routes["DELETE /api/v1/app"] = { MockResponse.Builder().code(204).build() }
        api.disconnect(connection())
        assertEquals("DELETE /api/v1/app", seen.last().let { "${it.method} ${it.url.encodedPath}" })
    }

    @Test
    fun `a disconnected till hears 401 with the problem's detail`() = runBlocking {
        routes["GET /api/v1"] = { problem(401, "No valid key", "This app was disconnected.") }
        val e = assertFailsWith<MonokuloException> { api.store(connection()) }
        assertEquals(401, e.status)
        assertEquals("This app was disconnected.", e.message)
    }

    @Test
    fun `a form the key may not use isn't tried`() = runBlocking {
        routes["GET /api/v1"] = { hal("""{"_links":{"self":{"href":"/api/v1"}}}""") }
        val e = assertFailsWith<MonokuloException> { api.createOrder(connection(), "1", "USD", null, "k") }
        assertEquals(403, e.status)
        assertEquals(1, seen.size, "only the entry was read")
    }

    @Test
    fun `the event stream gives ready, then sales, skipping what it doesn't know`() = runBlocking {
        val sale = """{"event":"sale.waiting","order_id":"order_1","amount":"1","currency":"XMR","address":"8Bx",
            "xmr_amount_piconero":1000000000000,"expires_at":9,"device_name":"Till","at":1}""".replace("\n", "")
        routes["GET /api/v1/events"] = {
            MockResponse.Builder().code(200).setHeader("Content-Type", "text/event-stream").body(
                "event: ready\ndata: {\"at\":1}\n\n" +
                    ": keep-alive\n\n" +
                    "event: something.new\ndata: {}\n\n" +
                    "event: sale.waiting\ndata: $sale\n\n" +
                    "event: lagged\ndata: {\"missed\":3}\n\n",
            ).build()
        }
        val events = api.events(connection()).take(3).toList()
        assertEquals(StreamEvent.Ready, events[0])
        assertIs<StreamEvent.Sale>(events[1])
        assertEquals("order_1", (events[1] as StreamEvent.Sale).event.orderId)
        assertEquals(StreamEvent.Lagged, events[2])
        val stream = seen.last()
        assertEquals("/api/v1/events", stream.url.encodedPath)
        assertEquals("Bearer ak_secret", stream.headers["Authorization"])
    }

    @Test
    fun `a stream refused with 401 says so`() = runBlocking {
        routes["GET /api/v1/events"] = { problem(401, "No valid key", "This app was disconnected.") }
        val e = assertFailsWith<MonokuloException> { api.events(connection()).toList() }
        assertEquals(401, e.status)
    }

    companion object {
        /** The entry as Monokulo answers an app's key: relative links, and its forms. */
        val ENTRY = """{
            "_links":{"self":{"href":"/api/v1"},"mk:store":{"href":"/api/v1/store"},"mk:orders":{"href":"/api/v1/orders"},
              "mk:events":{"href":"/api/v1/events","type":"text/event-stream"},"mk:app":{"href":"/api/v1/app"},
              "curies":[{"name":"mk","href":"https://docs.monokulo.com/rels/{rel}","templated":true}]},
            "_templates":{"create-order":{"method":"POST","target":"/api/v1/orders","contentType":"application/json","properties":[]},
              "disconnect":{"method":"DELETE","target":"/api/v1/app","properties":[]}}}"""
    }
}
