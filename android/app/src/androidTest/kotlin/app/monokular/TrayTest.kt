package app.monokular

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.monokular.OneClockTest.Companion.noActions
import app.monokular.OneClockTest.Companion.status
import app.monokular.core.PointOfSale
import app.monokular.core.SalePhase
import app.monokular.sales.TillSale
import app.monokular.ui.Motion
import app.monokular.ui.ShiftScreen
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The waiting tray (A4, `docs/motion.md` §12.3) on a real frame clock: the
 * fold after three rows and its words, opening in place, the filters, what
 * TalkBack is told, the fold kept across a rotation, and the rows moving
 * between the tray and Earlier under the list's own item specs.
 */
class TrayTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = mutableLongStateOf(1_000)
    private val recompositions = Recompositions()

    @After
    fun tearDown() = recompositions.close()

    /** [n] live sales, newest first ("w<n>" to "w1"), then a paid one and an expired one. */
    private fun busy(n: Int) = List(n) { testSale("w${n - it}", expiresAt = 1_600) } +
        listOf(testSale("p1", SalePhase.Paid, paidAt = 900), testSale("e1", SalePhase.Expired, expiresAt = 900))

    private fun title(sale: TillSale) = ShiftList.row(sale, PointOfSale.Square, 0).title

    private fun shows(sale: TillSale) = compose.onAllNodes(SemanticsMatcher("row ${sale.orderId}") { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == title(sale) } == true
    }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun top(sale: TillSale): Float =
        compose.onNodeWithText(title(sale), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    private fun exists(text: String, substring: Boolean = false) =
        compose.onAllNodes(SemanticsMatcher("says $text") { n ->
            val said = n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            said.any { if (substring) it.contains(text) else it == text }
        }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun show(sales: List<TillSale>, motion: Motion = Motion.Full) {
        compose.setContent { Till(motion) { ShiftScreen(status, sales, noActions, now) } }
        compose.waitForIdle()
    }

    @Test
    fun fourWaitingFoldAfterThreeAndOpenInPlace() {
        val sales = busy(4)
        show(sales)
        assertEquals(listOf(true, true, true, false), sales.take(4).map(::shows), "three rows, the fourth folded away")
        assertTrue(exists("Show 1 more waiting"))
        assertTrue(shows(sales[4]) && exists(ShiftList.EARLIER), "the paid sale under Earlier")
        compose.onNodeWithContentDescription("Show 1 more waiting sale").performClick()
        compose.waitForIdle()
        assertTrue(sales.take(4).all(::shows), "open, every live sale is a row")
        assertTrue(exists("Show fewer"))
        assertTrue(top(sales[3]) < top(sales[4]), "the fourth opens in place, in the tray above Earlier")
        compose.onNodeWithContentDescription("Show fewer").performClick()
        compose.waitForIdle()
        assertTrue(!shows(sales[3]) && exists("Show 1 more waiting"), "and folds again")
    }

    @Test
    fun twentyOneWaitingCountAndFoldEighteen() {
        show(busy(21))
        assertTrue(exists("Waiting for payment, 21 sales"))
        assertTrue(exists("Show 18 more waiting"))
        compose.onNodeWithContentDescription("Show 18 more waiting sales").performClick()
        compose.waitForIdle()
        assertTrue(exists("Show fewer"), "the fold row's words change at once; the rows open under it")
    }

    @Test
    fun twoWaitingHaveNoFold() {
        val sales = busy(2)
        show(sales)
        assertTrue(sales.take(2).all(::shows))
        assertTrue(!exists("more waiting", substring = true) && !exists("Show fewer"))
        assertTrue(exists("Waiting for payment, 2 sales") && exists(ShiftList.EARLIER))
    }

    @Test
    fun noLiveSaleNoTrayAndNoHeaders() {
        show(busy(0))
        assertTrue(!exists(ShiftList.TRAY_HEADER, substring = true) && !exists(ShiftList.EARLIER) && !exists("more waiting", substring = true))
        assertTrue(shows(testSale("p1", SalePhase.Paid, paidAt = 900)), "the list reads as it always has")
    }

    @Test
    fun needsActionShowsTheTrayUnfolded() {
        val sales = busy(5)
        show(sales)
        compose.onNodeWithText("Needs action", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertTrue(sales.take(5).all(::shows), "every live sale")
        assertTrue(!exists("more waiting", substring = true) && !exists("Show fewer"), "no fold row")
        assertTrue(!shows(sales[5]) && shows(sales[6]), "then what ended unpaid, not the paid sale")
    }

    @Test
    fun theFoldRowAndTheHeaderSayWhatTheyAre() {
        show(busy(4))
        compose.onNodeWithContentDescription("Waiting for payment, 4 sales").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText(ShiftList.EARLIER).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val fold = compose.onNodeWithContentDescription("Show 1 more waiting sale")
        fold.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        fold.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        fold.performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Show fewer").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }

    @Test
    fun theFoldIsKeptAcrossARotation() {
        val restore = StateRestorationTester(compose)
        val sales = busy(4)
        restore.setContent { Till { ShiftScreen(status, sales, noActions, now) } }
        compose.onNodeWithContentDescription("Show 1 more waiting sale").performClick()
        compose.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue(shows(sales[3]) && exists("Show fewer"), "still open")
    }

    /** A sale paid: its row is the same item, moving from the tray to the top of Earlier while the rows round it settle. */
    @Test
    fun aSalePaidMovesFromTheTrayToTheTopOfEarlier() {
        val start = busy(3)
        var sales by mutableStateOf(start)
        compose.setContent { Till { ShiftScreen(status, sales, noActions, now) } }
        compose.waitForIdle()
        val w2 = start[1]
        val from = top(w2)
        compose.mainClock.autoAdvance = false
        val paid = testSale("w2", SalePhase.Paid, paidAt = 1_000)
        // The till keeps the shift newest first by when each sale started: w2 stays where it was in the list.
        sales = listOf(start[0], paid, start[2]) + start.drop(3)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        val during = top(paid)
        compose.mainClock.advanceTimeBy(800)
        val to = top(paid)
        assertTrue(to > from, "it lands below the tray: $from to $to")
        assertTrue(during > from && during < to, "mid-move it is between: $from < $during < $to")
        assertTrue(to < top(start[3]), "at the top of Earlier, over the sale paid before it")
        assertTrue(top(start[2]) < from + 1f, "the row under it in the tray moved up into its place")
    }

    /** The last live sale paid: the tray's header goes and Earlier's with it, the list reading as it always has. */
    @Test
    fun theLastLiveSaleGoneTakesTheHeadersWithIt() {
        val start = busy(1)
        var sales by mutableStateOf(start)
        compose.setContent { Till { ShiftScreen(status, sales, noActions, now) } }
        compose.waitForIdle()
        assertTrue(exists("Waiting for payment, 1 sale"))
        compose.mainClock.autoAdvance = false
        sales = listOf(testSale("w1", SalePhase.Paid, paidAt = 1_000)) + start.drop(1)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(!exists(ShiftList.TRAY_HEADER, substring = true) && !exists(ShiftList.EARLIER))
    }

    /** A new sale on a showing shift lands at the top of the tray; folded, the third row goes into the fold. */
    @Test
    fun aNewSaleLandsAtTheTopOfTheTray() {
        val start = busy(3)
        var sales by mutableStateOf(start)
        compose.setContent { Till { ShiftScreen(status, sales, noActions, now) } }
        compose.waitForIdle()
        val first = top(start[0])
        compose.mainClock.autoAdvance = false
        val new = testSale("w4", expiresAt = 1_600)
        sales = listOf(new) + start
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(first, top(new), 0.5f, "at the tray's top")
        assertTrue(!shows(start[2]) && exists("Show 1 more waiting"), "the third row moved into the fold")
        assertTrue(exists("Waiting for payment, 4 sales"))
    }

    /** Opening and folding the tray recomposes no row that was already there: the rows entering compose, nothing per frame. */
    @Test
    fun openingTheTrayRecomposesOnlyTheRowsComingIn() {
        show(busy(6))
        compose.mainClock.autoAdvance = false
        recompositions.reset()
        compose.onNodeWithContentDescription("Show 3 more waiting sales").performClick()
        compose.mainClock.advanceTimeByFrame()
        val entering = recompositions.count("app.monokular.ui.SaleRow")
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
        assertTrue(entering <= 3, "only the three rows coming in compose: $entering")
        assertEquals(entering, recompositions.count("app.monokular.ui.SaleRow"), "no row recomposes over the next ten frames")
    }

    @Test
    fun underStillTheTrayOpensWithASnap() {
        val sales = busy(4)
        show(sales, Motion.Still)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Show 1 more waiting sale").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertTrue(shows(sales[3]) && exists("Show fewer"))
    }
}
