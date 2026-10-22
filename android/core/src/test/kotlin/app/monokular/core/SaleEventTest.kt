package app.monokular.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SaleEventTest {
    @Test
    fun `a waiting sale decodes, ignoring fields it doesn't know`() {
        val event = SaleEvent.decode(
            """
            {"api_version":1,"event_id":"evt_4be2d07a","phase":"waiting","sale_id":"sl_1042",
             "ref":"#1042","amount":"$24.50","xmr":"0.1421","expires_at":1761120000,
             "payment_uri":"monero:8Bx?tx_amount=0.1421","location":"Front Counter"}
            """.trimIndent(),
        )
        assertEquals(SalePhase.Waiting, event.phase)
        assertEquals("#1042", event.ref)
        assertEquals(1_761_120_000L, event.expiresAt)
    }

    @Test
    fun `every phase name matches the server's`() {
        val names = SalePhase.entries.map { phase ->
            SaleEvent.decode(
                """{"api_version":1,"event_id":"e","phase":"${wire(phase)}","sale_id":"s","ref":"r",
                    "amount":"a","xmr":"x","expires_at":0,"payment_uri":"monero:x"}""",
            ).phase
        }
        assertEquals(SalePhase.entries, names)
    }

    private fun wire(phase: SalePhase) = when (phase) {
        SalePhase.Waiting -> "waiting"
        SalePhase.Seen -> "seen"
        SalePhase.Confirming -> "confirming"
        SalePhase.Paid -> "paid"
        SalePhase.Partial -> "partial"
        SalePhase.Expired -> "expired"
        SalePhase.DoubleSpend -> "double_spend"
    }
}
