package app.monokular

import app.monokular.core.SalePhase
import app.monokular.receipt.PaymentCode
import app.monokular.receipt.Receipt
import app.monokular.sales.FoundSale
import app.monokular.sales.TillSale
import app.monokular.ui.coreAlpha
import app.monokular.ui.glintAlpha
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class SaleTextTest {
    private fun sale(
        id: String = "o1",
        phase: SalePhase = SalePhase.Waiting,
        reference: String? = "#1042",
        found: Boolean = true,
        device: String? = null,
        closedAt: Long? = null,
        refundedAt: Long? = null,
    ) = TillSale(
        orderId = id,
        amount = "24.50",
        currency = "USD",
        reference = reference,
        code = PaymentCode("monero:4abc?tx_amount=0.1421", hasReceipt = true),
        phase = phase,
        expiresAt = 0,
        xmr = "0.1421",
        address = "4abc",
        found = if (found) FoundSale("p1", 2450, "USD", reference, "ord", "2026-10-23T10:00:00Z", null) else null,
        deviceName = device,
        closedAt = closedAt,
        refundedAt = refundedAt,
    )

    private val receipt = Receipt(
        receipt = Receipt.Info("k4Rt", "2026-10-22T16:07:00-07:00"),
        merchant = Receipt.Merchant("The Bean Room", "US"),
        lineItems = listOf(
            Receipt.LineItem("Flat white", 2.0, 4.5, 9.0),
            Receipt.LineItem("Oat latte", 1.0, 5.25, 5.25),
            Receipt.LineItem("Almond croissant", 3.0, 3.75, 11.25),
            Receipt.LineItem("Banana bread", 1.0, 4.5, 4.5),
            Receipt.LineItem("House blend beans, 250 g", 1.5, 12.0, 18.0),
        ),
        tax = Receipt.Tax(listOf(Receipt.TaxLine(0.08875, 4.26))),
        total = 52.26,
        currency = "USD",
    )

    @Test
    fun `a sale is named by the POS's receipt number, else as a Monero sale`() {
        assertEquals("Sale #1042", SaleText.title(sale(reference = "#1042")))
        assertEquals("Sale #bP9m", SaleText.title(sale(reference = "bP9m")))
        assertEquals("Monero sale", SaleText.title(sale(reference = null)))
        assertEquals("Monero sale", SaleText.title(sale(reference = " ")))
    }

    @Test
    fun `a sale says where it came from`() {
        assertEquals("Front Counter · this till", SaleText.place(sale(), "Front Counter"))
        assertEquals("This till", SaleText.place(sale(), null))
        assertEquals("From Front Counter iPad", SaleText.place(sale(found = false, device = "Front Counter iPad"), "Front Counter"))
        assertEquals("From another till", SaleText.place(sale(found = false), null))
    }

    @Test
    fun `the four steps follow the payment, and an unpaid end is none of them`() {
        assertEquals(
            listOf(1, 1, 2, 3, 4, 0, 0, 0),
            listOf(SalePhase.Waiting, SalePhase.Partial, SalePhase.Seen, SalePhase.Confirming, SalePhase.Paid, SalePhase.Expired, SalePhase.Cancelled, SalePhase.DoubleSpend)
                .map(SaleText::step),
        )
        assertEquals("29:05", SaleText.clock(29 * 60 + 5))
    }

    @Test
    fun `a sale's status follows where it stands on this till`() {
        assertEquals("Waiting for payment", SaleText.status(sale()))
        assertEquals("Seen on the network", SaleText.status(sale(phase = SalePhase.Seen)))
        assertEquals("Part paid", SaleText.status(sale(phase = SalePhase.Partial)))
        assertEquals("Paid", SaleText.status(sale(phase = SalePhase.Paid)))
        assertEquals("Paid after you closed it", SaleText.status(sale(phase = SalePhase.Paid, closedAt = 1)))
        assertEquals("Paid after the code expired", SaleText.status(sale(phase = SalePhase.Paid).copy(endedAt = 1)))
        assertEquals("Paid after you closed it", SaleText.status(sale(phase = SalePhase.Paid, closedAt = 1).copy(endedAt = 2)), "closed, then the code ran out, then paid")
        assertEquals("Paid after the refund", SaleText.status(sale(phase = SalePhase.Paid, closedAt = 1, refundedAt = 2)), "a late payment after the Square refund")
        assertEquals("Closed · not paid", SaleText.status(sale(closedAt = 1)))
        assertEquals("Code expired · not paid", SaleText.status(sale(phase = SalePhase.Expired)))
        assertEquals("Code expired · not paid", SaleText.status(sale(phase = SalePhase.Expired, closedAt = 1)), "the code running out outranks the cashier's close")
        assertEquals("Cancelled · not paid", SaleText.status(sale(phase = SalePhase.Cancelled)))
        assertEquals("Payment reversed", SaleText.status(sale(phase = SalePhase.DoubleSpend)))
        assertEquals("Refunded in Square", SaleText.status(sale(phase = SalePhase.Expired, refundedAt = 2)))
        assertEquals("Refunded in Square", SaleText.status(sale(phase = SalePhase.Cancelled, closedAt = 1, refundedAt = 2)))
        assertEquals("Code expired · not paid", SaleText.status(sale(phase = SalePhase.Expired, found = false, device = "Back till")), "what this till knows; the row adds the till")
        assertEquals("Payment reversed", SaleText.status(sale(phase = SalePhase.DoubleSpend, found = false)))
        assertEquals("Closed · not paid", SaleText.status(sale(found = false, closedAt = 1)))
        assertEquals("Paid", SaleText.status(sale(phase = SalePhase.Paid, found = false, device = "Back till")), "another till's paid sale is just paid")
    }

    @Test
    fun `what was bought is counted and listed from the on-device receipt`() {
        assertEquals("9 items", SaleText.items(receipt), "quantities add up, by weight rounded")
        assertEquals("1 item", SaleText.items(receipt.copy(lineItems = receipt.lineItems.take(1).map { it.copy(quantity = 1.0) })))
        assertEquals("2 × Flat white, Oat latte, 3 × Almond croissant, +2 more", SaleText.firstItems(receipt))
        assertEquals("2 × Flat white, Oat latte", SaleText.firstItems(receipt.copy(lineItems = receipt.lineItems.take(2))), "no '+0 more'")
        assertEquals("1.5 × House blend beans, 250 g", SaleText.line(receipt.lineItems.last()))
        assertEquals("9.00", SaleText.plain(9.0, "USD"))
        assertEquals("1125", SaleText.plain(1125.0, "JPY"))
        val at = Instant.parse("2026-10-22T16:07:00Z").epochSecond
        assertEquals("9:07 AM", SaleText.time(at, Locale.US, ZoneId.of("America/Los_Angeles")), "the locale's own form, with a plain space")
        assertEquals("16:07", SaleText.time(at, Locale.UK, ZoneId.of("UTC")))
    }

    @Test
    fun `phase changes report new sales and new phases, but not what was there at the start`() {
        val changes = PhaseChanges()
        assertEquals(emptyList(), changes.next(listOf(sale("old"))))
        assertEquals(emptyList(), changes.next(listOf(sale("old"))))
        val fresh = sale("new")
        assertEquals(listOf(fresh), changes.next(listOf(fresh, sale("old"))))
        val paid = fresh.copy(phase = SalePhase.Paid)
        assertEquals(listOf(paid), changes.next(listOf(paid, sale("old"))))
        assertEquals(emptyList(), changes.next(listOf(paid, sale("old"))))
    }

    @Test
    fun `the bubble is lit while a sale's code is up, green just after one is paid, else faded`() {
        assertEquals(BubbleLook.Idle, Bubble.look(emptyList(), justPaid = false))
        assertEquals(BubbleLook.Lit, Bubble.look(listOf(sale(phase = SalePhase.Seen)), justPaid = false))
        assertEquals(BubbleLook.Idle, Bubble.look(listOf(sale(closedAt = 1)), justPaid = false), "a closed sale's code is down")
        assertEquals(BubbleLook.Paid, Bubble.look(listOf(sale(phase = SalePhase.Seen)), justPaid = true), "paid shows first, even with another sale waiting")
        assertEquals(BubbleLook.Paid, Bubble.look(listOf(sale(phase = SalePhase.Paid)), justPaid = true))
        assertEquals(BubbleLook.Idle, Bubble.look(listOf(sale(phase = SalePhase.Paid)), justPaid = false))
        assertEquals("Monokular, new sale, tap to show the code", Bubble.label(BubbleLook.Lit))
    }

    @Test
    fun `the panel stays on a sale while it shows as paid, then moves to the next one waiting`() {
        val waiting = sale("older")
        val paid = sale("newer", phase = SalePhase.Paid)
        assertEquals("newer", Bubble.focus(listOf(paid, waiting), "newer", justPaid = true))
        assertEquals("older", Bubble.focus(listOf(paid, waiting), "newer", justPaid = false))
        assertEquals("newer", Bubble.focus(listOf(sale("newer"), waiting), "older", justPaid = false), "a newer sale takes over")
        assertEquals("older", Bubble.focus(listOf(sale("newer", closedAt = 1), waiting), "older", justPaid = false), "not a closed one")
        assertEquals("gone", Bubble.focus(emptyList(), "gone", justPaid = false))
    }

    @Test
    fun `the till keeps its sale on screen through paid, and closed or ended sales never come up on their own`() {
        val paid = sale("p", phase = SalePhase.Paid)
        val waiting = sale("w")
        val expired = sale("x", phase = SalePhase.Expired)
        val closed = sale("c", closedAt = 1)
        assertEquals("p", TillFocus.pick(listOf(paid, waiting), emptySet(), focus = "p")?.orderId)
        assertEquals("w", TillFocus.pick(listOf(paid, waiting), setOf("p"), focus = "p")?.orderId)
        assertEquals("w", TillFocus.pick(listOf(paid, waiting), emptySet(), focus = null)?.orderId)
        assertEquals(null, TillFocus.pick(listOf(paid), emptySet(), focus = null))
        assertEquals(null, TillFocus.pick(listOf(paid, expired, closed), emptySet(), focus = null), "they wait in the shift, under Needs action")
        assertEquals("x", TillFocus.pick(listOf(paid, expired), emptySet(), focus = "x")?.orderId, "unless the cashier opened it")
        assertEquals(null, TillFocus.pick(listOf(waiting), setOf("w"), focus = null), "a sale the cashier left stays down")
    }

    @Test
    fun `the loading mark's glints follow the web's keyframes`() {
        assertEquals(0f, glintAlpha(0f))
        assertEquals(0.95f, glintAlpha(0.05f))
        assertEquals(0f, glintAlpha(0.5f))
        assertEquals(0f, coreAlpha(0.2f))
        assertEquals(0.95f, coreAlpha(0.6f))
        assertEquals(0f, coreAlpha(0.99f))
    }
}
