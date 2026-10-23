package app.monokular.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a sale's payment is. */
enum class SalePhase {
    Waiting, Seen, Confirming, Paid, Partial, Expired, DoubleSpend;

    /** Nothing more will happen to it. */
    val isFinal: Boolean get() = this == Paid || this == Expired || this == DoubleSpend

    companion object {
        /** The phase a `sale.*` event name puts a sale in, or null for another event. */
        fun ofEvent(name: String): SalePhase? = when (name) {
            "sale.waiting" -> Waiting
            "sale.seen" -> Seen
            "sale.confirming" -> Confirming
            "sale.paid" -> Paid
            "sale.partial" -> Partial
            "sale.expired" -> Expired
            "sale.double_spend" -> DoubleSpend
            else -> null
        }
    }
}

/**
 * One `sale.*` event from the store's event stream (`GET /api/v1/events`):
 * an order a connected app made, and what just happened to it. Never
 * anything about what was bought.
 */
@Serializable
data class SaleEvent(
    val event: String,
    @SerialName("order_id") val orderId: String,
    val amount: String,
    val currency: String,
    val address: String,
    @SerialName("xmr_amount_piconero") val piconero: Long,
    @SerialName("merchant_order_id") val merchantOrderId: String? = null,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("device_name") val deviceName: String = "",
    val at: Long,
) {
    val phase: SalePhase? get() = SalePhase.ofEvent(event)

    companion object {
        internal val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): SaleEvent = json.decodeFromString(serializer(), text)
    }
}
