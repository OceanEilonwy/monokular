package app.monokular.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.ConcurrentHashMap

/**
 * A Monokulo answer that wasn't a success: its status and what it said
 * (a problem's `detail`, else its `title`). A 401 means the store
 * disconnected this device.
 */
class MonokuloException(val status: Int, message: String) : Exception(message)

/** What the app says about itself when it connects and on every request. */
data class AppIdentity(
    val slug: String,
    val name: String,
    val version: String,
    val platform: String,
    /** This device, for when the merchant didn't name it ("Pixel 8"). */
    val deviceName: String,
) {
    /** The `Monokulo-Client` header's value. */
    val client: String get() = "$slug/$version"
}

/** Something the store's event stream said. */
sealed interface StreamEvent {
    /** It's open: re-read the orders on screen. */
    data object Ready : StreamEvent
    /** Some events were missed: re-read the orders on screen. */
    data object Lagged : StreamEvent
    data class Sale(val event: SaleEvent) : StreamEvent
}

/**
 * A store's Monokulo, as a connected app uses it: `POST /apps/connect`,
 * then Monokulo's HTTP API from its entry, following its links and forms
 * (Monokulo's docs, `/api/` and `/apps/`). Every call runs on
 * [Dispatchers.IO].
 */
class MonokuloApi(
    private val http: OkHttpClient,
    private val identity: AppIdentity,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonType = "application/json".toMediaType()

    /** Each connection's entry, read once: its links and forms don't change while the app runs. */
    private val entries = ConcurrentHashMap<Connection, Entry>()

    @Serializable
    private data class Redeem(
        val token: String,
        val slug: String,
        val name: String,
        @SerialName("device_name") val deviceName: String,
        val platform: String,
    )

    @Serializable
    private data class Redeemed(@SerialName("app_key") val appKey: String, val api: String)

    @Serializable
    private data class NewOrder(
        val amount: String,
        val currency: String,
        @SerialName("merchant_order_id") val merchantOrderId: String? = null,
        @SerialName("show_merchant_order_id") val showMerchantOrderId: Boolean = true,
        @SerialName("idempotency_key") val idempotencyKey: String,
    )

    /** A form from `_templates`: how to send it and where, resolved. */
    private data class Form(val method: String, val target: HttpUrl)

    /** The API's entry: where things are, for this key. */
    private data class Entry(
        val store: HttpUrl?,
        val orders: HttpUrl?,
        val events: HttpUrl?,
        val app: HttpUrl?,
        val createOrder: Form?,
        val disconnect: Form?,
    )

    /** Redeems [code], once, for this device's key and where the store's API is. */
    suspend fun connect(code: ConnectCode): Connection {
        val body = json.encodeToString(
            Redeem.serializer(),
            Redeem(code.token, identity.slug, identity.name, identity.deviceName, identity.platform),
        )
        val request = Request.Builder()
            .url("${code.baseUrl}/apps/connect")
            .header("Monokulo-Client", identity.client)
            .post(body.toRequestBody(jsonType))
            .build()
        return call(request) { text, used ->
            val redeemed = json.decodeFromString(Redeemed.serializer(), text)
            val api = used.resolve(redeemed.api) ?: throw MonokuloException(0, "The store's answer didn't say where its API is.")
            Connection(redeemed.appKey, api.toString())
        }
    }

    /** The store as it is now: its name, base currency and wallet. */
    suspend fun store(connection: Connection): Store {
        val url = entry(connection).store ?: throw notAllowed("read the store")
        return call(keyed(connection, url).get().build()) { text, _ -> json.decodeFromString(Store.serializer(), text) }
    }

    /** This device's connection as the store has it: its name, above all. */
    suspend fun app(connection: Connection): AppConnection {
        val url = entry(connection).app ?: throw notAllowed("read its connection")
        return call(keyed(connection, url).get().build()) { text, _ -> json.decodeFromString(AppConnection.serializer(), text) }
    }

    /**
     * Makes an order with the `create-order` form. [idempotencyKey] makes a
     * retry, or a second till finding the same sale, get the same order.
     * Never a description: it would carry what was bought
     * (`docs/design.md` §4).
     */
    suspend fun createOrder(
        connection: Connection,
        amount: String,
        currency: String,
        merchantOrderId: String?,
        idempotencyKey: String,
    ): Order {
        val form = entry(connection).createOrder ?: throw notAllowed("make orders")
        val body = json.encodeToString(
            NewOrder.serializer(),
            NewOrder(amount, currency, merchantOrderId, idempotencyKey = idempotencyKey),
        ).toRequestBody(jsonType)
        return call(keyed(connection, form.target).method(form.method, body).build(), ::order)
    }

    /**
     * Reads an order again: at [Order.url] for one this device made, else
     * by its id among the store's orders (a sale event carries no link).
     */
    suspend fun order(connection: Connection, orderId: String, url: String? = null): Order {
        val at = url?.takeIf { it.isNotEmpty() }?.toHttpUrl()
            ?: entry(connection).orders?.newBuilder()?.addPathSegment(orderId)?.build()
            ?: throw notAllowed("read orders")
        return call(keyed(connection, at).get().build(), ::order)
    }

    /** Disconnects this device with the `disconnect` form: its key stops working at once. */
    suspend fun disconnect(connection: Connection) {
        val form = entry(connection).disconnect ?: throw notAllowed("disconnect itself")
        try {
            call(keyed(connection, form.target).method(form.method, null).build()) { _, _ -> }
        } finally {
            entries.remove(connection)
        }
    }

    /**
     * The store's sale events (`mk:events`), for as long as it's collected.
     * Ends with a [MonokuloException] when the stream fails; the caller
     * reconnects.
     */
    fun events(connection: Connection): Flow<StreamEvent> = callbackFlow {
        val url = entry(connection).events ?: throw notAllowed("follow the store's sales")
        val request = keyed(connection, url).header("Accept", "text/event-stream").get().build()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val event = when (type) {
                    "ready" -> StreamEvent.Ready
                    "lagged" -> StreamEvent.Lagged
                    null -> null
                    else -> if (SalePhase.ofEvent(type) != null) {
                        runCatching { StreamEvent.Sale(SaleEvent.decode(data)) }.getOrNull()
                    } else {
                        null
                    }
                }
                if (event != null) trySend(event)
            }

            override fun onClosed(eventSource: EventSource) {
                close(MonokuloException(0, "the store closed its event stream"))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val status = response?.code ?: 0
                val said = response?.let { errorOf(it) }
                close(MonokuloException(status, said ?: t?.message ?: "the event stream failed"))
            }
        }
        val source = EventSources.createFactory(http).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    /** The entry for [connection], read the first time it's needed. */
    private suspend fun entry(connection: Connection): Entry {
        entries[connection]?.let { return it }
        val request = keyed(connection, connection.api.toHttpUrl()).get().build()
        val entry = call(request) { text, used ->
            val body = json.parseToJsonElement(text).jsonObject
            val links = body["_links"] as? JsonObject
            val templates = body["_templates"] as? JsonObject
            fun link(rel: String) = (links?.get(rel) as? JsonObject)?.text("href")?.let(used::resolve)
            fun form(name: String) = (templates?.get(name) as? JsonObject)?.let { form ->
                val target = form.text("target")?.let(used::resolve) ?: return@let null
                Form(form.text("method")?.uppercase() ?: "POST", target)
            }
            Entry(
                store = link("mk:store"),
                orders = link("mk:orders"),
                events = link("mk:events"),
                app = link("mk:app"),
                createOrder = form("create-order"),
                disconnect = form("disconnect"),
            )
        }
        entries[connection] = entry
        return entry
    }

    private fun order(text: String, used: HttpUrl): Order {
        val body = json.parseToJsonElement(text).jsonObject
        val self = ((body["_links"] as? JsonObject)?.get("self") as? JsonObject)?.text("href")
        val url = self?.let(used::resolve) ?: used
        return json.decodeFromJsonElement(Order.serializer(), body).copy(url = url.toString())
    }

    private fun JsonObject.text(name: String): String? =
        runCatching { get(name)?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun notAllowed(what: String) =
        MonokuloException(403, "The store's API doesn't let this device $what.")

    private fun keyed(connection: Connection, url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${connection.appKey}")
        .header("Monokulo-Client", identity.client)

    /** Runs [request]; [read] gets the body and the URL it came from, which links resolve against. */
    private suspend fun <T> call(request: Request, read: (String, HttpUrl) -> T): T = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw MonokuloException(response.code, errorOf(response) ?: "HTTP ${response.code}")
            }
            read(response.body.string(), response.request.url)
        }
    }

    /** What an error said: a problem's `detail` or `title`, or `/apps/connect`'s `error`. */
    private fun errorOf(response: Response): String? = runCatching {
        val body = json.parseToJsonElement(response.peekBody(4096).string()).jsonObject
        body.text("detail") ?: body.text("title") ?: body.text("error")
    }.getOrNull()
}
