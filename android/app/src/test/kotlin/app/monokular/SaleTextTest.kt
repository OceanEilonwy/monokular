package app.monokular

import app.monokular.core.SalePhase
import app.monokular.receipt.PaymentCode
import app.monokular.sales.FoundSale
import app.monokular.sales.TillSale
import app.monokular.ui.coreAlpha
import app.monokular.ui.glintAlpha
import kotlin.test.Test
import kotlin.test.assertEquals

class SaleTextTest {
    private fun sale(
        id: String = "o1",
        phase: SalePhase = SalePhase.Waiting,
        reference: String? = "#1042",
        found: Boolean = true,
        device: String? = null,
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
        assertEquals("Square · Front Counter", SaleText.place(sale(), "Front Counter"))
        assertEquals("From Front Counter iPad", SaleText.place(sale(found = false, device = "Front Counter iPad"), "Front Counter"))
        assertEquals("From another till", SaleText.place(sale(found = false), null))
    }

    @Test
    fun `the four steps follow the payment, and an unpaid end is none of them`() {
        assertEquals(
            listOf(1, 1, 2, 3, 4, 0, 0),
            listOf(SalePhase.Waiting, SalePhase.Partial, SalePhase.Seen, SalePhase.Confirming, SalePhase.Paid, SalePhase.Expired, SalePhase.DoubleSpend)
                .map(SaleText::step),
        )
        assertEquals("No payment yet", SaleText.status(sale(phase = SalePhase.Expired)))
        assertEquals("Refunded in Square", SaleText.status(sale(phase = SalePhase.Expired).copy(refunded = true)))
        assertEquals("29:05", SaleText.clock(29 * 60 + 5))
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
    fun `the bubble is lit while a sale waits, green just after one is paid, else faded`() {
        assertEquals(BubbleLook.Idle, Bubble.look(emptyList(), justPaid = false))
        assertEquals(BubbleLook.Lit, Bubble.look(listOf(sale(phase = SalePhase.Seen)), justPaid = false))
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
        assertEquals("gone", Bubble.focus(emptyList(), "gone", justPaid = false))
    }

    @Test
    fun `the till keeps its sale on screen through paid, and old paid sales never come back`() {
        val paid = sale("p", phase = SalePhase.Paid)
        val waiting = sale("w")
        val expired = sale("x", phase = SalePhase.Expired)
        assertEquals("p", TillFocus.pick(listOf(paid, waiting), emptySet(), focus = "p")?.orderId)
        assertEquals("w", TillFocus.pick(listOf(paid, waiting), setOf("p"), focus = "p")?.orderId)
        assertEquals("w", TillFocus.pick(listOf(paid, waiting), emptySet(), focus = null)?.orderId)
        assertEquals(null, TillFocus.pick(listOf(paid), emptySet(), focus = null))
        assertEquals("x", TillFocus.pick(listOf(paid, expired), emptySet(), focus = null)?.orderId)
        assertEquals(null, TillFocus.pick(listOf(expired.copy(refunded = true)), emptySet(), focus = null))
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
