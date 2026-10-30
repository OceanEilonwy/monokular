package app.monokular

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.monokular.OneClockTest.Companion.noActions
import app.monokular.ui.SaleScreen
import org.junit.Rule
import org.junit.Test

/**
 * The sale's order card is folded to its one row unless the page has room
 * for it open: on a phone it is folded from its first frame, on a tablet
 * it opens, and a tap folds or unfolds it whatever the room.
 */
class ReceiptFoldTest {
    @get:Rule
    val compose = createComposeRule()

    private val sale = testSale("a1", expiresAt = 1_600).copy(receipt = sampleReceipt())

    /** The card's row: whose receipt, how many items, the total. */
    private val row get() = compose.onNodeWithText("k4Rt · 15 items · 79.21 USD", substring = true)

    /** One of its lines, shown only open. */
    private val line get() = compose.onNodeWithText("2 × Flat white")

    private fun sale(size: DpSize) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                Till { SaleScreen(sale, "Front", refunding = false, refundProblem = null, refundSheet = null, actions = noActions) }
            }
        }
    }

    private fun state(words: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, words)

    @Test
    fun onAPhoneTheCardIsFoldedFromItsFirstFrameAndATapOpensIt() {
        sale(DpSize(412.dp, 760.dp))
        compose.mainClock.advanceTimeByFrame()
        row.assert(state("Collapsed"))
        line.assertIsNotDisplayed()
        compose.mainClock.advanceTimeBy(1_000)
        // Under the code, past the window's foot: no room for it open.
        row.assert(state("Collapsed"))
        line.assertIsNotDisplayed()

        compose.mainClock.autoAdvance = true
        row.performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        row.assert(state("Expanded"))
        line.performScrollTo().assertIsDisplayed()
        row.performScrollTo().performClick()
        compose.waitForIdle()
        row.assert(state("Collapsed"))
        line.assertIsNotDisplayed()
    }

    @Test
    fun onATabletInPortraitTheCardIsOpen() = opensWithRoom(DpSize(800.dp, 1280.dp))

    @Test
    fun onATabletInLandscapeTheCardIsOpen() = opensWithRoom(DpSize(1280.dp, 800.dp))

    private fun opensWithRoom(size: DpSize) {
        sale(size)
        compose.mainClock.advanceTimeBy(1_000)
        row.assertIsDisplayed().assert(state("Expanded"))
        line.assertIsDisplayed()

        // The cashier's tap wins over the room.
        compose.mainClock.autoAdvance = true
        row.performClick()
        compose.waitForIdle()
        row.assert(state("Collapsed"))
        line.assertIsNotDisplayed()
    }
}
