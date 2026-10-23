package app.monokular

import app.monokular.core.Amounts
import app.monokular.core.SalePhase
import app.monokular.sales.TillSale

/** How a sale is named and described everywhere it's shown: the app, the bubble and the notification. */
object SaleText {
    /** "Sale #1042" from the POS's receipt number, or "Monero sale" when the POS gave none. */
    fun title(sale: TillSale): String {
        val reference = sale.reference?.trim().orEmpty()
        if (reference.isEmpty()) return "Monero sale"
        return "Sale " + if (reference.startsWith("#")) reference else "#$reference"
    }

    /** Where it came from: "Square · Front Counter" for this till's, the other device's name for another's. */
    fun place(sale: TillSale, location: String?): String = when {
        sale.found != null && location != null -> "Square · $location"
        sale.found != null -> "Square"
        sale.deviceName != null -> "From ${sale.deviceName}"
        else -> "From another till"
    }

    fun amount(sale: TillSale): String = Amounts.fiat(sale.amount, sale.currency)

    /** The exact XMR to pay: rounding it would have the customer underpay. */
    fun xmr(sale: TillSale): String = "${sale.xmr} XMR"

    fun status(sale: TillSale): String = when (sale.phase) {
        SalePhase.Waiting -> "Waiting for payment"
        SalePhase.Partial -> "Part paid"
        SalePhase.Seen -> "Seen on the network"
        SalePhase.Confirming -> "Confirming"
        SalePhase.Paid -> "Paid"
        SalePhase.Expired -> if (sale.refunded) "Refunded in Square" else "No payment yet"
        SalePhase.Cancelled -> if (sale.refunded) "Refunded in Square" else "Cancelled"
        SalePhase.DoubleSpend -> "Payment reversed"
    }

    /** Which of the four steps (Waiting, Seen, Confirming, Paid) it's at, from 1; 0 when it ended unpaid. */
    fun step(phase: SalePhase): Int = when (phase) {
        SalePhase.Waiting, SalePhase.Partial -> 1
        SalePhase.Seen -> 2
        SalePhase.Confirming -> 3
        SalePhase.Paid -> 4
        SalePhase.Expired, SalePhase.Cancelled, SalePhase.DoubleSpend -> 0
    }

    val steps = listOf("Waiting", "Seen", "Confirming", "Paid")

    /** "1:05" from seconds. */
    fun clock(seconds: Long): String = "${seconds / 60}:${"%02d".format(seconds % 60)}"
}

/** Which sale the till shows full screen. */
object TillFocus {
    /**
     * The sale on screen ([focus], the one shown last or opened from the
     * list) while it's there and not closed, so a sale stays on screen
     * through Paid; else the newest still waiting; else the newest that
     * ended unpaid and still needs refunding. Sales the cashier closed
     * never come back on their own, and nor do paid ones.
     */
    fun pick(sales: List<TillSale>, closed: Set<String>, focus: String?): TillSale? {
        val open = sales.filter { it.orderId !in closed }
        return open.firstOrNull { it.orderId == focus }
            ?: open.firstOrNull { !it.phase.isFinal }
            ?: open.firstOrNull { it.phase.isUnpaid && it.found != null && !it.refunded }
    }
}

/**
 * Which sales changed phase since the last look: what the bubble and the
 * notifications react to ("Paid" for a few seconds, an alert on a new sale).
 */
class PhaseChanges {
    private var last = emptyMap<String, SalePhase>()
    private var started = false

    /** The sales in [sales] that are new or in a new phase. The first look reports nothing: those sales aren't news. */
    fun next(sales: List<TillSale>): List<TillSale> {
        val changed = if (started) sales.filter { last[it.orderId] != it.phase } else emptyList()
        last = sales.associate { it.orderId to it.phase }
        started = true
        return changed
    }
}

/** What the bubble shows. */
enum class BubbleLook { Idle, Lit, Paid }

object Bubble {
    /** Green for a few seconds after a sale is paid, then lit while a sale waits for its money, else faded. */
    fun look(sales: List<TillSale>, justPaid: Boolean): BubbleLook = when {
        justPaid -> BubbleLook.Paid
        sales.any { !it.phase.isFinal } -> BubbleLook.Lit
        else -> BubbleLook.Idle
    }

    /**
     * Which sale the bubble and its panel are about: the one just paid,
     * while it shows as paid; else the newest still waiting; else the last
     * one they showed.
     */
    fun focus(sales: List<TillSale>, previous: String?, justPaid: Boolean): String? {
        val shown = sales.firstOrNull { it.orderId == previous }
        if (justPaid && shown?.phase == SalePhase.Paid) return previous
        return sales.firstOrNull { !it.phase.isFinal }?.orderId ?: previous
    }

    /** What TalkBack says for it, as the design's aria labels do. */
    fun label(look: BubbleLook): String = when (look) {
        BubbleLook.Idle -> "Monokular, waiting for sales"
        BubbleLook.Lit -> "Monokular, new sale, tap to show the code"
        BubbleLook.Paid -> "Monokular, paid"
    }

    /** How long the bubble stays green after a sale is paid. */
    const val PAID_MS = 4_000L
}
