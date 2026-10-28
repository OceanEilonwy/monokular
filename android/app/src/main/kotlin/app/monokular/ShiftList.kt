package app.monokular

import app.monokular.core.Amounts
import app.monokular.core.PointOfSale
import app.monokular.core.SalePhase
import app.monokular.sales.Standing
import app.monokular.sales.TillSale
import app.monokular.sales.needsAction
import app.monokular.sales.standing
import java.math.BigDecimal
import java.time.ZoneId
import java.util.Locale

/** The shift's filter chips (ShiftList's All / Needs action / Paid). */
enum class ShiftFilter { All, NeedsAction, Paid }

/** What a shift row opens: the code, for a sale that's live, else the sale's page. */
enum class RowOpens { Code, Details }

/** The colour role a row's second line is in (`ui/Theme.kt`'s tokens of the same names). */
enum class Tone { Link, Warn, Danger, Paid, Muted }

/** The glyph in a row's circle, and on a detail page's hero. */
enum class StateIcon { Ring, Clock, Warning, Tick, Refund, Close }

/** One shift row, in words and roles: what the screen draws for a sale. */
data class ShiftRow(
    val title: String,
    val sub: String,
    val tone: Tone,
    /** The title in muted, as a refunded sale's is: nothing more to do. */
    val quiet: Boolean,
    val icon: StateIcon,
    val opens: RowOpens,
)

/**
 * The shift (the design's ShiftList): which sales each filter shows, how
 * many need the cashier, the summary line and each row's words. Rows are
 * newest first, as the till keeps them.
 */
object ShiftList {
    fun rows(sales: List<TillSale>, filter: ShiftFilter): List<TillSale> = when (filter) {
        ShiftFilter.All -> sales
        ShiftFilter.NeedsAction -> sales.filter { it.needsAction }
        ShiftFilter.Paid -> sales.filter { it.standing is Standing.Paid }
    }

    /** What a new shift keeps of the last one's [sales]: those that still need the cashier (decision 86). */
    fun carried(sales: List<TillSale>): List<TillSale> = sales.filter { it.needsAction }

    /** What the Needs action chip counts (decision 60). */
    fun needsAction(sales: List<TillSale>): Int = sales.count { it.needsAction }

    /**
     * "5 paid · $69.50 · since 9:02 AM", or "Nothing paid yet · since 9:02 AM": the sales paid since the shift
     * started summed per currency (decision 72). A sale carried over from the last shift, paid after its refund,
     * still needs the cashier but isn't this shift's takings.
     */
    fun summary(sales: List<TillSale>, startedAt: Long?, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
        val paid = sales.filter { it.phase == SalePhase.Paid && (startedAt == null || (it.paidAt ?: startedAt) >= startedAt) }
        val totals = paid.groupBy { it.currency }.map { (currency, those) ->
            Amounts.fiat(those.sumOf { runCatching { BigDecimal(it.amount) }.getOrDefault(BigDecimal.ZERO) }.toPlainString(), currency, locale)
        }
        val head = if (paid.isEmpty()) listOf("Nothing paid yet") else listOf("${paid.size} paid") + totals
        val since = startedAt?.let { "since " + SaleText.time(it, locale, zone) }
        return (head + listOfNotNull(since)).joinToString(" · ")
    }

    /** Where a row goes: a live sale's code, any other sale's page (the design's note s2). */
    fun opens(sale: TillSale): RowOpens = if (sale.standing is Standing.Live) RowOpens.Code else RowOpens.Details

    /** The row for [sale] at a till on [till], with [secondsLeft] on its code when it's waiting or part paid. */
    fun row(
        sale: TillSale,
        till: PointOfSale?,
        secondsLeft: Long,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ShiftRow {
        val pos = PosText.of(sale, till)
        fun at(time: Long?) = time?.let { " · " + SaleText.time(it, locale, zone) }.orEmpty()
        val left = " · ${SaleText.clock(secondsLeft)} left"
        val other = sale.deviceName ?: "another till"
        val standing = sale.standing
        val (sub, tone, icon) = when (standing) {
            is Standing.Live -> Triple(
                when (standing.phase) {
                    SalePhase.Partial -> "Part paid$left"
                    SalePhase.Seen -> "Seen on the network"
                    SalePhase.Confirming -> "Confirming"
                    else -> "Waiting$left"
                },
                Tone.Link, StateIcon.Ring,
            )
            is Standing.Paid -> Triple(
                SaleText.status(sale, till) + at(sale.paidAt),
                when {
                    standing.afterRefund -> Tone.Warn
                    standing.late -> Tone.Paid
                    else -> Tone.Muted
                },
                StateIcon.Tick,
            )
            Standing.Closed -> Triple("Closed · not paid · " + PosText.refundItIn(pos), Tone.Warn, StateIcon.Clock)
            Standing.Expired -> Triple(SaleText.status(sale, till), Tone.Warn, StateIcon.Clock)
            Standing.Reversed -> Triple("Payment reversed · don't hand it over", Tone.Danger, StateIcon.Warning)
            Standing.Refunded -> Triple(PosText.refundedIn(pos) + at(sale.refundedAt), Tone.Muted, StateIcon.Refund)
            Standing.RefundInPos -> Triple(
                PosText.refundItIn(pos).replaceFirstChar(Char::uppercase) + orderOf(sale) + at(sale.refundPendingAt),
                Tone.Warn, StateIcon.Refund,
            )
            Standing.OtherTill -> Triple(
                if (sale.phase.isFinal) "${SaleText.status(sale, till)} · on $other" else "Closed · not paid · refund it on $other",
                Tone.Muted, StateIcon.Close,
            )
        }
        return ShiftRow(
            title = "${SaleText.title(sale)} · ${SaleText.amount(sale)}",
            sub = sub,
            tone = tone,
            quiet = standing == Standing.Refunded,
            icon = icon,
            opens = opens(sale),
        )
    }

    /** " · order 7F3K…": the point of sale's order to refund, by the start of its id, when the till has it. */
    private fun orderOf(sale: TillSale): String = sale.found?.orderId?.let { " · order ${it.take(4)}…" }.orEmpty()

    /** What an empty filter says where its rows would be. */
    fun empty(filter: ShiftFilter): String = when (filter) {
        ShiftFilter.All -> "No sale right now"
        ShiftFilter.NeedsAction -> "Nothing needs action"
        ShiftFilter.Paid -> "Nothing paid yet"
    }
}
