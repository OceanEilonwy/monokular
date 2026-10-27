package app.monokular

import app.monokular.core.SalePhase
import app.monokular.overlay.Panel
import app.monokular.receipt.PaymentCode
import app.monokular.sales.FoundSale
import app.monokular.sales.TillSale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelTest {
    private fun sale(
        phase: SalePhase = SalePhase.Waiting,
        found: Boolean = true,
        device: String? = "Back till",
        refundedAt: Long? = null,
        id: String = "o1",
    ) = TillSale(
        orderId = id,
        amount = "79.21",
        currency = "USD",
        reference = "k4Rt",
        code = PaymentCode("monero:4abc", hasReceipt = false),
        phase = phase,
        expiresAt = 0,
        xmr = "0.31684",
        address = "4abc",
        found = if (found) FoundSale("p1", 7921, "USD", "k4Rt", "ord", "2026-10-22T23:07:00Z", null) else null,
        deviceName = if (found) null else device,
        refundedAt = refundedAt,
    )

    @Test
    fun `the grabber lowers the panel past a quarter of its height, or on a fling`() {
        val fling = 2000f
        assertFalse(Panel.lowers(drag = 100f, height = 1000f, velocity = 0f, fling), "a short drag springs back")
        assertFalse(Panel.lowers(drag = 250f, height = 1000f, velocity = 0f, fling), "a quarter exactly isn't past it")
        assertTrue(Panel.lowers(drag = 251f, height = 1000f, velocity = 0f, fling))
        assertTrue(Panel.lowers(drag = 20f, height = 1000f, velocity = 2000f, fling), "a quick flick down lowers it")
        assertFalse(Panel.lowers(drag = 20f, height = 1000f, velocity = -3000f, fling), "flung up, it stays")
        assertFalse(Panel.lowers(drag = 20f, height = 0f, velocity = 0f, fling), "not measured yet")
    }

    @Test
    fun `the panel floats only on a screen whose smallest width is a tablet's`() {
        assertFalse(Panel.wide(411), "a phone, upright or on its side: its smallest width doesn't change")
        assertFalse(Panel.wide(599))
        assertTrue(Panel.wide(600), "a small tablet")
        assertTrue(Panel.wide(840))
    }

    @Test
    fun `the header says where the sale is from`() {
        assertEquals("Front Counter", Panel.where(sale(), "Front Counter"))
        assertEquals("This till", Panel.where(sale(), null))
        assertEquals("From Back till", Panel.where(sale(found = false), "Front Counter"))
        assertEquals("From another till", Panel.where(sale(found = false, device = null), "Front Counter"))
    }

    @Test
    fun `a sale that ends while the panel is up sends the cashier to refund it`() {
        assertEquals("Code expired · not paid", Panel.endedHeading(sale(SalePhase.Expired)))
        assertEquals("Cancelled · not paid", Panel.endedHeading(sale(SalePhase.Cancelled)))
        assertEquals("Payment reversed · don't hand it over", Panel.endedHeading(sale(SalePhase.DoubleSpend)))
        assertEquals("Payment reversed · don't hand it over", Panel.endedHeading(sale(SalePhase.DoubleSpend, found = false)))
        assertEquals("Open the shift to refund it in Square.", Panel.endedLine(sale(SalePhase.Expired)))
        assertEquals("Refund it in Square on Back till.", Panel.endedLine(sale(SalePhase.Expired, found = false)))
        assertEquals("Refund it in Square on the till that took it.", Panel.endedLine(sale(SalePhase.Expired, found = false, device = null)))
        assertEquals("Refunded in Square", Panel.endedHeading(sale(SalePhase.DoubleSpend, refundedAt = 9)))
        assertEquals("Nothing more to do.", Panel.endedLine(sale(SalePhase.Expired, refundedAt = 9)))
    }

    @Test
    fun `the sale on the panel shows paid before the next one waiting comes up`() {
        val waiting = listOf(sale(id = "1044"), sale(id = "1043"))
        assertEquals("1044", Bubble.focus(waiting, null, justPaid = false), "the newest is up")
        val paid = listOf(sale(SalePhase.Paid, id = "1044"), sale(id = "1043"))
        assertTrue(Panel.landed(paid, "1044", wasLive = true), "it was live a moment ago, now paid")
        assertEquals("1044", Bubble.focus(paid, "1044", justPaid = true), "held, paid, for a few seconds")
        assertEquals("1043", Bubble.focus(paid, "1044", justPaid = false), "then the next one waiting")
        assertFalse(Panel.landed(paid, "1044", wasLive = false), "already paid at the last look: not news")
        assertFalse(Panel.landed(paid, "1043", wasLive = true), "another sale, still waiting")
    }
}
