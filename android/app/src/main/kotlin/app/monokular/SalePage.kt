package app.monokular

import app.monokular.core.SalePhase
import app.monokular.sales.Standing
import app.monokular.sales.TillSale
import app.monokular.sales.live
import app.monokular.sales.standing
import java.time.ZoneId
import java.util.Locale

/** The colour roles behind a detail page's hero glyph and its note. */
enum class Ground { Paid, Warn, Danger, Chip, Card }

/** A detail page's note: a few sentences on a card, on [Ground.Danger] for a reversed payment. */
data class Note(val text: String, val ground: Ground = Ground.Card)

/** A line of the timeline card: "Rung up" and its time, or "Till" and the till's name. */
data class Fact(val label: String, val value: String)

/** What a page offers: refund the Square sale, put the code back up. */
enum class PageAction { Refund, ShowCodeAgain }

/** A sale's page when it isn't live (the design's row 2b), in words and roles. */
data class SalePageModel(
    val icon: StateIcon,
    val ground: Ground,
    val heading: String,
    /** The heading in warn: paid after the refund, which needs the cashier. */
    val headingWarn: Boolean,
    /** The amount in muted: refunded, so it no longer counts. */
    val amountMuted: Boolean,
    val line: String,
    val note: Note?,
    /** Whether the page shows what was bought: never for another till's sale, which this till has no receipt for. */
    val order: Boolean,
    val facts: List<Fact>,
    val actions: List<PageAction>,
)

/**
 * The detail pages (PaidDetail, LatePaidDetail, ClosedDetail,
 * ExpiredDetail, ReversedDetail, RefundedDetail, OtherTillDetail): what
 * each says, from where the sale stands. Times the app doesn't know aren't
 * written (decision 71).
 */
object SalePage {
    fun of(sale: TillSale, now: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): SalePageModel {
        fun time(at: Long) = SaleText.time(at, locale, zone)
        fun fact(label: String, at: Long?) = at?.takeIf { it > 0 }?.let { Fact(label, time(it)) }
        val xmr = SaleText.xmr(sale)
        val rungUp = fact("Rung up", sale.createdAt)
        val ended = fact(endedLabel(sale.phase), sale.endedAt)
        val watching = when {
            sale.settled -> "Monokulo has stopped watching it."
            else -> "Monokulo keeps watching for a late payment for a while: if one arrives, this sale turns paid."
        }
        return when (val standing = sale.standing) {
            is Standing.Paid -> {
                val closed = sale.closedAt != null
                val note = when {
                    standing.afterRefund -> Note("$xmr arrived after the Square sale was refunded. Ring the sale up in Square again, or return the payment.")
                    closed -> Note("The customer paid ${after(sale.closedAt, sale.paidAt)}after the sale was closed. $NOTHING_TO_DO")
                    standing.late -> Note("The customer paid ${after(sale.endedAt, sale.paidAt)}after the code ran out. $NOTHING_TO_DO")
                    else -> null
                }
                val facts = when {
                    standing.afterRefund -> listOf(rungUp, refundedBecause(sale, ::fact), fact("Refunded in Square", sale.refundedAt), fact("Paid", sale.paidAt))
                    closed -> listOf(rungUp, fact("Closed", sale.closedAt), fact("Paid", sale.paidAt))
                    standing.late -> listOf(rungUp, ended, fact("Paid", sale.paidAt))
                    else -> listOf(rungUp, fact("Seen on the network", sale.seenAt), fact("Paid", sale.paidAt))
                }
                SalePageModel(
                    StateIcon.Tick, Ground.Paid, SaleText.status(sale), headingWarn = standing.afterRefund, amountMuted = false,
                    line = "$xmr received" + sale.paidAt?.let { " · " + time(it) }.orEmpty(),
                    note = note, order = true, facts = facts.filterNotNull(), actions = emptyList(),
                )
            }
            Standing.Closed -> SalePageModel(
                StateIcon.Clock, Ground.Warn, SaleText.status(sale), headingWarn = false, amountMuted = false, line = xmr,
                note = Note(
                    "Square has this sale as Monero. If the customer isn't paying, refund it. " + when {
                        sale.settled -> "Monokulo has stopped watching it."
                        sale.expiresAt > now -> "The code is still live until ${time(sale.expiresAt)}, and Monokulo keeps watching for a late payment after that: if one arrives, this sale turns paid."
                        else -> watching
                    },
                ),
                order = true, facts = listOfNotNull(rungUp, fact("Closed", sale.closedAt)),
                actions = listOf(PageAction.Refund, PageAction.ShowCodeAgain),
            )
            Standing.Expired -> SalePageModel(
                StateIcon.Clock, Ground.Warn, SaleText.status(sale), headingWarn = false, amountMuted = false, line = xmr,
                note = Note(
                    if (sale.phase == SalePhase.Cancelled) {
                        "The order was cancelled in Monokulo. Square has this sale as Monero: refund it if the customer isn't paying."
                    } else {
                        "The code ran out at ${time(sale.expiresAt)} with no payment. Square has this sale as Monero: refund it if the customer isn't paying. $watching"
                    },
                ),
                order = true, facts = listOfNotNull(rungUp, ended), actions = listOf(PageAction.Refund),
            )
            Standing.Reversed -> SalePageModel(
                StateIcon.Warning, Ground.Danger, SaleText.status(sale), headingWarn = false, amountMuted = false, line = "$xmr · no longer counts",
                note = Note("The payment was spent twice, so it doesn't count. Don't hand anything over. Square has this sale as Monero: refund it there.", Ground.Danger),
                order = true, facts = listOfNotNull(rungUp, fact("Seen on the network", sale.seenAt), ended),
                actions = listOf(PageAction.Refund),
            )
            Standing.Refunded -> SalePageModel(
                StateIcon.Refund, Ground.Chip, SaleText.status(sale), headingWarn = false, amountMuted = true,
                line = listOfNotNull(sale.refundedAt?.let { "Refunded " + time(it) }, "nothing was paid").joinToString(" · "),
                note = Note("Nothing more to do. If the customer pays after all, Monokular tells you straight away and this sale shows Paid after the refund."),
                order = true, facts = listOfNotNull(rungUp, refundedBecause(sale, ::fact), fact("Refunded in Square", sale.refundedAt)),
                actions = emptyList(),
            )
            Standing.RefundInPos -> SalePageModel(
                StateIcon.Refund, Ground.Warn, SaleText.status(sale), headingWarn = false, amountMuted = false, line = "$xmr · not paid",
                note = Note("Refund this sale in the POS. Monokular will notice when it's done."),
                order = true, facts = listOfNotNull(rungUp, refundedBecause(sale, ::fact)),
                actions = listOf(PageAction.Refund),
            )
            Standing.OtherTill -> {
                val till = sale.deviceName
                SalePageModel(
                    StateIcon.Close, Ground.Chip, SaleText.status(sale), headingWarn = false, amountMuted = false, line = "$xmr · not paid",
                    note = Note(
                        (if (till != null) "$till rang this sale up." else "Another till rang this sale up.") +
                            " Its Square sale and what was bought stay on that till, so refund it there if the customer isn't paying. A late payment still shows here.",
                    ),
                    order = false,
                    facts = listOfNotNull(
                        till?.let { Fact("Till", it) },
                        rungUp,
                        if (sale.phase.isFinal) ended else fact("Closed here", sale.closedAt),
                    ),
                    actions = emptyList(),
                )
            }
            // A live sale shows its code, not a page; this is only what it would say.
            is Standing.Live -> SalePageModel(
                StateIcon.Ring, Ground.Chip, SaleText.status(sale), headingWarn = false, amountMuted = false, line = xmr,
                note = null, order = true, facts = listOfNotNull(rungUp, fact("Seen on the network", sale.seenAt)), actions = emptyList(),
            )
        }
    }

    /**
     * "15 minutes ", "1 minute ", "under a minute " from [from] to [to]: how
     * late a payment came. Empty when either time isn't known.
     */
    fun after(from: Long?, to: Long?): String {
        if (from == null || to == null) return ""
        val minutes = (to - from).coerceAtLeast(0) / 60
        return when (minutes) {
            0L -> "under a minute "
            1L -> "1 minute "
            else -> "$minutes minutes "
        }
    }

    /** How a refunded sale came to be refunded: the cashier closed it, or its code ran out first. */
    private fun refundedBecause(sale: TillSale, fact: (String, Long?) -> Fact?): Fact? = when {
        sale.closedAt != null -> fact("Closed: customer not paying", sale.closedAt)
        else -> fact(endedLabel(sale.phase), sale.endedAt)
    }

    private fun endedLabel(phase: SalePhase): String = when (phase) {
        SalePhase.Cancelled -> "Cancelled"
        SalePhase.DoubleSpend -> "Reversed"
        else -> "Code expired"
    }

    private const val NOTHING_TO_DO = "Square already has it as Monero, so there's nothing to do. Hand over the order if they're still here."
}

/** The close sheet's three answers to "Is the customer paying?" (the design's CloseAsk). */
enum class CloseChoice { RefundIt, CloseForNow, KeepUp }

/** What the close sheet's answer does: close the sale, refund it, and where the cashier lands. */
data class CloseOutcome(val close: Boolean, val refund: Boolean, val then: Then) {
    enum class Then {
        /** Stay on the sale, which shows its page now (Refunded, or Closed when the refund failed). */
        Sale,
        Shift,
        /** Nothing happens: the sheet goes and the code stays up. */
        Stay,
    }
}

/** The close sheet (decisions 62, 70). */
object Closing {
    /**
     * The answers offered: all three for this till's sale; for another
     * till's, "No: close it" and "Yes" (this till can't refund it, so "No"
     * and "Not sure" would be the same); none once it's refunded or its code
     * is down, where the sale screen has no X.
     */
    fun choices(sale: TillSale): List<CloseChoice> = when {
        sale.standing !is Standing.Live -> emptyList()
        sale.found == null -> listOf(CloseChoice.CloseForNow, CloseChoice.KeepUp)
        else -> CloseChoice.entries
    }

    /**
     * Whether the sheet's answer still stands for [sale] as it is now: only
     * while its code is up. Paid (or ended) since the cashier tapped, the
     * answer is dropped, so a paid sale is never closed or refunded by it.
     */
    fun applies(sale: TillSale?): Boolean = sale?.live == true

    fun outcome(choice: CloseChoice): CloseOutcome = when (choice) {
        CloseChoice.RefundIt -> CloseOutcome(close = true, refund = true, then = CloseOutcome.Then.Sale)
        CloseChoice.CloseForNow -> CloseOutcome(close = true, refund = false, then = CloseOutcome.Then.Shift)
        CloseChoice.KeepUp -> CloseOutcome(close = false, refund = false, then = CloseOutcome.Then.Stay)
    }

    /** An answer's words: another till's sale's "No" only closes it. */
    fun title(choice: CloseChoice, sale: TillSale): String = when (choice) {
        CloseChoice.RefundIt -> "No: refund it in Square"
        CloseChoice.CloseForNow -> if (sale.found == null) "No: close it" else "Not sure: close it for now"
        CloseChoice.KeepUp -> "Yes: keep the code up"
    }

    /** The line under an answer, with [secondsLeft] on the code for "Yes". */
    fun detail(choice: CloseChoice, sale: TillSale, secondsLeft: Long): String = when (choice) {
        CloseChoice.RefundIt -> "Monokular refunds the sale and closes it."
        CloseChoice.CloseForNow -> if (sale.found == null) {
            "Refund it in Square on ${sale.deviceName ?: "the till that rang it up"}. A late payment still shows here."
        } else {
            "It stays in the shift. A late payment still counts, and you can refund it there."
        }
        CloseChoice.KeepUp -> when (sale.phase) {
            SalePhase.Waiting, SalePhase.Partial -> "${SaleText.clock(secondsLeft)} left to pay."
            else -> "The payment is being confirmed."
        }
    }

    /** "Sale #bP9m · $13.88 · Square has it as Monero", or "… · From Back till" for another till's sale. */
    fun subtitle(sale: TillSale): String =
        "${SaleText.title(sale)} · ${SaleText.amount(sale)} · " +
            if (sale.found != null) "Square has it as Monero" else SaleText.place(sale, null)
}
