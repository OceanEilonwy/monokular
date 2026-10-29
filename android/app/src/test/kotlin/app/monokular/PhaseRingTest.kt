package app.monokular

import app.monokular.core.SalePhase
import app.monokular.ui.ringFill
import kotlin.test.Test
import kotlin.test.assertEquals

class PhaseRingTest {
    @Test
    fun `the ring fills a quarter a step and is empty once a sale ended unpaid`() {
        assertEquals(0.25f, ringFill(SalePhase.Waiting))
        assertEquals(0.25f, ringFill(SalePhase.Partial), "part paid is still waiting for the rest")
        assertEquals(0.5f, ringFill(SalePhase.Seen))
        assertEquals(0.75f, ringFill(SalePhase.Confirming))
        assertEquals(1f, ringFill(SalePhase.Paid), "paid closes the ring")
        for (ended in listOf(SalePhase.Expired, SalePhase.Cancelled, SalePhase.DoubleSpend)) assertEquals(0f, ringFill(ended))
    }
}
