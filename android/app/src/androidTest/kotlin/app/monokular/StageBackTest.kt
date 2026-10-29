package app.monokular

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.monokular.core.PointOfSale
import app.monokular.OneClockTest.Companion.noActions
import app.monokular.OneClockTest.Companion.status
import app.monokular.ui.CodeScreen
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
    private val other = testSale("b2", expiresAt = 1_600)
    private val sales = listOf(testSale("z0", expiresAt = 1_600), testSale("y0", expiresAt = 1_600), sale, other)
    private val now = mutableLongStateOf(1_000)
    private lateinit var dispatcher: OnBackPressedDispatcher

    private fun stage(model: TestModel) = compose.setContent {
        dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        Till {
            ModelStage(model) { s ->
                when (s) {
                    is Screen.Code -> CodeScreen(sale, onBack = { model.back(s) })
                    is Screen.Sale -> SaleScreen(sales.first { it.orderId == s.orderId }, "Front", refunding = false, refundProblem = null, refundSheet = null, actions = noActions, now = now)
                    else -> ShiftScreen(status, sales, noActions, now)
                }
            }
        }
    }

    private fun started() = compose.runOnUiThread { dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 1000f, 0f, BackEventCompat.EDGE_LEFT)) }

    private fun gesture(progress: Float) {
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(BackEventCompat(10f, 1000f, progress, BackEventCompat.EDGE_LEFT)) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun backButton(): Rect =
        compose.onNodeWithContentDescription("Back to the shift", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** At rest, the stage shows the model's screen and is going nowhere else. */
    private fun assertAgree(model: TestModel, why: String) {
        assertEquals(model.screen.value, model.stage.currentState, "$why: the stage is on the model's screen")
        assertEquals(model.screen.value, model.stage.targetState, "$why: and is going nowhere else")
    }

    @Test
    fun theSaleIsAsFarTowardItsRowAsTheFingerIsAcross() {
        val model = TestModel(Screen.Sale("a1"))
        stage(model)
        compose.waitForIdle()
        val rest = backButton()
        compose.mainClock.autoAdvance = false

        started()
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
        assertEquals(Screen.Sale("a1"), model.screen.value, "nothing changes before the gesture commits")

        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(listOf<Screen>(Screen.Sale("a1")), model.commits, "released, the model is told the sale was left")
        assertEquals(Screen.Till, model.screen.value)
        assertAgree(model, "released")
    }

    @Test
    fun cancelledTheSaleSpringsBackToWhereItWas() {
        val model = TestModel(Screen.Sale("a1"))
        stage(model)
        compose.waitForIdle()
        val rest = backButton()
        compose.mainClock.autoAdvance = false

        started()
        gesture(0.4f)
        assertTrue(backButton().top > rest.top + 10f, "the preview moved it")
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(rest, backButton(), "cancelled, it springs back")
        assertTrue(model.commits.isEmpty(), "and nothing went back")
        assertAgree(model, "cancelled")
    }

    @Test
    fun whereBackGoesIsReadAsTheGestureStartsNotAtTheLastComposition() {
        val model = TestModel(Screen.Sale("a1"))
        stage(model)
        compose.waitForIdle()
        // The model's answer changes with nothing recomposing (a sale arriving while Settings is up, say).
        model.backTarget = { Screen.Sale("b2") }
        compose.mainClock.autoAdvance = false
        started()
        gesture(0.3f)
        assertEquals(Screen.Sale("b2"), model.stage.targetState, "the preview is toward where back goes now")
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(1_500)
        assertAgree(model, "cancelled")
    }

    @Test
    fun aScrubbedBackLandsOnTheSalesOwnRowWithTheRowsAroundItAtRest() {
        val model = TestModel(Screen.Till)
        stage(model)
        compose.waitForIdle()
        fun top(id: String) =
            compose.onNodeWithText(ShiftList.row(sales.first { it.orderId == id }, PointOfSale.Square, 0).title, substring = true, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.top
        val rest = sales.map { top(it.orderId) }
        compose.mainClock.autoAdvance = false
        model.go(Screen.Sale("a1"))
        compose.mainClock.advanceTimeBy(1_500)

        started()
        gesture(0.3f)
        gesture(0.6f)
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_500)
        assertAgree(model, "landed")
        assertEquals(Screen.Till, model.stage.currentState)
        assertEquals(rest, sales.map { top(it.orderId) }, "landed, every row is where it was")
        compose.mainClock.advanceTimeBy(100)
        assertEquals(rest, sales.map { top(it.orderId) }, "and stays there")
    }

    @Test
    fun aSaleComingUpUnderACancelledGestureIsWhereTheStageGoes() {
        val model = TestModel(Screen.Sale("a1"))
        stage(model)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        started()
        gesture(0.3f)
        // A new sale comes up by itself while the finger is down.
        model.go(Screen.Sale("b2"), byItself = true)
        gesture(0.5f)
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(1_500)
        assertTrue(model.commits.isEmpty(), "cancelled, nothing went back")
        assertEquals(Screen.Sale("b2"), model.screen.value)
        assertAgree(model, "after the cancel")
    }

    @Test
    fun aSaleComingUpUnderACommittedGestureIsWhereTheStageGoes() {
        val model = TestModel(Screen.Sale("a1"))
        stage(model)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        started()
        gesture(0.3f)
        model.go(Screen.Sale("b2"), byItself = true)
        gesture(0.6f)
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(listOf<Screen>(Screen.Sale("a1")), model.commits, "the Back was from the sale the gesture started on")
        assertEquals(Screen.Sale("b2"), model.screen.value, "which had already gone, so the model stays on the new sale")
        assertAgree(model, "after the commit")
        // And Back still works from there.
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(Screen.Till, model.screen.value)
        assertAgree(model, "after a second Back")
    }

    @Test
    fun aBackPressedMidGrowGoesBackFromTheCodeAndASecondReachesTheShift() {
        val model = TestModel(Screen.Sale("a1"))
        stage(model)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        // The cashier taps the code and presses Back before it has landed (decision 129).
        model.go(Screen.Code("a1"))
        compose.mainClock.advanceTimeBy(96)
        assertTrue(model.stage.currentState != model.stage.targetState, "the grow is still running")
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(listOf<Screen>(Screen.Code("a1")), model.commits, "Back was from the code, the model's screen")
        assertEquals(Screen.Sale("a1"), model.screen.value)
        assertAgree(model, "after the first Back")

        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(Screen.Till, model.screen.value, "a second Back reaches the shift")
        assertAgree(model, "after the second Back")
    }
}
