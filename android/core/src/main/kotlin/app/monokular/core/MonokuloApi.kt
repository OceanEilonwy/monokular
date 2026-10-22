package app.monokular.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/** A Monokulo answer that wasn't a success: its status and what it said, if anything. */
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
    /** It's open: re-read the status of the orders on screen. */
    data object Ready : StreamEvent
    /** Some events were missed: re-read the status of the orders on screen. */
    data object Lagged : StreamEvent
    data class Sale(val event: SaleEvent) : StreamEvent
}

/**
 * A store's Monokulo, as a connected app uses it (`protocol/openapi.yaml`).
 * Every call runs on [Dispatchers.IO].
 */
class MonokuloApi(
    private val http: OkHttpClient,
    private val identity: AppIdentity,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonType = "application/json".toMediaType()

    @Serializable
    private data class Redeem(
        val token: String,
        val slug: String,
        val name: String,
        @SerialName("device_name") val deviceName: String,
        val platform: String,
    )

    @Serializable
    private data class NewOrder(
        val amount: String,
        val currency: String,
        @SerialName("merchant_order_id") val merchantOrderId: String? = null,
        @SerialName("show_merchant_order_id") val showMerchantOrderId: Boolean = true,
        @SerialName("idempotency_key") val idempotencyKey: String,
    )

    /** Redeems [code], once, for this device's connection to the store. */
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
        return call(request) { json.decodeFromString(Connection.serializer(), it) }
    }

    /**
     * Makes an order. [idempotencyKey] makes a retry, or a second till
     * finding the same sale, get the same order. Never a description: it
     * would carry what was bought (`docs/design.md` §4).
     */
    suspend fun createOrder(
        connection: Connection,
        amount: String,
        currency: String,
        merchantOrderId: String?,
        idempotencyKey: String,
    ): Order {
        val body = json.encodeToString(
            NewOrder.serializer(),
            NewOrder(amount, currency, merchantOrderId, idempotencyKey = idempotencyKey),
        )
        val request = keyed(connection, "orders").post(body.toRequestBody(jsonType)).build()
        return call(request) { json.decodeFromString(Order.serializer(), it) }
    }

    suspend fun status(connection: Connection, orderId: String): OrderStatus {
        val request = keyed(connection, "orders", orderId, "status").get().build()
        return call(request) { json.decodeFromString(OrderStatus.serializer(), it) }
    }

    /** Disconnects this device: its key stops working at once. */
    suspend fun disconnect(connection: Connection) {
        call(keyed(connection, "app").delete().build()) { }
    }

    /**
     * The store's event stream, for as long as it's collected. Ends with a
     * [MonokuloException] when the stream fails; the caller reconnects.
     */
    fun events(connection: Connection): Flow<StreamEvent> = callbackFlow {
        val request = keyed(connection, "events").header("Accept", "text/event-stream").get().build()
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

    private fun keyed(connection: Connection, vararg path: String): Request.Builder {
        val url = connection.endpoint.toHttpUrl().newBuilder()
            .addPathSegment("pay")
            .addPathSegment(connection.publicKey)
            .apply { path.forEach { addPathSegment(it) } }
            .build()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${connection.appKey}")
            .header("Monokulo-Client", identity.client)
    }

    private suspend fun <T> call(request: Request, read: (String) -> T): T = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw MonokuloException(response.code, errorOf(response) ?: "HTTP ${response.code}")
            }
            read(response.body.string())
        }
    }

    private fun errorOf(response: Response): String? = runCatching {
        val body = response.peekBody(4096).string()
        (json.parseToJsonElement(body) as JsonObject)["error"]?.jsonPrimitive?.content
    }.getOrNull()
}
