package app.monokular.sales

import app.monokular.core.Connection
import app.monokular.core.MonokuloApi
import app.monokular.core.Order
import app.monokular.core.PaymentUri
import app.monokular.core.SaleEvent
import app.monokular.core.SalePhase
import app.monokular.receipt.PaymentCode
import app.monokular.receipt.ReceiptCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/** A sale on the till's screen. */
data class TillSale(
    val orderId: String,
    val amount: String,
    val currency: String,
    val reference: String?,
    /** What to show as the QR code: with the receipt when this till found the sale and could add it. */
    val code: PaymentCode,
    val phase: SalePhase,
    /** Unix seconds. */
    val expiresAt: Long,
    val xmr: String,
    /** The address the customer pays: what "Copy address" copies. */
    val address: String,
    /** The POS sale this till found, when it found it: what a refund needs. */
    val found: FoundSale? = null,
    val refunded: Boolean = false,
    /** The device that made the order, for a sale another till found. */
    val deviceName: String? = null,
    /** Where to read the order again: its `self` link, for an order this till made. */
    val url: String? = null,
)

/** What the till shows: its sales, newest first, and the last thing that went wrong. */
data class TillState(
    val sales: List<TillSale> = emptyList(),
    val problem: String? = null,
    /** The POS sign-in has ended: the cashier must sign in again. */
    val signInAgain: Boolean = false,
)

/**
 * The till (`docs/design.md` §1): turns the POS's Monero sales into
 * Monokulo orders, puts receipts in their codes, and follows them to paid
 * or expired. A sale it finds is handled once it has an order; until then
 * every [check] tries again, and the idempotency key makes a retry, or
 * another till finding the same sale, get the same order.
 */
class Till(
    private val source: SaleSource,
    private val monokulo: MonokuloApi,
    private val connection: Connection,
    /** Only this device's sales, when the POS says which device took each. */
    private val deviceId: String? = null,
) {
    private val state = MutableStateFlow(TillState())
    val sales: StateFlow<TillState> = state.asStateFlow()
    private val checking = Mutex()

    /** Looks for new sales; makes each an order with its code. */
    suspend fun check() = checking.withLock {
        val found = try {
            source.newSales()
        } catch (e: SquareException) {
            state.update { it.copy(problem = e.message, signInAgain = e.signInAgain) }
            return@withLock
        } catch (e: Exception) {
            state.update { it.copy(problem = "Couldn't reach the POS: ${e.message}") }
            return@withLock
        }
        var problem: String? = null
        for (sale in found) {
            if (deviceId != null && sale.deviceId != null && sale.deviceId != deviceId) {
                source.handled(sale)
                continue
            }
            val order = try {
                monokulo.createOrder(
                    connection,
                    amount = decimalAmount(sale.amountMinor, sale.currency),
                    currency = sale.currency,
                    merchantOrderId = sale.reference,
                    idempotencyKey = idempotencyKey(sale),
                )
            } catch (e: Exception) {
                problem = "Couldn't make the payment code: ${e.message}"
                continue
            }
            source.handled(sale)
            val receipt = runCatching { source.receipt(sale) }.getOrNull()
            add(order, ReceiptCode.of(order.paymentUri, receipt), sale)
        }
        state.update { it.copy(problem = problem, signInAgain = false) }
    }

    /** What the store's event stream said. */
    fun onEvent(event: SaleEvent) {
        val phase = event.phase ?: return
        state.update { s ->
            if (s.sales.any { it.orderId == event.orderId }) {
                s.copy(sales = s.sales.map { if (it.orderId == event.orderId) it.copy(phase = phase) else it })
            } else if (phase == SalePhase.Waiting) {
                // Another till's sale: shown with the plain code (no receipt here).
                val uri = PaymentUri.build(event.address, event.piconero)
                s.copy(sales = listOf(event.toTillSale(PaymentCode(uri, hasReceipt = false))) + s.sales)
            } else {
                s
            }
        }
    }

    /** An order's phase, from its status (after the stream reconnects). */
    fun onStatus(orderId: String, phase: SalePhase) {
        state.update { s -> s.copy(sales = s.sales.map { if (it.orderId == orderId) it.copy(phase = phase) else it }) }
    }

    /** Refunds the POS sale of an order that was never paid. */
    suspend fun refund(orderId: String) {
        val sale = state.value.sales.firstOrNull { it.orderId == orderId } ?: return
        val found = sale.found ?: return
        try {
            source.refund(found)
            state.update { s -> s.copy(sales = s.sales.map { if (it.orderId == orderId) it.copy(refunded = true) else it }) }
        } catch (e: Exception) {
            state.update { it.copy(problem = "Couldn't refund it in the POS: ${e.message}. Refund it there instead.") }
        }
    }

    /** Takes a finished sale off the screen. */
    fun dismiss(orderId: String) {
        state.update { s -> s.copy(sales = s.sales.filterNot { it.orderId == orderId }) }
    }

    private fun add(order: Order, code: PaymentCode, found: FoundSale) {
        // Another till may have made this order already, and the store's events
        // may have moved it on (paid, even): keep what they said, else the order's own.
        val known = state.value.sales.firstOrNull { it.orderId == order.orderId }?.phase
        val sale = TillSale(
            orderId = order.orderId,
            amount = order.amount ?: decimalAmount(found.amountMinor, found.currency),
            currency = order.currency ?: found.currency,
            reference = order.merchantOrderId,
            code = code,
            phase = known ?: order.phase,
            url = order.url,
            expiresAt = order.expiresAt,
            xmr = PaymentUri.xmr(order.piconero),
            address = order.address,
            found = found,
        )
        state.update { s -> s.copy(sales = listOf(sale) + s.sales.filterNot { it.orderId == order.orderId }) }
    }

    private fun SaleEvent.toTillSale(code: PaymentCode) = TillSale(
        orderId = orderId,
        amount = amount,
        currency = currency,
        reference = merchantOrderId,
        code = code,
        phase = SalePhase.Waiting,
        expiresAt = expiresAt,
        xmr = PaymentUri.xmr(piconero),
        address = address,
        deviceName = deviceName.ifBlank { null },
    )

    companion object {
        /** `sq:` and the start of SHA-256 of the POS's payment id: the same for every till, and opaque. */
        fun idempotencyKey(sale: FoundSale): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(sale.id.toByteArray())
            return "sq:" + hash.joinToString("") { "%02x".format(it) }.take(40)
        }
    }
}
