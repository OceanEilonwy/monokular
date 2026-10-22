package app.monokular.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaleEventTest {
    @Test
    fun `an event from the stream decodes, ignoring fields it doesn't know`() {
        val event = SaleEvent.decode(
            """{"event":"sale.paid","order_id":"ord_1","amount":"24.50","currency":"USD",
               "address":"8Bx","xmr_amount_piconero":141900000000,"merchant_order_id":"#1042",
               "expires_at":1761312000,"device_name":"Front counter","at":1761310260,"later":true}""",
        )
        assertEquals(SalePhase.Paid, event.phase)
        assertEquals("#1042", event.merchantOrderId)
        assertTrue(event.phase!!.isFinal)
    }

    @Test
    fun `every event name has its phase`() {
        mapOf(
            "sale.waiting" to SalePhase.Waiting,
            "sale.seen" to SalePhase.Seen,
            "sale.confirming" to SalePhase.Confirming,
            "sale.paid" to SalePhase.Paid,
            "sale.partial" to SalePhase.Partial,
            "sale.expired" to SalePhase.Expired,
            "sale.double_spend" to SalePhase.DoubleSpend,
        ).forEach { (name, phase) -> assertEquals(phase, SalePhase.ofEvent(name)) }
        assertNull(SalePhase.ofEvent("ready"))
    }

    @Test
    fun `a status puts a sale in its phase`() {
        mapOf(
            "pending" to SalePhase.Waiting,
            "unconfirmed" to SalePhase.Seen,
            "confirming" to SalePhase.Confirming,
            "overpaid" to SalePhase.Paid,
            "partial" to SalePhase.Partial,
            "cancelled" to SalePhase.Expired,
        ).forEach { (status, phase) -> assertEquals(phase, OrderStatus(status).phase, status) }
    }

    @Test
    fun `payment uris carry the amount in XMR without trailing zeros`() {
        assertEquals("0.080370584829", PaymentUri.xmr(80_370_584_829))
        assertEquals("2", PaymentUri.xmr(2_000_000_000_000))
        assertEquals("0.5", PaymentUri.xmr(500_000_000_000))
        assertEquals("monero:8Bx?tx_amount=0.5", PaymentUri.build("8Bx", 500_000_000_000))
    }
}
