package app.monokular

import app.monokular.core.AppIdentity
import app.monokular.core.Connection
import app.monokular.core.MonokuloApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The store read live, never saved: what a read gives, and what a failed one leaves. */
class LiveStoreTest {
    private val server = MockWebServer()
    private val api = MonokuloApi(OkHttpClient(), AppIdentity("monokular", "Monokular", "0.1.0", "android", "Pixel 8"))
    private var disconnected = 0
    private val live = LiveStore(api, onDisconnected = { disconnected++ })
    private var answers: (String) -> MockResponse = { MockResponse.Builder().code(404).build() }

    @BeforeTest
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = answers(request.url.encodedPath)
        }
        server.start()
    }

    @AfterTest fun stop() = server.close()

    private fun connection() = Connection("ak_1", server.url("/api/v1").toString())

    private fun json(body: String) = MockResponse.Builder().code(200).body(body).build()

    private suspend fun settled() = withTimeout(5_000) { live.now.first { !it.reading } }

    @Test
    fun `a read gives the store, its wallet and this device`() = runBlocking {
        answers = { path ->
            when (path) {
                "/api/v1" -> json("""{"_links":{"mk:store":{"href":"/api/v1/store"},"mk:app":{"href":"/api/v1/app"}}}""")
                "/api/v1/store" -> json("""{"name":"Bakery","base_currency":"EUR","wallet":{"name":"Shop","network":"mainnet","address":"4Ad"}}""")
                "/api/v1/app" -> json("""{"app_id":"app_1","device_name":"Front counter"}""")
                else -> MockResponse.Builder().code(404).build()
            }
        }
        live.read(connection())
        val now = settled()
        assertEquals("Bakery", now.store?.name)
        assertEquals("4Ad", now.store?.wallet?.address)
        assertEquals("Front counter", now.device?.deviceName)
        assertNull(now.problem)
    }

    @Test
    fun `a disconnected till hears so, and the app doesn't fall over`() = runBlocking {
        answers = {
            MockResponse.Builder().code(401).setHeader("Content-Type", "application/problem+json")
                .body("""{"title":"No valid key","status":401,"detail":"This key was not accepted."}""").build()
        }
        live.read(connection())
        val now = settled()
        assertEquals("This key was not accepted.", now.problem)
        assertNull(now.store)
        assertTrue(disconnected > 0)
    }
}
