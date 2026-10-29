package app.monokular

import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntSize
import app.monokular.OneClockTest.Companion.noActions
import app.monokular.OneClockTest.Companion.status
import app.monokular.ui.CodeScreen
import app.monokular.ui.SaleScreen
import app.monokular.ui.ShiftScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M7 on a real frame clock: the shift and a sale reach each other through the stage, the code inside laid out once. */
class StageTest {
    @get:Rule
    val compose = createComposeRule()

    private val sale = testSale("a1", expiresAt = 1_600)
    private val sales = listOf(sale, testSale("b2", expiresAt = 1_600))
    private val now = mutableLongStateOf(1_000)
    private val recompositions = Recompositions()
    private lateinit var scope: CoroutineScope

    @After
    fun stopCounting() = recompositions.close()

    private fun stage(state: SeekableTransitionState<Screen>, byItself: Boolean = false) = compose.setContent {
        scope = rememberCoroutineScope()
        Till {
            TillStage(state, cameUpByItself = { byItself }) { s ->
                when (s) {
                    is Screen.Sale -> SaleScreen(sale, "Front", refunding = false, refundProblem = null, actions = noActions, now = now)
                    else -> ShiftScreen(status, sales, noActions, now)
                }
            }
        }
    }

    private fun code() = compose.onNodeWithContentDescription("Payment code for", substring = true, useUnmergedTree = true)

    private fun row(): Rect =
        compose.onNodeWithText(ShiftList.row(sale, 0).title, substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun theShiftGrowsIntoTheSaleWithBothOnScreenOnTheWayAndTheCodeLaidOutOnce() {
        var screen by mutableStateOf<Screen>(Screen.Till)
        val state = SeekableTransitionState<Screen>(Screen.Till)
        compose.setContent {
            Till {
                LaunchedEffect(screen) { state.animateTo(screen) }
                TillStage(state, cameUpByItself = { false }) { s ->
                    when (s) {
                        is Screen.Sale -> SaleScreen(sale, "Front", refunding = false, refundProblem = null, actions = noActions, now = now)
                        else -> ShiftScreen(status, sales, noActions, now)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        screen = Screen.Sale("a1")
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val size = code().fetchSemanticsNode().size
        recompositions.reset()
        val sizes = mutableListOf<IntSize>()
        repeat(5) {
            compose.mainClock.advanceTimeBy(40)
            sizes += code().fetchSemanticsNode().size
        }
        compose.onAllNodesWithText("This shift", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("Waiting for payment", useUnmergedTree = true).assertCountEquals(1)
        assertTrue(sizes.all { it == size }, "the code keeps its layout size through the grow: $size, $sizes")
        // SaleScreen's own body (its shared surface) may run as the transition starts and ends; what it shows doesn't.
        for (name in listOf("QrCode", "SaleRow", "LiveSale", "StatusPill", "ShiftScreen")) {
            assertEquals(0, recompositions.count("app.monokular.ui.$name"), "$name isn't recomposed by the transition's frames")
        }

        compose.mainClock.advanceTimeBy(1_500)
        compose.onAllNodesWithText("This shift", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Waiting for payment", useUnmergedTree = true).assertExists()
        assertEquals(size, code().fetchSemanticsNode().size, "and lands at the size it was laid out at")
    }

    @Test
    fun aBackGestureScrubsTheSaleTowardItsRowAndCancelledItSpringsBack() {
        val state = SeekableTransitionState<Screen>(Screen.Sale("a1"))
        stage(state)
        compose.waitForIdle()
        fun back() = compose.onNodeWithContentDescription("Back to the shift", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val full = back()
        val codeWidth = code().fetchSemanticsNode().boundsInRoot.width
        val codeSize = code().fetchSemanticsNode().size
        compose.mainClock.autoAdvance = false

        fun seek(fraction: Float) {
            scope.launch { state.seekTo(fraction, Screen.Till) }
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
        }
        seek(0.08f)
        val early = back()
        val drawn = code().fetchSemanticsNode().boundsInRoot.width
        val laidOut = code().fetchSemanticsNode().size
        seek(0.99f)
        val late = back()
        assertTrue(full.top < early.top && early.top < late.top, "the sale moves down toward its row as the gesture goes: ${full.top} < ${early.top} < ${late.top}")
        assertTrue(late.top > row().top - full.top, "and ends at the row, its own top bar a scaled inset below the row's top")
        assertTrue(drawn < codeWidth, "the code is drawn smaller, as a layer: $codeWidth -> $drawn")
        assertEquals(codeSize, laidOut, "and keeps the size it was laid out at")

        scope.launch { state.animateTo(Screen.Sale("a1")) }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(full, back(), "cancelled, it springs back")
    }

    @Test
    fun theCodeGrowsOutOfItsCardLaidOutOnceAndAShrinkMidGrowReverses() {
        var screen by mutableStateOf<Screen>(Screen.Sale("a1"))
        val state = SeekableTransitionState<Screen>(Screen.Sale("a1"))
        compose.setContent {
            Till {
                LaunchedEffect(screen) { state.animateTo(screen) }
                TillStage(state, cameUpByItself = { false }) { s ->
                    when (s) {
                        is Screen.Code -> CodeScreen(sale, onBack = {})
                        else -> SaleScreen(sale, "Front", refunding = false, refundProblem = null, actions = noActions, now = now)
                    }
                }
            }
        }
        compose.waitForIdle()
        val small = code().fetchSemanticsNode().size
        compose.mainClock.autoAdvance = false

        screen = Screen.Code("a1")
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        fun big() = compose.onNodeWithContentDescription("Payment code, tap to shrink", useUnmergedTree = true).fetchSemanticsNode()
        val bigSize = big().size
        recompositions.reset()
        val drawn = mutableListOf<Float>()
        for (t in listOf(0L, 75L, 75L)) {
            compose.mainClock.advanceTimeBy(t)
            assertEquals(bigSize, big().size, "the enlarged code keeps its laid-out size at every frame")
            assertEquals(small, code().fetchSemanticsNode().size, "and so does the sale's")
            drawn += big().boundsInRoot.width
        }
        assertEquals(0, recompositions.count("app.monokular.ui.QrCode"), "the code isn't recomposed by the grow")
        assertTrue(drawn[0] < drawn[2] && drawn[2] < bigSize.width, "mid-grow it is drawn between the card and the screen: $drawn of ${bigSize.width}")

        screen = Screen.Sale("a1")
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(50)
        val shrinking = big().boundsInRoot.width
        compose.mainClock.advanceTimeBy(50)
        assertTrue(big().boundsInRoot.width < shrinking && shrinking <= drawn[2] + 1f, "Back mid-grow shrinks from where it got to: ${drawn[2]} -> $shrinking -> ${big().boundsInRoot.width}")

        compose.mainClock.advanceTimeBy(1_500)
        compose.onAllNodesWithContentDescription("Payment code, tap to shrink", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(small, code().fetchSemanticsNode().size, "and lands at its 264 dp card")
    }
}
