package app.monokular.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * This device's connection to one store: its key and where the store's
 * API is. The app keeps it (encrypted) until it is disconnected, and
 * nothing else: what the store is called and where its payments go are
 * read from the store each time ([MonokuloApi.store]), never kept
 * (`docs/decisions/android.md` 50).
 */
@Serializable
data class Connection(
    /** The app's own key: `Authorization: Bearer ak_…`. */
    @SerialName("app_key") val appKey: String,
    /**
     * The API's entry, absolute (`https://pay.example/api/v1`): the `api`
     * path `POST /apps/connect` answered, resolved against the address the
     * code was redeemed at, so a till that connected over an onion address
     * stays on it.
     */
    val api: String,
) {
    /** The store's host, as the app shows it (`pay.example`). */
    val host: String
        get() = api.toHttpUrlOrNull()?.let { url ->
            if (url.port == defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"
        } ?: api

    /** Whether the connection is encrypted on the way (https). */
    val encrypted: Boolean get() = api.startsWith("https://")

    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

        /** What the app writes to keep [connection]. */
        fun save(connection: Connection): String = json.encodeToString(serializer(), connection)

        /**
         * The connection [saved] holds, or null for none. One saved by an
         * app from before the API (`endpoint`, `public_key`, `store`,
         * `wallet`, …) is the same key at its endpoint's `/api/v1`: the
         * till carries on without connecting again, and the rest of what
         * it saved is dropped.
         */
        fun restore(saved: String): Connection? {
            val fields = runCatching { json.parseToJsonElement(saved) as JsonObject }.getOrNull() ?: return null
            fun text(name: String) = runCatching { fields[name]?.jsonPrimitive?.contentOrNull }.getOrNull()
            val key = text("app_key") ?: return null
            val api = text("api") ?: text("endpoint")?.toHttpUrlOrNull()?.let { endpoint ->
                endpoint.newBuilder().encodedPath("/api/v1").query(null).fragment(null).build().toString()
            } ?: return null
            return Connection(key, api)
        }
    }
}
