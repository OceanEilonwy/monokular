package app.monokular.sales

import app.monokular.core.Connection
import app.monokular.core.MonokuloApi
import app.monokular.core.MonokuloException
import app.monokular.core.Order
import app.monokular.core.PaymentUri
import app.monokular.core.Problems
import app.monokular.core.SaleEvent
import app.monokular.core.SalePhase
import app.monokular.receipt.PaymentCode
import app.monokular.receipt.Receipt
import app.monokular.receipt.ReceiptCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Instant

/** A sale on the till's screen, and in its saved shift ([ShiftStore]). */
@Serializable
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
    /**
     * What was bought, as the receipt this till built from the POS's order
     * (`docs/design.md` §4). It is shown on this till's own screens and
     * never leaves the device except inside [code]: never sent to Monokulo,
     * never logged. Null for another till's sale, or when the order couldn't
     * be read.
     */
    val receipt: Receipt? = null,
    /** The device that made the order, for a sale another till found. */
    val deviceName: String? = null,
    /** Where to read the order again: its `self` link, or its sale event's `mk:order`. */
    val url: String? = null,
    /**
     * Monokulo said nothing more will happen to the order (`is_final`). An
     * expired or cancelled order isn't, while Monokulo still watches it for
     * a late payment that turns it paid.
     */
    val settled: Boolean = false,
    /** When it was rung up, Unix seconds: the POS's time for this till's own, the event's for another's. */
    val createdAt: Long = 0,
    /** When its payment was first seen on the network, if it was. */
    val seenAt: Long? = null,
    val paidAt: Long? = null,
    /** When it ended unpaid: the code ran out, the order was cancelled or the payment reversed. */
    val endedAt: Long? = null,
    /** When the cashier closed it on this till (the close sheet's "Not sure" or "No"), if they did. */
    val closedAt: Long? = null,
    /** When the cashier refunded it in Square from this till, if they did. */
    val refundedAt: Long? = null,
) {
    /** Worth reading again: Monokulo may still move it on. */
    val open: Boolean get() = !settled && phase != SalePhase.Paid

    val refunded: Boolean get() = refundedAt != null
}

/** What the till shows: its sales, newest first, and the last thing that went wrong. */
data class TillState(
    val sales: List<TillSale> = emptyList(),
    val problem: String? = null,
    /** The POS sign-in has ended: the cashier must sign in again. */
    val signInAgain: Boolean = false,
    /**
     * Why the POS refused a refund, by order: shown on that sale's page
     * beside its Refund button until it's tried again, which the next
     * check, clearing [problem], doesn't undo.
     */
    val refundProblems: Map<String, String> = emptyMap(),
)

/**
 * Where the till keeps this shift's sales between runs (decision 64): the
 * app backs it with its encrypted store, since a sale's receipt says what
 * was bought.
 */
interface ShiftStore {
    fun load(): List<TillSale>

    fun save(sales: List<TillSale>)
}

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
    /** The time, Unix seconds: what the cashier's marks and a resync's are stamped with. */
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** This shift's sales as saved: loaded now, saved again on every change. */
    private val store: ShiftStore? = null,
) {
    private val state = MutableStateFlow(TillState(sales = store?.load().orEmpty().take(MAX_SALES)))
    val sales: StateFlow<TillState> = state.asStateFlow()
    private val checking = Mutex()
    /** The sales the last [check] found, by id: what the next one's count leaves out. */
    private var lastFound: Set<String> = emptySet()
    private val saving = Any()

    /**
     * Changes the till's state, keeping at most [MAX_SALES] sales, and saves
     * the sales when they changed. The save writes the state as it is then,
     * not as this change left it, so two changes racing still leave the
     * latest on disk.
     */
    private fun change(block: (TillState) -> TillState) {
        var moved = false
        state.update { old ->
            val new = block(old).let { if (it.sales.size > MAX_SALES) it.copy(sales = it.sales.take(MAX_SALES)) else it }
            moved = new.sales != old.sales
            new
        }
        if (moved && store != null) synchronized(saving) { store.save(state.value.sales) }
    }

    /**
     * Looks for new sales; makes each an order with its code. Answers how
     * many of the POS's sales this check found that the last one hadn't,
     * another device's included (the Bell rings for every sale at the
     * location): a sale whose order failed, found again, isn't counted
     * twice. 0 when the POS couldn't be read.
     */
    suspend fun check(): Int = checking.withLock {
        val found = try {
            source.newSales()
        } catch (e: SquareException) {
            change { it.copy(problem = e.message, signInAgain = e.signInAgain) }
            return@withLock 0
        } catch (e: Exception) {
            change { it.copy(problem = "Couldn't reach the POS: ${e.message}") }
            return@withLock 0
        }
        val fresh = found.count { it.id !in lastFound }
        lastFound = found.mapTo(HashSet()) { it.id }
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
                problem = creationProblem(e)
                continue
            }
            source.handled(sale)
            val receipt = runCatching { source.receipt(sale) }.getOrNull()
            add(order, ReceiptCode.of(order.paymentUri, receipt), receipt, sale)
        }
        change { it.copy(problem = problem, signInAgain = false) }
        fresh
    }

    /**
     * What the till says when an order couldn't be made: what Monokulo's
     * problem means and who can fix it ([Problems]). No order was made, so
     * the sale stays unhandled and the next check asks again with the same
     * idempotency key.
     */
    private fun creationProblem(e: Exception): String =
        if (e is MonokuloException) Problems.say(e) else "Couldn't make the payment code: ${e.message}"

    /** What the store's event stream said. */
    fun onEvent(event: SaleEvent) {
        val phase = event.phase ?: return
        change { s ->
            if (s.sales.any { it.orderId == event.orderId }) {
                s.copy(sales = s.sales.map { if (it.orderId == event.orderId) it.moved(phase, event.at).copy(url = it.url ?: event.orderUrl) else it })
            } else if (phase == SalePhase.Waiting) {
                // Another till's sale: shown with the plain code (no receipt here).
                val uri = PaymentUri.build(event.address, event.piconero)
                s.copy(sales = listOf(event.toTillSale(PaymentCode(uri, hasReceipt = false))) + s.sales)
            } else {
                s
            }
        }
    }

    /** An order's phase, from its status, and whether it's [TillSale.settled] (after the stream reconnects). */
    fun onStatus(orderId: String, phase: SalePhase, settled: Boolean = false) {
        val at = now()
        change { s -> s.copy(sales = s.sales.map { if (it.orderId == orderId) it.moved(phase, at).copy(settled = settled) else it }) }
    }

    /**
     * The sale moved on to [to], the time of its first step there kept: seen when
     * its payment first showed, paid, or ended unpaid. A step already timed
     * keeps its time (a resync says what, not when).
     */
    private fun TillSale.moved(to: SalePhase, at: Long): TillSale = after(to).let { phase ->
        copy(
            phase = phase,
            seenAt = seenAt ?: at.takeIf { phase == SalePhase.Seen || phase == SalePhase.Confirming || phase == SalePhase.Partial },
            paidAt = paidAt ?: at.takeIf { phase == SalePhase.Paid },
            endedAt = endedAt ?: at.takeIf { phase.isUnpaid },
        )
    }

    /**
     * Where a sale goes when Monokulo says [to]. Only a payment moves a sale
     * that ended unpaid: a reversed one stays reversed (its order reads
     * `pending` again once the double spend voided the payment, and carries
     * nothing saying so), and an expired or cancelled one never goes back
     * to waiting. Otherwise, what Monokulo says.
     */
    private fun TillSale.after(to: SalePhase): SalePhase = when {
        to == SalePhase.Paid -> to
        phase == SalePhase.DoubleSpend -> phase
        phase.isUnpaid && !to.isUnpaid -> phase
        else -> to
    }

    /**
     * The cashier closed the sale for now: its code comes down and it goes
     * to the shift, where Monokulo's late payment still reaches it and the
     * cashier can refund it. Nothing is said to Monokulo.
     */
    fun close(orderId: String) {
        val at = now()
        change { s -> s.copy(sales = s.sales.map { if (it.orderId == orderId && it.closedAt == null) it.copy(closedAt = at) else it }) }
    }

    /**
     * Watching stopped: every live sale's code comes down, closed as the
     * close sheet's "Not sure" closes one, so none stays up with nothing
     * watching it and each waits in the shift to be refunded or paid late.
     */
    fun closeLive() {
        val at = now()
        change { s -> s.copy(sales = s.sales.map { if (it.live) it.copy(closedAt = at) else it }) }
    }

    /** "Show the code again" on a closed sale: its code is back up. */
    fun reopen(orderId: String) {
        change { s -> s.copy(sales = s.sales.map { if (it.orderId == orderId) it.copy(closedAt = null) else it }) }
    }

    /**
     * Refunds the POS sale of an order that was never paid. A paid sale is
     * never refunded from here, nor one already refunded: the payment may
     * land while the cashier's "No" is on its way.
     */
    suspend fun refund(orderId: String) {
        val sale = state.value.sales.firstOrNull { it.orderId == orderId } ?: return
        if (sale.phase == SalePhase.Paid || sale.refundedAt != null) return
        val found = sale.found ?: return
        change { it.copy(refundProblems = it.refundProblems - orderId) }
        try {
            source.refund(found)
            val at = now()
            change { s -> s.copy(sales = s.sales.map { if (it.orderId == orderId) it.copy(refundedAt = it.refundedAt ?: at) else it }) }
        } catch (e: Exception) {
            change { it.copy(refundProblems = it.refundProblems + (orderId to "Couldn't refund it in the POS: ${e.message}. Refund it there instead.")) }
        }
    }

    private fun add(order: Order, code: PaymentCode, receipt: Receipt?, found: FoundSale) {
        // Another till may have made this order already, and the store's events
        // may have moved it on (paid, even): keep what they said, and when,
        // else the order's own. Read and replaced in the one change, so a
        // close or a refund stamped meanwhile on another thread isn't lost.
        change { s ->
            val known = s.sales.firstOrNull { it.orderId == order.orderId }
            val sale = TillSale(
                orderId = order.orderId,
                amount = order.amount ?: decimalAmount(found.amountMinor, found.currency),
                currency = order.currency ?: found.currency,
                reference = order.merchantOrderId,
                code = code,
                phase = known?.phase ?: order.phase,
                url = order.url,
                settled = known == null && order.isFinal,
                expiresAt = order.expiresAt,
                xmr = PaymentUri.xmr(order.piconero),
                address = order.address,
                found = found,
                receipt = receipt,
                createdAt = runCatching { Instant.parse(found.createdAt).epochSecond }.getOrElse { now() },
                seenAt = known?.seenAt,
                paidAt = known?.paidAt,
                endedAt = known?.endedAt,
                closedAt = known?.closedAt,
                refundedAt = known?.refundedAt,
            )
            s.copy(sales = listOf(sale) + s.sales.filterNot { it.orderId == order.orderId })
        }
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
        url = orderUrl,
        createdAt = at,
    )

    companion object {
        /** The most sales a shift keeps, newest first: older ones drop off the list and the saved shift. */
        const val MAX_SALES = 500

        /** `sq:` and the start of SHA-256 of the POS's payment id: the same for every till, and opaque. */
        fun idempotencyKey(sale: FoundSale): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(sale.id.toByteArray())
            return "sq:" + hash.joinToString("") { "%02x".format(it) }.take(40)
        }
    }
}
