package app.monokular

import app.monokular.core.Amounts
import app.monokular.core.SalePhase
import app.monokular.receipt.Receipt
import app.monokular.sales.Standing
import app.monokular.sales.TillSale
import app.monokular.sales.live
import app.monokular.sales.standing
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import kotlin.math.roundToInt

/** How a sale is named and described everywhere it's shown: the app, the bubble and the notification. */
object SaleText {
    /** "Sale #1042" from the POS's receipt number, or "Monero sale" when the POS gave none. */
    fun title(sale: TillSale): String {
        val reference = sale.reference?.trim().orEmpty()
        if (reference.isEmpty()) return "Monero sale"
        return "Sale " + if (reference.startsWith("#")) reference else "#$reference"
    }

    /** Where it came from: "Front Counter · this till" for this till's, "From Back till" for another's. */
    fun place(sale: TillSale, location: String?): String = when {
        sale.found != null && location != null -> "$location · this till"
        sale.found != null -> "This till"
        sale.deviceName != null -> "From ${sale.deviceName}"
        else -> "From another till"
    }

    fun amount(sale: TillSale): String = Amounts.fiat(sale.amount, sale.currency)

    /** The exact XMR to pay: rounding it would have the customer underpay. */
    fun xmr(sale: TillSale): String = "${sale.xmr} XMR"

    /**
     * The sale's state in a few words: the shift row's second line and the
     * detail page's heading. Another till's sale says what this till knows
     * (its code ran out, its payment was reversed, it was closed here), never
     * that the other till closed it, which Monokulo doesn't tell us.
     */
    fun status(sale: TillSale): String = when (val standing = sale.standing) {
        is Standing.Live -> when (standing.phase) {
            SalePhase.Partial -> "Part paid"
            SalePhase.Seen -> "Seen on the network"
            SalePhase.Confirming -> "Confirming"
            else -> "Waiting for payment"
        }
        is Standing.Paid -> when {
            standing.afterRefund -> "Paid after the refund"
            sale.closedAt != null -> "Paid after you closed it"
            standing.late -> "Paid after the code expired"
            else -> "Paid"
        }
        Standing.Closed -> "Closed · not paid"
        Standing.Expired, Standing.Reversed, Standing.OtherTill -> ended(sale.phase)
        Standing.Refunded -> "Refunded in Square"
    }

    /** How a sale that isn't live ended, or that it was closed here while still waiting. */
    private fun ended(phase: SalePhase): String = when (phase) {
        SalePhase.Expired -> "Code expired · not paid"
        SalePhase.Cancelled -> "Cancelled · not paid"
        SalePhase.DoubleSpend -> "Payment reversed"
        else -> "Closed · not paid"
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

    /** "15 items" or "1 item": the receipt's quantities added up. */
    fun items(receipt: Receipt): String {
        val n = itemCount(receipt)
        return if (n == 1) "1 item" else "$n items"
    }

    fun itemCount(receipt: Receipt): Int = receipt.lineItems.sumOf { it.quantity }.roundToInt()

    /** "2 × Flat white, Oat latte, 3 × Almond croissant, +5 more": the first [show] lines, and how many more there are. */
    fun firstItems(receipt: Receipt, show: Int = 3): String {
        val lines = receipt.lineItems
        val head = lines.take(show).joinToString(", ") { line(it) }
        val more = lines.size - show
        return if (more > 0) "$head, +$more more" else head
    }

    /** "2 × Flat white"; one of something is just its name. */
    fun line(item: Receipt.LineItem): String =
        if (item.quantity == 1.0) item.description else "${quantity(item.quantity)} × ${item.description}"

    /** "2", or "1.5" for something sold by weight. */
    fun quantity(quantity: Double): String = BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString()

    /** "9.00": a receipt's amount in the currency's minor digits with no symbol, as the design's order lists have it. */
    fun plain(value: Double, currency: String): String {
        val digits = runCatching { Currency.getInstance(currency.uppercase(Locale.ROOT)).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
        return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()
    }

    /** "4:07 PM" (or "16:07"): a time of day as the till's locale writes it, with a plain space before am or pm. */
    fun time(epochSeconds: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
            .format(Instant.ofEpochSecond(epochSeconds).atZone(zone))
            .replace(NARROW_SPACE, ' ')

    /** The narrow no-break space newer JDKs put before am or pm. */
    private const val NARROW_SPACE = '\u202F'
}

/** Which sale the till shows full screen. */
object TillFocus {
    /**
     * The sale on screen ([focus], the one shown last or opened from the
     * shift) while it's there and the cashier hasn't [left] it, so a sale
     * stays on screen through Paid; else the newest live sale they haven't
     * left. A closed or ended sale never comes up on its own: it's in the
     * shift, under Needs action when it needs them.
     */
    fun pick(sales: List<TillSale>, left: Set<String>, focus: String?): TillSale? {
        val open = sales.filter { it.orderId !in left }
        return open.firstOrNull { it.orderId == focus } ?: open.firstOrNull { it.live }
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
    /** Green for a few seconds after a sale is paid, then lit while a sale's code is up, else faded. */
    fun look(sales: List<TillSale>, justPaid: Boolean): BubbleLook = when {
        justPaid -> BubbleLook.Paid
        sales.any { it.live } -> BubbleLook.Lit
        else -> BubbleLook.Idle
    }

    /**
     * Which sale the bubble and its panel are about: the one just paid,
     * while it shows as paid; else the newest whose code is up; else the
     * last one they showed.
     */
    fun focus(sales: List<TillSale>, previous: String?, justPaid: Boolean): String? {
        val shown = sales.firstOrNull { it.orderId == previous }
        if (justPaid && shown?.phase == SalePhase.Paid) return previous
        return sales.firstOrNull { it.live }?.orderId ?: previous
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
