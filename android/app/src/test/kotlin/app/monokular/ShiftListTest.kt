package app.monokular

import app.monokular.core.SalePhase
import app.monokular.receipt.PaymentCode
import app.monokular.receipt.Receipt
import app.monokular.sales.FoundSale
import app.monokular.sales.TillSale
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShiftListTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val at = Instant.parse("2026-10-22T22:58:00Z").epochSecond // 3:58 PM there

    private fun sale(
        id: String,
        phase: SalePhase = SalePhase.Waiting,
        mine: Boolean = true,
        amount: String = "8.40",
        currency: String = "USD",
        closedAt: Long? = null,
        refundedAt: Long? = null,
        paidAt: Long? = null,
        endedAt: Long? = null,
        device: String? = "Back till",
    ) = TillSale(
        orderId = id,
        amount = amount,
        currency = currency,
        reference = id,
        code = PaymentCode("monero:4abc?tx_amount=0.1", hasReceipt = mine),
        phase = phase,
        expiresAt = 0,
        xmr = "0.0336",
        address = "4abc",
        found = if (mine) FoundSale("p_$id", 840, currency, id, "o", "2026-10-22T22:50:00Z", null) else null,
        deviceName = if (mine) null else device,
        createdAt = at - 600,
        paidAt = paidAt,
        endedAt = endedAt,
        closedAt = closedAt,
        refundedAt = refundedAt,
    )

    /** acceptance §12's fixtures, newest first. */
    private val shift = listOf(
        sale("waiting"),
        sale("closedHere", closedAt = at),
        sale("closedTheirs", mine = false, closedAt = at),
        sale("expired", phase = SalePhase.Expired, endedAt = at),
        sale("expiredTheirs", phase = SalePhase.Expired, mine = false, endedAt = at),
        sale("reversed", phase = SalePhase.DoubleSpend, endedAt = at),
        sale("refunded", phase = SalePhase.Expired, closedAt = at - 60, refundedAt = at),
        sale("paid", phase = SalePhase.Paid, amount = "24.50", paidAt = at),
        sale("latePaid", phase = SalePhase.Paid, amount = "6.00", closedAt = at - 900, paidAt = at),
        sale("paidAfterRefund", phase = SalePhase.Paid, amount = "11.00", closedAt = at - 900, refundedAt = at - 800, paidAt = at),
        sale("cancelled", phase = SalePhase.Cancelled, endedAt = at),
    )

    private fun ids(sales: List<TillSale>) = sales.map { it.orderId }

    @Test
    fun `needs action counts what this till can still refund, and a payment after the refund`() {
        assertEquals(5, ShiftList.needsAction(shift))
        assertEquals(
            listOf("closedHere", "expired", "reversed", "paidAfterRefund", "cancelled"),
            ids(ShiftList.rows(shift, ShiftFilter.NeedsAction)),
            "never another till's, a refunded, a paid or a waiting sale",
        )
    }

    @Test
    fun `a new shift keeps the last one's sales that still need action, and only those`() {
        assertEquals(ids(ShiftList.rows(shift, ShiftFilter.NeedsAction)), ids(ShiftList.carried(shift)))
        assertEquals(emptyList(), ShiftList.carried(listOf(sale("w"))))
    }

    @Test
    fun `the filters keep the shift's order, newest first`() {
        assertEquals(ids(shift), ids(ShiftList.rows(shift, ShiftFilter.All)))
        assertEquals(listOf("paid", "latePaid", "paidAfterRefund"), ids(ShiftList.rows(shift, ShiftFilter.Paid)))
        assertEquals(emptyList(), ShiftList.rows(listOf(sale("w")), ShiftFilter.NeedsAction))
        assertEquals("Nothing needs action", ShiftList.empty(ShiftFilter.NeedsAction))
        assertEquals("Nothing paid yet", ShiftList.empty(ShiftFilter.Paid))
    }

    @Test
    fun `the summary counts paid sales, sums them per currency and says since when`() {
        val started = Instant.parse("2026-10-22T16:02:00Z").epochSecond
        assertEquals("3 paid · $41.50 · since 9:02 AM", ShiftList.summary(shift, started, Locale.US, zone))
        assertEquals("Nothing paid yet · since 9:02 AM", ShiftList.summary(listOf(sale("w")), started, Locale.US, zone))
        assertEquals("Nothing paid yet", ShiftList.summary(emptyList(), null, Locale.US, zone))
        val mixed = listOf(sale("a", SalePhase.Paid, amount = "1.25"), sale("b", SalePhase.Paid, amount = "500", currency = "JPY"), sale("c", SalePhase.Paid, amount = "2.75"))
        assertEquals("3 paid · $4.00 · ¥500", ShiftList.summary(mixed, null, Locale.US, zone))
        // A sale the last shift carried over, paid after its refund, isn't this shift's takings.
        val carried = sale("paidAfterRefund", SalePhase.Paid, amount = "11.00", closedAt = started - 900, refundedAt = started - 800, paidAt = started - 60)
        assertEquals("1 paid · $24.50 · since 9:02 AM", ShiftList.summary(listOf(carried, sale("paid", SalePhase.Paid, amount = "24.50", paidAt = at)), started, Locale.US, zone))
    }

    @Test
    fun `a live sale's row opens its code, every other row its page`() {
        val live = listOf(sale("w"), sale("s", SalePhase.Seen), sale("c", SalePhase.Confirming), sale("p", SalePhase.Partial), sale("t", mine = false))
        assertEquals(List(5) { RowOpens.Code }, live.map(ShiftList::opens))
        assertEquals(List(10) { RowOpens.Details }, shift.drop(1).map(ShiftList::opens))
    }

    @Test
    fun `each row says how its sale stands, in its state's colour`() {
        fun row(sale: TillSale) = ShiftList.row(sale, secondsLeft = 552, Locale.US, zone).let { Triple(it.sub, it.tone, it.icon) }
        assertEquals(Triple("Waiting · 9:12 left", Tone.Link, StateIcon.Ring), row(sale("w")))
        assertEquals(Triple("Seen on the network", Tone.Link, StateIcon.Ring), row(sale("w", SalePhase.Seen)))
        assertEquals(Triple("Confirming", Tone.Link, StateIcon.Ring), row(sale("w", SalePhase.Confirming)))
        assertEquals(Triple("Part paid · 9:12 left", Tone.Link, StateIcon.Ring), row(sale("w", SalePhase.Partial)))
        val rows = shift.associate { it.orderId to row(it) }
        assertEquals(Triple("Closed · not paid · refund it in Square", Tone.Warn, StateIcon.Clock), rows["closedHere"])
        assertEquals(Triple("Closed · not paid · refund it on Back till", Tone.Muted, StateIcon.Close), rows["closedTheirs"])
        assertEquals(Triple("Code expired · not paid", Tone.Warn, StateIcon.Clock), rows["expired"])
        assertEquals(Triple("Code expired · not paid · on Back till", Tone.Muted, StateIcon.Close), rows["expiredTheirs"])
        assertEquals(Triple("Cancelled · not paid", Tone.Warn, StateIcon.Clock), rows["cancelled"])
        assertEquals(Triple("Payment reversed · don't hand it over", Tone.Danger, StateIcon.Warning), rows["reversed"])
        assertEquals(Triple("Refunded in Square · 3:58 PM", Tone.Muted, StateIcon.Refund), rows["refunded"])
        assertEquals(Triple("Paid · 3:58 PM", Tone.Muted, StateIcon.Tick), rows["paid"])
        assertEquals(Triple("Paid after you closed it · 3:58 PM", Tone.Paid, StateIcon.Tick), rows["latePaid"])
        assertEquals(Triple("Paid after the refund · 3:58 PM", Tone.Warn, StateIcon.Tick), rows["paidAfterRefund"])
        assertEquals(
            Triple("Paid after the code expired · 3:58 PM", Tone.Paid, StateIcon.Tick),
            row(sale("x", SalePhase.Paid, endedAt = at - 60, paidAt = at)),
        )
        assertEquals(
            Triple("Code expired · not paid · on another till", Tone.Muted, StateIcon.Close),
            row(sale("x", SalePhase.Expired, mine = false, device = null)),
        )
        val refunded = ShiftList.row(shift.single { it.orderId == "refunded" }, 0, Locale.US, zone)
        assertEquals("Sale #refunded · ${SaleText.amount(sale("refunded"))}", refunded.title, "the amount as the locale writes it")
        assertEquals(true, refunded.quiet, "a refunded sale's title is muted too")
        assertEquals(false, ShiftList.row(sale("w"), 0).quiet)
    }

    @Test
    fun `the close sheet's answers close, refund and go where the design says`() {
        assertEquals(CloseOutcome(close = true, refund = true, then = CloseOutcome.Then.Sale), Closing.outcome(CloseChoice.RefundIt))
        assertEquals(CloseOutcome(close = true, refund = false, then = CloseOutcome.Then.Shift), Closing.outcome(CloseChoice.CloseForNow))
        assertEquals(CloseOutcome(close = false, refund = false, then = CloseOutcome.Then.Stay), Closing.outcome(CloseChoice.KeepUp))
        assertEquals(CloseChoice.entries, Closing.choices(sale("w")))
        val theirs = sale("t", mine = false)
        assertEquals(listOf(CloseChoice.CloseForNow, CloseChoice.KeepUp), Closing.choices(theirs), "this till can't refund another's")
        assertEquals("No: close it", Closing.title(CloseChoice.CloseForNow, theirs))
        assertEquals("Refund it in Square on Back till. A late payment still shows here.", Closing.detail(CloseChoice.CloseForNow, theirs, 0))
        assertEquals("Not sure: close it for now", Closing.title(CloseChoice.CloseForNow, sale("w")))
        assertEquals("14:52 left to pay.", Closing.detail(CloseChoice.KeepUp, sale("w"), 892))
        assertEquals(emptyList(), Closing.choices(shift.single { it.orderId == "refunded" }), "nothing to ask: no X")
        assertEquals(emptyList(), Closing.choices(sale("w", closedAt = 1)))
        assertTrue(Closing.applies(sale("w")), "the code is still up: the answer stands")
        assertFalse(Closing.applies(sale("p", SalePhase.Paid)), "paid as the cashier tapped: the answer is dropped")
        assertFalse(Closing.applies(sale("w", closedAt = 1)))
        assertFalse(Closing.applies(null), "gone from the shift")
        val amount = SaleText.amount(sale("w"))
        assertEquals("Sale #w · $amount · Square has it as Monero", Closing.subtitle(sale("w")))
        assertEquals("Sale #t · $amount · From Back till", Closing.subtitle(theirs))
    }

    @Test
    fun `a late payment says how late, in words`() {
        assertEquals("under a minute ", SalePage.after(100, 159))
        assertEquals("1 minute ", SalePage.after(100, 160))
        assertEquals("15 minutes ", SalePage.after(100, 100 + 15 * 60 + 30))
        assertEquals("", SalePage.after(null, 100))
        val late = SalePage.of(shift.single { it.orderId == "latePaid" }, now = at, Locale.US, zone)
        assertEquals(
            "The customer paid 15 minutes after the sale was closed. Square already has it as Monero, so there's nothing to do. Hand over the order if they're still here.",
            late.note?.text,
        )
        assertEquals(listOf("Rung up", "Closed", "Paid"), late.facts.map { it.label })
        val expiredThenPaid = SalePage.of(sale("x", SalePhase.Paid, endedAt = at - 60, paidAt = at), now = at, Locale.US, zone)
        assertEquals("Paid after the code expired", expiredThenPaid.heading)
        assertEquals(true, expiredThenPaid.note!!.text.startsWith("The customer paid 1 minute after the code ran out."))
        assertEquals(listOf("Rung up", "Code expired", "Paid"), expiredThenPaid.facts.map { it.label })
    }

    @Test
    fun `each page says what the design says, without times the app doesn't know`() {
        fun page(id: String) = SalePage.of(shift.single { it.orderId == id }, now = at, Locale.US, zone)
        val paid = page("paid")
        assertEquals("Paid", paid.heading)
        assertEquals("0.0336 XMR received · 3:58 PM", paid.line)
        assertNull(paid.note)
        assertEquals(listOf(Fact("Rung up", "3:48 PM"), Fact("Paid", "3:58 PM")), paid.facts, "no seen row when it wasn't seen; no confirmations")
        assertEquals(emptyList(), paid.actions)

        val closed = page("closedHere")
        assertEquals(listOf(PageAction.Refund, PageAction.ShowCodeAgain), closed.actions)
        assertEquals(
            "Square has this sale as Monero. If the customer isn't paying, refund it. Monokulo keeps watching for a late payment for a while: if one arrives, this sale turns paid.",
            closed.note?.text,
        )
        val stillLive = SalePage.of(sale("c", closedAt = at).copy(expiresAt = at + 1440), now = at, Locale.US, zone)
        assertEquals(true, stillLive.note!!.text.contains("The code is still live until 4:22 PM, and Monokulo keeps watching"))
        val settled = SalePage.of(sale("c", SalePhase.Expired, endedAt = at).copy(settled = true), now = at, Locale.US, zone)
        assertEquals(true, settled.note!!.text.endsWith("Monokulo has stopped watching it."))
        assertEquals(listOf(PageAction.Refund), settled.actions, "an expired code can't be shown again")

        val reversed = page("reversed")
        assertEquals(Ground.Danger, reversed.note?.ground)
        assertEquals("0.0336 XMR · no longer counts", reversed.line)

        val refunded = page("refunded")
        assertEquals("Refunded 3:58 PM · nothing was paid", refunded.line)
        assertEquals(true, refunded.amountMuted)
        assertEquals(listOf("Rung up", "Closed: customer not paying", "Refunded in Square"), refunded.facts.map { it.label })

        val afterRefund = page("paidAfterRefund")
        assertEquals("Paid after the refund", afterRefund.heading)
        assertEquals(true, afterRefund.headingWarn)
        assertEquals(listOf("Rung up", "Closed: customer not paying", "Refunded in Square", "Paid"), afterRefund.facts.map { it.label })

        val theirs = page("expiredTheirs")
        assertEquals(false, theirs.order, "what was bought stays on that till")
        assertEquals(emptyList(), theirs.actions)
        assertEquals(listOf(Fact("Till", "Back till"), Fact("Rung up", "3:48 PM"), Fact("Code expired", "3:58 PM")), theirs.facts)
        assertEquals(true, theirs.note!!.text.startsWith("Back till rang this sale up."))
        assertEquals(listOf("Till", "Rung up", "Closed here"), page("closedTheirs").facts.map { it.label })

        val cancelled = page("cancelled")
        assertEquals("The order was cancelled in Monokulo. Square has this sale as Monero: refund it if the customer isn't paying.", cancelled.note?.text)
        assertEquals("Cancelled", cancelled.facts.last().label)
    }

    @Test
    fun `the till brings up a new live sale, keeps a sale on screen, and steps back from one paid on it`() {
        val waiting = sale("w")
        assertEquals(Screen.Sale("w"), TillNav.follow(Screen.Till, listOf(waiting), emptySet()))
        assertEquals(Screen.Till, TillNav.follow(Screen.Till, listOf(waiting), setOf("w")), "the cashier left it")
        assertEquals(Screen.Sale("w2"), TillNav.follow(Screen.Till, listOf(sale("w2"), waiting), setOf("w")), "the newest they haven't left")
        assertEquals(Screen.Sale("waiting"), TillNav.follow(Screen.Till, shift.reversed(), emptySet()), "past the paid and ended ones")
        assertEquals(Screen.Till, TillNav.follow(Screen.Till, shift.drop(1), emptySet()), "closed and ended sales never come up on their own")
        assertEquals(Screen.Sale("paid"), TillNav.follow(Screen.Sale("paid"), listOf(waiting) + shift, emptySet()), "a sale on screen stays")
        assertEquals(Screen.Code("w"), TillNav.follow(Screen.Code("w"), listOf(waiting), emptySet()))
        assertEquals(Screen.Sale("paid"), TillNav.follow(Screen.Code("paid"), shift, emptySet()), "the enlarged code gives way to the page")
        assertEquals(Screen.Sale("expired"), TillNav.follow(Screen.Order("expired"), shift, emptySet()))
        assertEquals(Screen.Settings, TillNav.follow(Screen.Settings, listOf(waiting), emptySet()))
        val paid = waiting.copy(phase = SalePhase.Paid)
        assertEquals("w", TillNav.paidOnScreen(Screen.Sale("w"), listOf(paid), liveBefore = setOf("w")))
        assertNull(TillNav.paidOnScreen(Screen.Sale("w"), listOf(paid), liveBefore = emptySet()), "opened from the shift once paid: it stays")
        assertNull(TillNav.paidOnScreen(Screen.Till, listOf(paid), liveBefore = setOf("w")))
    }

    @Test
    fun `the order card lists four lines, else three and how many more`() {
        fun receipt(lines: Int) = Receipt(
            receipt = Receipt.Info("k4Rt", "2026-10-22T16:07:00-07:00"),
            merchant = Receipt.Merchant("The Bean Room", "US"),
            lineItems = List(lines) { Receipt.LineItem("Item $it", 1.0, 1.0, 1.0) },
            total = lines.toDouble(),
            currency = "USD",
        )
        assertEquals(4 to 0, SaleText.shownLines(receipt(4)).let { it.first.size to it.second })
        assertEquals(3 to 5, SaleText.shownLines(receipt(8)).let { it.first.size to it.second })
        assertNull(SaleText.tax(receipt(1)), "no tax lines: the last row is the total")
        assertEquals("1.00 USD", SaleText.total(receipt(1)))
        assertEquals("Square receipt k4Rt · 4:07 PM", SaleText.receiptLabel(receipt(1), withTime = true, Locale.US, zone))
        assertEquals("Square receipt k4Rt", SaleText.receiptLabel(receipt(1), withTime = false, Locale.US, zone))
        val taxed = receipt(2).copy(tax = Receipt.Tax(listOf(Receipt.TaxLine(0.05, 0.5), Receipt.TaxLine(0.08875, 0.63))))
        assertEquals("Tax 1.13", SaleText.tax(taxed))
        assertEquals("Scan with any Monero wallet · 14:52 left", SaleText.hint(sale("w"), 892))
        assertEquals("Ask the customer to pay the rest to the same code · 9:12 left", SaleText.hint(sale("w", SalePhase.Partial), 552))
        assertEquals("The customer can go: it's being confirmed", SaleText.hint(sale("w", SalePhase.Seen), 0))
    }
}
