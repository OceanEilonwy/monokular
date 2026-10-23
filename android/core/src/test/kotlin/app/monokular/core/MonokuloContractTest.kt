package app.monokular.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The client against Monokulo's own OpenAPI document, generated from the
 * pinned Monokulo commit (`monokulo.sha` in `gradle.properties`) by the
 * `monokuloOpenApi` task, never committed. A stand-in answers each request
 * with the example Monokulo recorded for that route from a real exchange,
 * so a renamed route, link, form or field fails here.
 */
class MonokuloContractTest {
    private val openapi: JsonObject = run {
        val path = System.getProperty("monokulo.openapi")
            ?: fail("monokulo.openapi isn't set: run the tests with Gradle, which generates Monokulo's OpenAPI document")
        Json.parseToJsonElement(File(path).readText()).jsonObject
    }
    private val paths = openapi["paths"]!!.jsonObject
    private val server = MockWebServer()
    private val api = MonokuloApi(OkHttpClient(), AppIdentity("monokular", "Monokular", "0.1.0", "android", "Pixel 8"))
    private val seen = CopyOnWriteArrayList<String>()

    /** What went wrong answering, rethrown after the test so it isn't lost as a 500. */
    @Volatile private var failure: Throwable? = null

    @BeforeTest
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = try {
                answer(request)
            } catch (e: Throwable) {
                failure = e
                MockResponse.Builder().code(500).build()
            }
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        server.close()
        failure?.let { throw it }
    }

    private fun connection() = Connection("ak_secret", server.url("/api/v1").toString())

    /** The OpenAPI path template [path] matches, or null. */
    private fun template(path: String): String? = paths.keys.firstOrNull { template ->
        Regex("^" + template.replace(Regex("\\{[^}]+}"), "[^/]+") + "$").matches(path)
    }

    private fun operation(method: String, path: String): JsonObject? =
        template(path)?.let { paths[it]?.jsonObject?.get(method.lowercase()) as? JsonObject }

    /** An operation's success status and its recorded example body, if it has one. */
    private fun success(op: JsonObject): Pair<Int, JsonElement?> {
        val (code, response) = op["responses"]!!.jsonObject.entries.first { it.key.startsWith("2") }
        val example = (response.jsonObject["content"] as? JsonObject)?.values?.firstOrNull()?.jsonObject?.get("example")
        return code.toInt() to example
    }

    /** Every link or form ([part] `_links` or `_templates`) Monokulo's recorded examples carry, by name. */
    private fun recorded(part: String): Map<String, JsonElement> = buildMap {
        for (path in paths.values) for (op in path.jsonObject.values) {
            val example = (op as? JsonObject)?.let { success(it).second } as? JsonObject ?: continue
            (example[part] as? JsonObject)?.forEach { (name, value) -> putIfAbsent(name, value) }
        }
    }

    /**
     * The entry as an app's key sees it. Monokulo records the entry
     * without a key, so each `mk:` link its schema lists is taken from
     * another recorded answer that has it, else is the route whose
     * description begins with the link's.
     */
    private fun entry(): JsonObject {
        val root = openapi["components"]!!.jsonObject["schemas"]!!.jsonObject["Root"]!!.jsonObject
        val rels = root["properties"]!!.jsonObject["_links"]!!.jsonObject["properties"]!!.jsonObject
        val seen = recorded("_links")
        val links = buildJsonObject {
            for ((rel, schema) in rels) {
                if (!rel.startsWith("mk:")) continue
                seen[rel]?.let { put(rel, it); continue }
                val said = schema.jsonObject["description"]!!.jsonPrimitive.content.lineSequence().first()
                val path = paths.entries.firstOrNull { (_, item) ->
                    val get = item.jsonObject["get"] as? JsonObject ?: return@firstOrNull false
                    get["description"]?.jsonPrimitive?.content?.startsWith(said) == true ||
                        get["summary"]?.jsonPrimitive?.content?.startsWith(said) == true ||
                        responseSchemaSays(get, said)
                }?.key ?: fail("no route is $rel ($said)")
                put(rel, buildJsonObject { put("href", JsonPrimitive(path)) })
            }
        }
        return buildJsonObject {
            put("_links", links)
            put("_templates", JsonObject(recorded("_templates")))
        }
    }

    private fun responseSchemaSays(op: JsonObject, said: String): Boolean {
        val content = (op["responses"]!!.jsonObject.entries.first { it.key.startsWith("2") }.value.jsonObject["content"] as? JsonObject)
            ?.values?.firstOrNull()?.jsonObject ?: return false
        val ref = content["schema"]?.jsonObject?.get("\$ref")?.jsonPrimitive?.content ?: return false
        val schema = openapi["components"]!!.jsonObject["schemas"]!!.jsonObject[ref.substringAfterLast('/')]?.jsonObject
        return schema?.get("description")?.jsonPrimitive?.content?.startsWith(said) == true
    }

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        seen += "${request.method} $path"
        val op = operation(request.method, path) ?: return MockResponse.Builder().code(404).build()
        if (path == "/api/v1") {
            return MockResponse.Builder().code(200).setHeader("Content-Type", "application/prs.hal-forms+json")
                .body(entry().toString()).build()
        }
        if (request.method == "POST") checkSent(op, request.body!!.utf8())
        val (code, example) = success(op)
        return MockResponse.Builder().code(code).apply {
            if (example != null) {
                setHeader("Content-Type", "application/prs.hal-forms+json")
                body(example.toString())
            }
        }.build()
    }

    /** What the app sends is what the operation takes: every required field, nothing it doesn't know. */
    private fun checkSent(op: JsonObject, sent: String) {
        val ref = op["requestBody"]!!.jsonObject["content"]!!.jsonObject["application/json"]!!
            .jsonObject["schema"]!!.jsonObject["\$ref"]!!.jsonPrimitive.content
        val schema = openapi["components"]!!.jsonObject["schemas"]!!.jsonObject[ref.substringAfterLast('/')]!!.jsonObject
        val known = schema["properties"]!!.jsonObject.keys
        val required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        val fields = Json.parseToJsonElement(sent).jsonObject.keys
        assertTrue(known.containsAll(fields), "sent $fields; Monokulo takes $known")
        assertTrue(fields.containsAll(required), "sent $fields; Monokulo needs $required")
    }

    @Test
    fun `the store, its wallet and the app read as Monokulo writes them`() = runBlocking {
        val store = api.store(connection())
        assertTrue(store.name.isNotEmpty() && store.baseCurrency.isNotEmpty())
        val app = api.app(connection())
        assertTrue(app.deviceName.isNotEmpty() && app.appId.isNotEmpty())
        assertEquals(listOf("GET /api/v1", "GET /api/v1/store", "GET /api/v1/app"), seen)
    }

    @Test
    fun `an order is made with the create-order form, read as Monokulo writes it, and read again at its self link`() = runBlocking {
        val order = api.createOrder(connection(), "24.50", "USD", "#1042", "sq:abc")
        assertTrue(order.address.isNotEmpty() && order.piconero > 0 && order.expiresAt > 0)
        assertTrue(order.paymentUri.startsWith("monero:${order.address}?tx_amount="))
        assertNotNull(template(order.url.toHttpUrl().encodedPath), "its self link is a route")
        val again = api.order(connection(), order.orderId, order.url)
        assertEquals(order.orderId, again.orderId)
        val byId = api.order(connection(), order.orderId)
        assertEquals(order.orderId, byId.orderId)
    }

    @Test
    fun `the event stream and disconnecting are where the entry says`() = runBlocking {
        val events = entry()["_links"]!!.jsonObject["mk:events"]!!.jsonObject["href"]!!.jsonPrimitive.content
        val stream = operation("GET", events)!!["responses"]!!.jsonObject["200"]!!.jsonObject["content"]!!.jsonObject
        assertEquals(setOf("text/event-stream"), stream.keys)
        api.disconnect(connection())
        assertEquals("DELETE /api/v1/app", seen.last())
    }
}
