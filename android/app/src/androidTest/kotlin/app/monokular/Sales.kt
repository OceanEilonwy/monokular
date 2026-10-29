package app.monokular

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.monokular.core.SalePhase
import app.monokular.receipt.PaymentCode
import app.monokular.sales.FoundSale
import app.monokular.sales.TillSale
import app.monokular.ui.LocalMotion
import app.monokular.ui.MonokularTheme
import app.monokular.ui.Motion

/** A sale of this till's for the screens under test, live until [expiresAt] unless [phase] says otherwise. */
fun testSale(
    id: String,
    phase: SalePhase = SalePhase.Waiting,
    expiresAt: Long = Long.MAX_VALUE / 2,
    paidAt: Long? = null,
) = TillSale(
    orderId = id,
    amount = "8.40",
    currency = "USD",
    reference = id,
    code = PaymentCode("monero:4abc?tx_amount=0.0336&tx_description=$id", hasReceipt = false),
    phase = phase,
    expiresAt = expiresAt,
    xmr = "0.0336",
    address = "4abc",
    found = FoundSale("p_$id", 840, "USD", id, "o", "2026-10-22T22:50:00Z", null),
    createdAt = 1_761_000_000,
    paidAt = paidAt,
)

/**
 * [content] in the till's theme with [motion] whatever the emulator's
 * animator scale is, so a timing test runs the presets it names.
 */
@Composable
fun Till(motion: Motion = Motion.Full, content: @Composable () -> Unit) = MonokularTheme {
    CompositionLocalProvider(LocalMotion provides motion, content = content)
}
