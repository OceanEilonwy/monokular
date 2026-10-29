package app.monokular

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.launch
import app.monokular.OneClockTest.Companion.noActions
import app.monokular.OneClockTest.Companion.status
import app.monokular.ui.SaleScreen
import app.monokular.ui.ShiftScreen
import androidx.compose.runtime.mutableLongStateOf
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The back gesture over the stage (decision 112), driven the way Android
 * drives it, through the activity's back dispatcher: the finger's progress
 * is how far every part has moved, and where it goes is read as it starts.
 */
class StageBackTest {
    @get:Rule
    val compose = createComposeRule()

    private val sale = testSale("a1", expiresAt = 1_600)
    private val sales = listOf(testSale("z0", expiresAt = 1_600), testSale("y0", expiresAt = 1_600), sale, testSale("b2", expiresAt = 1_600))
    private val now = mutableLongStateOf(1_000)
    private lateinit var dispatcher: OnBackPressedDispatcher
    private lateinit var scope: kotlinx.coroutines.CoroutineScope
    private val landed = mutableListOf<Screen>()

    /** Where back goes; a plain var, as the view model's flows are not snapshot state. */
    private var target: Screen? = Screen.Till

    private fun stage(state: SeekableTransitionState<Screen>) = compose.setContent {
        dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        scope = androidx.compose.runtime.rememberCoroutineScope()
        Till {
            val scrub = androidx.compose.runtime.remember { BackScrub() }
            StageBack(true, state, scrub, target = { target }, onBack = {}, onLanded = { landed += it })
            TillStage(state, cameUpByItself = { false }, scrub) { s ->
                when (s) {
                    is Screen.Sale -> SaleScreen(sale, "Front", refunding = false, refundProblem = null, actions = noActions, now = now)
                    else -> ShiftScreen(status, sales, noActions, now)
                }
            }
        }
    }

    private fun gesture(progress: Float) {
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(BackEventCompat(10f, 1000f, progress, BackEventCompat.EDGE_LEFT)) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun backButton(): Rect =
        compose.onNodeWithContentDescription("Back to the shift", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun theSaleIsAsFarTowardItsRowAsTheFingerIsAcross() {
        val state = SeekableTransitionState<Screen>(Screen.Sale("a1"))
        stage(state)
        compose.waitForIdle()
        val rest = backButton()
        compose.mainClock.autoAdvance = false

        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 1000f, 0f, BackEventCompat.EDGE_LEFT)) }
        gesture(0.05f)
        val at = listOf(0.25f, 0.5f, 0.75f).map { gesture(it); backButton() }
        // The sale's surface is laid out once and scaled as a layer: a point on it moves as its bounds do.
        fun step(a: Rect, b: Rect) = listOf(b.top - a.top, b.left - a.left, b.width - a.width)
        val first = step(rest, at[0])
        val second = step(at[0], at[1])
        val third = step(at[1], at[2])
        for (i in first.indices) {
            // Within two pixels: each point is rounded to a pixel where it's placed.
            assertTrue(abs(second[i] - third[i]) <= 2f, "0.25 to 0.5 moves as far as 0.5 to 0.75: $second vs $third")
            assertTrue(abs(first[i] - second[i]) <= 2f, "and the first quarter as far as the second: $first vs $second")
        }
        assertTrue(second[0] > 10f, "and it does move: $second")

        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(listOf<Screen>(Screen.Sale("a1")), landed, "released, it lands on the shift and then goes back")
        assertEquals(Screen.Till, state.currentState)
    }

    @Test
    fun cancelledTheSaleSpringsBackToWhereItWas() {
        val state = SeekableTransitionState<Screen>(Screen.Sale("a1"))
        stage(state)
        compose.waitForIdle()
        val rest = backButton()
        compose.mainClock.autoAdvance = false

        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 1000f, 0f, BackEventCompat.EDGE_LEFT)) }
        gesture(0.4f)
        assertTrue(backButton().top > rest.top + 10f, "the preview moved it")
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(rest, backButton(), "cancelled, it springs back")
        assertEquals(Screen.Sale("a1"), state.currentState)
        assertTrue(landed.isEmpty(), "and nothing went back")
    }

    @Test
    fun whereBackGoesIsReadAsTheGestureStartsNotAtTheLastComposition() {
        val state = SeekableTransitionState<Screen>(Screen.Sale("a1"))
        stage(state)
        compose.waitForIdle()
        // The model's answer changes with nothing recomposing (a sale arriving while Settings is up, say).
        target = Screen.Sale("b2")
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 1000f, 0f, BackEventCompat.EDGE_LEFT)) }
        gesture(0.3f)
        assertEquals(Screen.Sale("b2"), state.targetState, "the preview is toward where back goes now")
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(1_500)
    }

    @Test
    fun aScrubbedBackLandsOnTheSalesOwnRowWithTheRowsAroundItAtRest() {
        val shift = SeekableTransitionState<Screen>(Screen.Till)
        stage(shift)
        compose.waitForIdle()
        fun top(id: String) =
            compose.onNodeWithText(ShiftList.row(sales.first { it.orderId == id }, 0).title, substring = true, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.top
        val rest = sales.map { top(it.orderId) }
        compose.mainClock.autoAdvance = false
        scope.launch { shift.animateTo(Screen.Sale("a1"), app.monokular.ui.Motion.Full.stage) }
        compose.mainClock.advanceTimeBy(1_500)

        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 1000f, 0f, BackEventCompat.EDGE_LEFT)) }
        gesture(0.3f)
        gesture(0.6f)
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(Screen.Till, shift.currentState)
        assertEquals(rest, sales.map { top(it.orderId) }, "landed, every row is where it was")
        compose.mainClock.advanceTimeBy(100)
        assertEquals(rest, sales.map { top(it.orderId) }, "and stays there")
    }
}
