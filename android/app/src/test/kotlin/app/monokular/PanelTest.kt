package app.monokular

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import app.monokular.core.SalePhase
import app.monokular.overlay.Handoff
import app.monokular.overlay.Life
import app.monokular.overlay.Panel
import app.monokular.overlay.WindowAct
import app.monokular.overlay.WindowEvent
import app.monokular.overlay.Windows
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

    @Test
    fun `the free bubble pulses only while a code waits for its money and the panel is down`() {
        assertTrue(Panel.pulses(BubbleLook.Lit, SalePhase.Waiting, panelUp = false))
        assertTrue(Panel.pulses(BubbleLook.Lit, SalePhase.Partial, panelUp = false), "part paid, the rest still to come")
        assertFalse(Panel.pulses(BubbleLook.Lit, SalePhase.Waiting, panelUp = true), "docked above the panel: nothing to call for")
        assertFalse(Panel.pulses(BubbleLook.Lit, SalePhase.Seen, panelUp = false), "the payment is on its way")
        assertFalse(Panel.pulses(BubbleLook.Lit, SalePhase.Confirming, panelUp = false))
        assertFalse(Panel.pulses(BubbleLook.Paid, SalePhase.Paid, panelUp = false))
        assertFalse(Panel.pulses(BubbleLook.Idle, SalePhase.Waiting, panelUp = false))
    }

    @Test
    fun `a fling's speed carries into the collapse as progress a second`() {
        assertEquals(-2f, Panel.collapseVelocity(velocity = 3000f, height = 1500f), "flung down, the panel's progress falls")
        assertEquals(0f, Panel.collapseVelocity(velocity = 3000f, height = 0f), "not measured yet: no speed to carry")
        assertEquals(0f, Panel.collapseVelocity(velocity = 0f, height = 1500f), "dragged and let go")
    }

    @Test
    fun `a phone's panel grows about its docked bubble, a tablet's about its corner nearest the bubble`() {
        val card = Rect(100f, 200f, 500f, 1000f)
        assertEquals(TransformOrigin(0.875f, -0.05f), Panel.origin(Offset(450f, 160f), card, corner = false), "above the card's end: the dock")
        assertEquals(TransformOrigin(1f, 0f), Panel.origin(Offset(1200f, 300f), card, corner = true), "bubble high on the right")
        assertEquals(TransformOrigin(0f, 1f), Panel.origin(Offset(20f, 900f), card, corner = true), "bubble low on the left")
        assertEquals(TransformOrigin(1f, 1f), Panel.origin(Offset(400f, 700f), card, corner = true), "behind the card: the nearest corner still")
        assertEquals(TransformOrigin.Center, Panel.origin(Offset(20f, 900f), Rect(0f, 0f, 0f, 0f), corner = true), "not placed yet")
    }

    private fun want(w: Windows, usable: Boolean, open: Boolean, wide: Boolean = false) = Handoff.next(w, WindowEvent.Want(usable, open, wide))

    @Test
    fun `on a phone the free bubble goes only once the panel has drawn the docked one, and comes back before the panel goes`() {
        var w = want(Windows(), usable = true, open = false).also { assertEquals(listOf(WindowAct.AddBubble), it.acts) }.windows
        assertEquals(Windows(usable = true, bubble = Life.Up), w)
        w = want(w, usable = true, open = true).also { assertEquals(listOf(WindowAct.AddPanel), it.acts, "the panel goes up at 0 over the bubble") }.windows
        assertEquals(Life.Up, w.bubble, "no frame with no bubble")
        assertEquals(w, want(w, usable = true, open = true).windows, "asked again, nothing changes")
        assertTrue(want(w, usable = true, open = true).acts.isEmpty())
        w = Handoff.next(w, WindowEvent.PanelDrawn).also {
            assertEquals(listOf(WindowAct.RemoveBubble, WindowAct.OpenPanel), it.acts, "then the free bubble goes and the panel grows")
        }.windows
        w = want(w, usable = true, open = false).also { assertEquals(listOf(WindowAct.ClosePanel), it.acts, "no free bubble yet") }.windows
        assertEquals(Life.Gone, w.bubble)
        w = Handoff.next(w, WindowEvent.PanelLanded).also { assertEquals(listOf(WindowAct.AddBubbleAtRest), it.acts) }.windows
        assertEquals(Life.Landed, w.panel, "the panel stays until the free bubble has drawn over its docked one")
        w = Handoff.next(w, WindowEvent.BubbleDrawn).also { assertEquals(listOf(WindowAct.RemovePanel), it.acts) }.windows
        assertEquals(Windows(usable = true, bubble = Life.Up), w)
    }

    @Test
    fun `a tap mid-collapse grows the panel again from where it is`() {
        var w = Windows(usable = true, bubble = Life.Gone, panel = Life.Up, panelDrawn = true)
        w = want(w, usable = true, open = false).windows
        w = want(w, usable = true, open = true).also { assertEquals(listOf(WindowAct.OpenPanel), it.acts) }.windows
        assertEquals(Life.Up, w.panel)
        assertTrue(Handoff.next(w, WindowEvent.PanelLanded).acts.isEmpty(), "the interrupted collapse's landing is nothing")
        // Reopened in the frame the free bubble came back: it goes again at once, the panel being drawn.
        val landed = Windows(usable = true, bubble = Life.Up, panel = Life.Landed, panelDrawn = true)
        assertEquals(listOf(WindowAct.OpenPanel, WindowAct.RemoveBubble), want(landed, usable = true, open = true).acts)
    }

    @Test
    fun `on a tablet the bubble stays beside the panel`() {
        var w = want(Windows(), usable = true, open = true, wide = true).also {
            assertEquals(listOf(WindowAct.AddPanel, WindowAct.AddBubble), it.acts)
        }.windows
        w = Handoff.next(w, WindowEvent.PanelDrawn).also { assertEquals(listOf(WindowAct.OpenPanel), it.acts) }.windows
        w = want(w, usable = true, open = false, wide = true).also { assertEquals(listOf(WindowAct.ClosePanel), it.acts) }.windows
        w = Handoff.next(w, WindowEvent.PanelLanded).also { assertEquals(listOf(WindowAct.RemovePanel), it.acts) }.windows
        assertEquals(Windows(usable = true, wide = true, bubble = Life.Up), w)
    }

    @Test
    fun `a panel of the other kind is swapped without motion`() {
        val phone = Windows(usable = true, panel = Life.Up, panelDrawn = true)
        val step = want(phone, usable = true, open = true, wide = true)
        assertEquals(listOf(WindowAct.RemovePanel, WindowAct.AddPanelOpen, WindowAct.AddBubble), step.acts)
    }

    @Test
    fun `Monokular coming to the front collapses the panel and the bubble goes with it`() {
        var w = Windows(usable = true, panel = Life.Up, panelDrawn = true)
        w = want(w, usable = false, open = false).also { assertEquals(listOf(WindowAct.ClosePanel), it.acts) }.windows
        w = Handoff.next(w, WindowEvent.PanelLanded).also { assertEquals(listOf(WindowAct.RemovePanel), it.acts, "no free bubble over the app") }.windows
        assertEquals(Windows(), w)
        var free = Windows(usable = true, bubble = Life.Up)
        free = want(free, usable = false, open = false).also { assertEquals(listOf(WindowAct.HideBubble), it.acts) }.windows
        assertEquals(listOf(WindowAct.ShowBubble), want(free, usable = true, open = false).acts, "back before it went: it grows again")
        assertEquals(listOf(WindowAct.RemoveBubble), Handoff.next(free, WindowEvent.BubbleLeft).acts)
    }
}
