package app.monokular

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.monokular.core.SalePhase
import app.monokular.overlay.CODE_TAG
import app.monokular.overlay.PanelActions
import app.monokular.overlay.Presence
import app.monokular.overlay.SalePanel
import app.monokular.receipt.PaymentCode
import app.monokular.sales.FoundSale
import app.monokular.sales.TillSale
import app.monokular.ui.LocalMotion
import app.monokular.ui.MonokularTheme
import app.monokular.ui.Motion
import org.junit.Rule
import org.junit.Test

/** The panel's code folding into its paid tick and growing back out of it (M5): the code itself never changes size. */
class PanelFaceOnDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private fun sale(id: String, phase: SalePhase) = TillSale(
        orderId = id,
        amount = "79.21",
        currency = "USD",
        reference = "k4Rt",
        code = PaymentCode("monero:4abc?tx_amount=0.31684&id=$id", hasReceipt = false),
        phase = phase,
        expiresAt = System.currentTimeMillis() / 1000 + 600,
        xmr = "0.31684",
        address = "4abc",
        found = FoundSale("p1", 7921, "USD", "k4Rt", "ord", "2026-10-22T23:07:00Z", null),
    )

    private var shown by mutableStateOf(sale("1", SalePhase.Waiting))

    private val actions = PanelActions({}, {}, {}, {}, {}, {}, {}, {})

    private fun panel(wide: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MonokularTheme {
                CompositionLocalProvider(LocalMotion provides Motion.Full) {
                    SalePanel(shown, "Front Counter", wide, actions, Presence(1f))
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun assertCodeAt(side: Int) {
        compose.onNodeWithTag(CODE_TAG, useUnmergedTree = true).assertWidthIsEqualTo(side.dp).assertHeightIsEqualTo(side.dp)
    }

    @Test
    fun theCodeIsItsDesignedSizeAtRestAndOnEveryFrameOfTheFold() {
        panel(wide = false)
        assertCodeAt(264)
        shown = sale("1", SalePhase.Paid)
        repeat(30) {
            compose.mainClock.advanceTimeBy(20)
            assertCodeAt(264)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Paid").assertExists()
        compose.onNodeWithText("Done").assertExists()
        shown = sale("2", SalePhase.Waiting)
        repeat(30) {
            compose.mainClock.advanceTimeBy(20)
            assertCodeAt(264)
        }
        compose.mainClock.advanceTimeBy(1_000)
        assertCodeAt(264)
        compose.onNodeWithText("Full screen").assertExists()
    }

    @Test
    fun onATabletTheCodeIs280() {
        panel(wide = true)
        assertCodeAt(280)
    }
}
