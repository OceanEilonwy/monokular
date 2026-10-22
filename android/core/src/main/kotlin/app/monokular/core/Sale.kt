package app.monokular.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a sale's payment is (`docs/design.md` §3, the `sale.*` events). */
@Serializable
enum class SalePhase {
    @SerialName("waiting") Waiting,
    @SerialName("seen") Seen,
    @SerialName("confirming") Confirming,
    @SerialName("paid") Paid,
    @SerialName("partial") Partial,
    @SerialName("expired") Expired,
    @SerialName("double_spend") DoubleSpend,
}

/** One `sale.*` event, as the SSE stream and decrypted pushes carry it. */
@Serializable
data class SaleEvent(
    @SerialName("api_version") val apiVersion: Int,
    @SerialName("event_id") val eventId: String,
    val phase: SalePhase,
    @SerialName("sale_id") val saleId: String,
    /** The POS's own reference, e.g. "#1042". */
    val ref: String,
    /** The amount in the store's currency, already formatted for display. */
    val amount: String,
    /** The XMR amount, formatted. */
    val xmr: String,
    /** When the payment code expires, in seconds since the epoch. */
    @SerialName("expires_at") val expiresAt: Long,
    /** The `monero:` URI to show as a QR code. */
    @SerialName("payment_uri") val paymentUri: String,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): SaleEvent = json.decodeFromString(serializer(), text)
    }
}
