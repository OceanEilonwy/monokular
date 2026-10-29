package app.monokular

import app.monokular.core.PointOfSale
import kotlin.test.Test
import kotlin.test.assertEquals

/** The words that name the point of sale (acceptance D5, T2.6, T3.2, T4.1; decision 136). */
class PosTextTest {
    private val square = PointOfSale.Square
    private val clover = PointOfSale.Clover

    @Test
    fun `step 4 and Settings name the point of sale chosen`() {
        assertEquals("Ring up in Square or Clover as usual and choose Monero as the payment.", PosText.WELCOME_FEATURE)
        assertEquals("Square Point of Sale on a phone, tablet or Register", PosText.choiceDetail(square))
        assertEquals("A Clover device", PosText.choiceDetail(clover))
        assertEquals("Connect Clover", PosText.connect(clover))
        assertEquals("Monokular checks Clover for each Monero payment you take, so it can show the code straight away.", PosText.lead(clover))
        assertEquals(
            "Monokular can read payments and orders, and add a Monero payment type. Your Clover sign-in stays on this device: " +
                "Monokulo never sees it, or what was bought.",
            PosText.permissions(clover),
        )
        assertEquals(
            "Monokular can read payments, orders and locations, and refund a Monero sale that's never paid. Your Square sign-in stays " +
                "on this device: Monokulo never sees it, or what was bought.",
            PosText.permissions(square),
        )
        assertEquals("In Clover Register", PosText.atTheTill(clover))
        assertEquals("In Square Point of Sale", PosText.atTheTill(square))
        assertEquals(listOf("Sign in to Square", "Sign in to Clover"), PointOfSale.entries.map(PosText::signIn))
        assertEquals(listOf("Sign out of Square", "Sign out of Clover"), PointOfSale.entries.map(PosText::signOut))
        assertEquals(listOf("Location", "Merchant"), PointOfSale.entries.map(PosText::placeRow))
        assertEquals(listOf("Stand-in Square address", "Stand-in Clover address"), PointOfSale.entries.map(PosText::standIn))
        assertEquals("Sales paid with this payment type at this merchant show up in Monokular.", PosText.signedInFootnote(clover))
        assertEquals("Without Clover, Monokular shows the sales other tills make, without receipts.", PosText.withoutFootnote(clover))
        assertEquals("Without a point of sale, Monokular shows the sales other tills make, without receipts.", PosText.withoutFootnote(null))
    }

    @Test
    fun `the bubble floats over Square's app, and over other apps beside a Clover device`() {
        assertEquals("Float over Square", PosText.floatHeading(square))
        assertEquals("Float over other apps", PosText.floatHeading(clover))
        assertEquals(
            "Start Monokular and ring up on your Clover device. Monokular waits in a faded bubble over other apps and lights up when a sale comes in.",
            PosText.readyLead(clover, bubble = true),
        )
        assertEquals(
            "Start Monokular and switch to Square. Monokular waits in its notification and alerts you when a sale comes in.",
            PosText.readyLead(square, bubble = false),
        )
    }

    @Test
    fun `the refund sheet says what to refund, where, and what the till saw`() {
        assertEquals("Refund in Clover", PosText.refundSheetTitle("Clover"))
        assertEquals(
            "This sale wasn't paid in Monero. Refund it in Clover's Orders app: order 7F3KQ2MA, 4:07 PM, $13.88, paid with Monero. " +
                "Monokular will notice when it's done.",
            PosText.refundSheetBody("Clover's Orders app", "7F3KQ2MA", "4:07 PM", "$13.88"),
        )
        assertEquals(
            "This sale wasn't paid in Monero. Refund it in Clover's Orders app: $13.88, paid with Monero. Monokular will notice when it's done.",
            PosText.refundSheetBody("Clover's Orders app", null, null, "$13.88"),
        )
        assertEquals(
            "Clover doesn't show a refund yet. Finish it in Clover's Orders app, then tap again.",
            PosText.refundNotSeen("Clover", "Clover's Orders app"),
        )
    }

    @Test
    fun `a receipt is Square's receipt or Clover's order`() {
        assertEquals("Square receipt bP9m", PosText.receiptLabel(square, "bP9m"))
        assertEquals("Clover order 7F3KQ2MA", PosText.receiptLabel(clover, "7F3KQ2MA"))
        assertEquals("Receipt bP9m", PosText.receiptLabel(null, "bP9m"))
        assertEquals("Back to Square", PosText.backTo(square))
    }
}
