package app.monokular.push

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/** The Bell's client against a stand-in Bell: exactly the three requests, and the Square token in one of them only (A9.1, A9.2). */
class BellApiTest {
    private val server = MockWebServer()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private val api = BellApi(OkHttpClient())
    private lateinit var bell: BellAddress

    private val subscription = PushSubscription("https://ntfy.sh/upAbc?up=1", "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM", "tBHItJI5svbpez7KI4CCXg")

    @BeforeTest
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return routes["${request.method} ${request.url.encodedPath}"]?.invoke() ?: MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        bell = BellAddress.parse(server.url("/bell").toString(), allowHttp = true)!!
    }

    @AfterTest fun stop() = server.close()

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    private fun problem(code: Int, slug: String, detail: String, retryAfter: Long? = null) = MockResponse.Builder().code(code)
        .setHeader("Content-Type", "application/problem+json")
        .apply { if (retryAfter != null) setHeader("Retry-After", retryAfter.toString()) }
        .body(
            """{"type":"https://github.com/OceanEilonwy/monokular/blob/main/docs/bell.md#$slug","title":"t","status":$code,"detail":"$detail"""" +
                (retryAfter?.let { ""","retry_after":$it""" } ?: "") + "}",
        )
        .build()

    private val info = """{"square_application_id":"sandbox-sq0idb-dev","environment":"sandbox","vapid_public_key":"BOS-key","version":"0.1.0"}"""

    @Test
    fun `info reads the Bell, with no Authorization and no body`() = runBlocking<Unit> {
        routes["GET /bell/v1/info"] = { json(200, info) }
        assertEquals(BellInfo("sandbox-sq0idb-dev", "sandbox", "BOS-key", "0.1.0"), api.info(bell))
        val request = seen.single()
        assertNull(request.headers["Authorization"])
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `register sends exactly the token, the location and the subscription's public half`() = runBlocking<Unit> {
        routes["POST /bell/v1/register"] = { json(201, """{"registration_id":"br_1","square_application_id":"sandbox-sq0idb-dev"}""") }
        assertEquals(BellRegistered("br_1", "sandbox-sq0idb-dev"), api.register(bell, "EAAAtoken", "L1", subscription))
        val request = seen.single()
        assertNull(request.headers["Authorization"])
        assertEquals(
            Json.parseToJsonElement(
                """{"square_access_token":"EAAAtoken","location_id":"L1",
                   "subscription":{"endpoint":"${subscription.endpoint}","keys":{"p256dh":"${subscription.p256dh}","auth":"${subscription.auth}"}}}""",
            ),
            Json.parseToJsonElement(request.body!!.utf8()),
        )
        val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(setOf("square_access_token", "location_id", "subscription"), sent.keys)
        assertEquals(setOf("endpoint", "keys"), sent["subscription"]!!.jsonObject.keys)
        assertEquals(setOf("p256dh", "auth"), sent["subscription"]!!.jsonObject["keys"]!!.jsonObject.keys)
    }

    @Test
    fun `unregister deletes by the id alone, and a 404 is as good as a 204`() = runBlocking<Unit> {
        routes["DELETE /bell/v1/register/br_1"] = { MockResponse.Builder().code(204).build() }
        api.unregister(bell, "br_1")
        api.unregister(bell, "br_gone")
        assertEquals(listOf("DELETE /bell/v1/register/br_1", "DELETE /bell/v1/register/br_gone"), seen.map { "${it.method} ${it.url.encodedPath}" })
        seen.forEach {
            assertNull(it.headers["Authorization"])
            assertEquals(0L, it.bodySize)
        }
    }

    @Test
    fun `the token is in one request of the three`() = runBlocking<Unit> {
        routes["GET /bell/v1/info"] = { json(200, info) }
        routes["POST /bell/v1/register"] = { json(201, """{"registration_id":"br_1","square_application_id":"sandbox-sq0idb-dev"}""") }
        routes["DELETE /bell/v1/register/br_1"] = { MockResponse.Builder().code(204).build() }
        api.info(bell)
        api.register(bell, "EAAAtoken", "L1", subscription)
        api.unregister(bell, "br_1")
        val carrying = seen.filter { request -> "EAAAtoken" in request.body?.utf8().orEmpty() || request.headers.any { "EAAAtoken" in it.second } }
        assertEquals(listOf("POST"), carrying.map { it.method })
    }

    @Test
    fun `a problem is read by its type's slug, its detail and Retry-After`() = runBlocking<Unit> {
        routes["POST /bell/v1/register"] = { problem(429, "rate-limited", "slow down", retryAfter = 42) }
        val e = assertFailsWith<BellException> { api.register(bell, "EAAAtoken", "L1", subscription) }
        assertEquals(429, e.status)
        assertEquals("rate-limited", e.type)
        assertEquals("slow down", e.detail)
        assertEquals(42L, e.retryAfter)
    }

    @Test
    fun `an error without a problem is its status alone`() = runBlocking<Unit> {
        routes["POST /bell/v1/register"] = { MockResponse.Builder().code(502).body("<html>Bad gateway</html>").build() }
        val e = assertFailsWith<BellException> { api.register(bell, "EAAAtoken", "L1", subscription) }
        assertEquals(502, e.status)
        assertNull(e.type)
        assertNull(e.detail)
    }

    @Test
    fun `info from something that isn't a Bell says so`() = runBlocking<Unit> {
        routes["GET /bell/v1/info"] = { MockResponse.Builder().code(200).body("<html>Hello</html>").build() }
        assertFailsWith<NotABellException> { api.info(bell) }
        routes["GET /bell/v1/info"] = { json(200, """{"payments":[]}""") }
        assertFailsWith<NotABellException> { api.info(bell) }
        routes["GET /bell/v1/info"] = { json(200, info.replace("sandbox\"", "staging\"")) }
        assertFailsWith<NotABellException> { api.info(bell) }
        routes.remove("GET /bell/v1/info")
        assertFailsWith<NotABellException> { api.info(bell) }
    }

    @Test
    fun `info from a Bell that can't answer is its status`() = runBlocking<Unit> {
        routes["GET /bell/v1/info"] = { MockResponse.Builder().code(503).build() }
        assertEquals(503, assertFailsWith<BellException> { api.info(bell) }.status)
    }

    @Test
    fun `a Bell that doesn't answer in time is a timeout`() = runBlocking<Unit> {
        val slow = BellApi(OkHttpClient(), timeout = 200.milliseconds)
        routes["GET /bell/v1/info"] = { MockResponse.Builder().code(200).body(info).headersDelay(2, java.util.concurrent.TimeUnit.SECONDS).build() }
        assertFailsWith<InterruptedIOException> { slow.info(bell) }
    }

    @Test
    fun `a Bell that isn't there is an IOException`() = runBlocking<Unit> {
        server.close()
        val e = assertFailsWith<IOException> { api.info(bell) }
        assertFalse(e is BellException || e is NotABellException || e is InterruptedIOException, "no answer at all: $e")
    }
}
