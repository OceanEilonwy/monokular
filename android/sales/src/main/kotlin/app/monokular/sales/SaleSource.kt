package app.monokular.sales

import app.monokular.receipt.Receipt
import kotlinx.serialization.Serializable

/**
 * A point of sale the till watches for Monero sales (`docs/design.md` §1,
 * "POS sources"). Square is the first; another POS is another source.
 */
interface SaleSource {
    /**
     * Sales tendered as Monero since the source started watching, oldest
     * first, that haven't been [handled]. Calling it again gives the same
     * ones until they are.
     */
    suspend fun newSales(): List<FoundSale>

    /** The sale's receipt, or null when it can't be read. */
    suspend fun receipt(sale: FoundSale): Receipt?

    /** Refunds a sale that was never paid in Monero. */
    suspend fun refund(sale: FoundSale)

    /** [sale] has its order: don't give it again. */
    fun handled(sale: FoundSale)
}

/** A Monero sale the POS closed. */
@Serializable
data class FoundSale(
    /** The POS's id for the payment: what the till's idempotency key is made from. */
    val id: String,
    /** In the currency's minor unit (cents). */
    val amountMinor: Long,
    val currency: String,
    /** What the POS printed or showed for it ("bP9m"), if anything. */
    val reference: String?,
    /** The POS's order, for the receipt. */
    val orderId: String?,
    /** RFC 3339. */
    val createdAt: String,
    /** The device that took it, when the POS says: to show it on its own till only. */
    val deviceId: String?,
)
