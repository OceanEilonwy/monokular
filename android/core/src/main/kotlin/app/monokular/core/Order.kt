package app.monokular.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An order, as `POST /pay/{pk}/orders` answered it. */
@Serializable
data class Order(
    @SerialName("order_id") val orderId: String,
    val address: String,
    @SerialName("xmr_amount_piconero") val piconero: Long,
    val amount: String,
    val currency: String,
    @SerialName("merchant_order_id") val merchantOrderId: String? = null,
    @SerialName("expires_at") val expiresAt: Long,
) {
    /** The plain payment URI, which the receipt is appended to. */
    val paymentUri: String get() = PaymentUri.build(address, piconero)
}

/** An order's state, as `GET /pay/{pk}/orders/{id}/status` answers it. */
@Serializable
data class OrderStatus(
    val status: String,
    val confirmations: Long = 0,
    @SerialName("confirmations_required") val confirmationsRequired: Long = 0,
    @SerialName("is_terminal") val isTerminal: Boolean = false,
    val error: String? = null,
) {
    /** The phase it puts a sale in. */
    val phase: SalePhase
        get() = when (status) {
            "unconfirmed" -> SalePhase.Seen
            "confirming" -> SalePhase.Confirming
            "paid", "overpaid" -> SalePhase.Paid
            "partial" -> SalePhase.Partial
            "expired", "cancelled" -> SalePhase.Expired
            else -> SalePhase.Waiting
        }
}

/** Monero payment URIs: `monero:<address>?tx_amount=<XMR>`. */
object PaymentUri {
    private const val PICONERO_PER_XMR = 1_000_000_000_000L

    /** [piconero] as XMR, without trailing zeros: 80370584829 is "0.080370584829". */
    fun xmr(piconero: Long): String {
        require(piconero >= 0) { "a negative amount" }
        val whole = piconero / PICONERO_PER_XMR
        val frac = (piconero % PICONERO_PER_XMR).toString().padStart(12, '0').trimEnd('0')
        return if (frac.isEmpty()) "$whole" else "$whole.$frac"
    }

    fun build(address: String, piconero: Long): String = "monero:$address?tx_amount=${xmr(piconero)}"
}
