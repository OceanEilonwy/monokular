package app.monokular

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import app.monokular.core.SalePhase
import app.monokular.overlay.PanelActions
import app.monokular.overlay.PanelMotion
import app.monokular.overlay.PanelPage
import app.monokular.overlay.PhonePanel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The overlay's moves recompose nothing per frame (`docs/motion.md` §10
 * rule 1): mid-move, the panel's card, its code, its rings and the order
 * run no composable body; every moving value is read in a layer, layout
 * or draw lambda. Counted from the compiler's trace markers, as Layout
 * Inspector counts them (decision 161).
 */
class OverlayRecompositionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val recompositions = Recompositions()
    private val actions = PanelActions({}, {}, {}, {}, {}, {}, {}, {})
    private val expires = System.currentTimeMillis() / 1000 + 15 * 60
    private var sale by mutableStateOf(testSale("r1", SalePhase.Confirming, expiresAt = expires))
    private var page by mutableStateOf<PanelPage>(PanelPage.Code)
    private lateinit var motion: PanelMotion

    private val named = listOf(
        "app.monokular.overlay.PanelCard", "app.monokular.overlay.Details", "app.monokular.ui.QrCode",
        "app.monokular.ui.PhaseRing", "app.monokular.overlay.BubbleFace", "app.monokular.ui.OrderContent",
        "app.monokular.overlay.StatusRow",
    )

    @After
    fun close() = recompositions.close()

    private fun panel(raised: Float) {
        compose.mainClock.autoAdvance = false
        motion = PanelMotion(raised, mutableStateOf<Offset?>(Offset(1200f, 900f)))
        compose.setContent {
            Till {
                motion.raise.Attach()
                PhonePanel(sale, "Front Counter", BubbleLook.Lit, page, actions, motion)
            }
        }
        compose.mainClock.advanceTimeBy(100)
        assertTrue(recompositions.count("app.monokular.overlay.PanelCard") > 0, "the tracer sees the panel compose")
    }

    /** Starts a move, lets its first frames compose what it brings, then counts ten frames of it. */
    private fun assertNothingRecomposesMidMove(move: String, start: () -> Unit) {
        start()
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        recompositions.reset()
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
        for (name in named) assertEquals(0, recompositions.count(name), "$move: $name recomposed mid-move")
    }

    @Test
    fun theRaise() {
        panel(raised = 0f)
        assertNothingRecomposesMidMove("raise") { motion.raise.enter() }
    }

    @Test
    fun theLower() {
        panel(raised = 1f)
        assertNothingRecomposesMidMove("lower") { motion.raise.exit() }
    }

    @Test
    fun thePaidFold() {
        panel(raised = 1f)
        assertNothingRecomposesMidMove("paid fold") { sale = testSale("r1", SalePhase.Paid, expiresAt = expires) }
    }

    @Test
    fun theOrdersGrow() {
        panel(raised = 1f)
        assertNothingRecomposesMidMove("order grow") { page = PanelPage.Order }
    }

    @Test
    fun theLateOrderLine() {
        panel(raised = 1f)
        assertNothingRecomposesMidMove("late order line") { sale = sale.copy(receipt = sampleReceipt()) }
    }
}
