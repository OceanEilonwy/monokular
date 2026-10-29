package app.monokular

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import app.monokular.core.SalePhase
import app.monokular.overlay.CARD_TAG
import app.monokular.overlay.CODE_TAG
import app.monokular.overlay.FloatingPanel
import app.monokular.overlay.Panel
import app.monokular.overlay.PanelActions
import app.monokular.overlay.PanelMotion
import app.monokular.overlay.PanelPage
import app.monokular.overlay.PhonePanel
import app.monokular.ui.Fixed
import app.monokular.ui.Motion
import app.monokular.ui.mk
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * The overlay's moves stopped at a quarter, a half and three quarters of
 * their spring's settling time, each frame saved for reviewers
 * (`docs/reports/motion/frames/`; the files land in the app's external
 * files dir, `frames/`) and the payment code checked on each: square and
 * not clipped, so it would scan whichever frame a camera caught. A
 * recording on the software emulator can't show these frames (decision
 * 114); the test clock can.
 */
class OverlayFramesTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = PanelActions({}, {}, {}, {}, {}, {}, {}, {})
    /** Fifteen minutes from now, so the frames show a countdown a cashier would see. */
    private val expires = System.currentTimeMillis() / 1000 + 15 * 60
    private var sale by mutableStateOf(testSale("f1", expiresAt = expires))
    private var page by mutableStateOf<PanelPage>(PanelPage.Code)
    private lateinit var motion: PanelMotion

    /** `motion.surface` settles in about this long (`docs/motion.md` §2): the seek points are its quarters. */
    private val surfaceMs = 450L

    /** The panel over a backdrop: a strong colour by default, so the card's edges show; [white] for Square's own ground. */
    private fun panel(raised: Float, white: Boolean = false, tablet: Boolean = false, presets: Motion = Motion.Full) {
        compose.mainClock.autoAdvance = false
        motion = PanelMotion(raised, mutableStateOf<Offset?>(Offset(1200f, 900f)))
        compose.setContent {
            Till(presets) {
                motion.raise.Attach()
                Box(Modifier.fillMaxSize().background(if (white) Fixed.White else mk.danger).testTag("window")) {
                    if (tablet) {
                        FloatingPanel(sale, "Front Counter", page, actions, motion)
                    } else {
                        PhonePanel(sale, "Front Counter", BubbleLook.Lit, page, actions, motion)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
    }

    private fun save(move: String, quarter: Int, image: Bitmap = compose.onNodeWithTag("window").captureToImage().asAndroidBitmap()) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "frames").apply { mkdirs() }
        val small = Bitmap.createScaledBitmap(image, image.width / 4, image.height / 4, true)
        File(dir, (if (move.startsWith("tablet")) "" else "phone-") + "$move-q$quarter.png").outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The code, wherever it shows on this frame, is square and drawn whole. */
    private fun assertCodeWhole(move: String, quarter: Int) {
        compose.onAllNodesWithTag(CODE_TAG, useUnmergedTree = true).fetchSemanticsNodes().forEach { code ->
            val drawn = code.boundsInRoot
            if (drawn.width <= 0f) return@forEach
            assertTrue(abs(drawn.width - drawn.height) <= 2f, "$move q$quarter: the code is ${drawn.width} x ${drawn.height}, not square")
        }
    }

    /**
     * The code is drawn at full strength on this frame: its darkest modules
     * are near black. A code faded in shows them grey, and a camera already
     * on the panel would see a faint code (§10 rule 6).
     */
    private fun assertCodeOpaque(move: String, quarter: Int) {
        val image = compose.onNodeWithTag("window").captureToImage().asAndroidBitmap()
        val code = compose.onNodeWithTag(CODE_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        var darkest = 1f
        for (y in code.top.toInt().coerceAtLeast(0) until code.bottom.toInt().coerceAtMost(image.height) step 2) {
            for (x in code.left.toInt().coerceAtLeast(0) until code.right.toInt().coerceAtMost(image.width) step 2) {
                val p = image.getPixel(x, y)
                darkest = minOf(darkest, (android.graphics.Color.red(p) + android.graphics.Color.green(p) + android.graphics.Color.blue(p)) / (3f * 255f))
            }
        }
        assertTrue(darkest < 0.15f, "$move q$quarter: the code's darkest module is $darkest light, so it is faded")
    }

    /** Seeks [move] to its quarters; from quarter [opaqueFrom] on, the code must be drawn at full strength. */
    private fun seek(move: String, opaqueFrom: Int = 4, start: () -> Unit) {
        start()
        var at = 0L
        for (quarter in 1..3) {
            val to = surfaceMs * quarter / 4
            compose.mainClock.advanceTimeBy(to - at)
            at = to
            save(move, quarter)
            assertCodeWhole(move, quarter)
            if (quarter >= opaqueFrom) assertCodeOpaque(move, quarter)
        }
        compose.mainClock.advanceTimeBy(1_500)
        save(move, 4)
    }

    @Test
    fun thePanelsRaise() {
        panel(raised = 0f)
        seek("raise") { motion.raise.enter() }
    }

    @Test
    fun thePanelsLower() {
        panel(raised = 1f)
        seek("lower") { motion.raise.exit() }
    }

    /**
     * How dark the band just under the card is on a white backdrop, 0 to 1:
     * the card's shadow, which nothing else draws there.
     */
    private fun shadowUnderCard(image: Bitmap): Float {
        val card = compose.onNodeWithTag(CARD_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val top = card.bottom.toInt() + 2
        val bottom = (card.bottom + card.height * 0.04f).toInt().coerceAtMost(image.height - 1)
        var darkest = 0f
        for (y in top..bottom) {
            for (x in (card.left + card.width / 4).toInt()..(card.right - card.width / 4).toInt()) {
                val p = image.getPixel(x, y)
                val light = (android.graphics.Color.red(p) + android.graphics.Color.green(p) + android.graphics.Color.blue(p)) / (3f * 255f)
                darkest = maxOf(darkest, 1f - light)
            }
        }
        return darkest
    }

    /** Lowered over Square's white, the shadow goes with the card: never darker than the card is opaque (decision 134). */
    @Test
    fun theLowersShadowGoesWithTheCard() {
        panel(raised = 1f, white = true)
        val atRest = shadowUnderCard(compose.onNodeWithTag("window").captureToImage().asAndroidBitmap())
        assertTrue(atRest > 0.03f, "the card casts a shadow at rest ($atRest)")
        motion.raise.exit()
        var at = 0L
        for (quarter in 1..3) {
            val to = surfaceMs * quarter / 4
            compose.mainClock.advanceTimeBy(to - at)
            at = to
            val image = compose.onNodeWithTag("window").captureToImage().asAndroidBitmap()
            save("lower-light", quarter, image)
            val shown = Panel.shown(motion.raise.value)
            val shadow = shadowUnderCard(image)
            assertTrue(shadow <= atRest * shown + 0.02f, "q$quarter: the shadow is $shadow dark under a card $shown opaque (at rest $atRest)")
        }
    }

    @Test
    fun thePaidFold() {
        panel(raised = 1f)
        seek("paid-fold") { sale = testSale("f1", SalePhase.Paid, expiresAt = expires) }
    }

    @Test
    fun theNextSaleGrowingOutOfThePaidTick() {
        sale = testSale("f1", SalePhase.Paid, expiresAt = expires)
        panel(raised = 1f)
        seek("next-sale", opaqueFrom = 1) { sale = testSale("f2", expiresAt = expires) }
    }

    @Test
    fun theTabletsCodeComingBackFromTheOrder() {
        sale = sale.copy(receipt = sampleReceipt())
        page = PanelPage.Order
        panel(raised = 1f, tablet = true)
        compose.mainClock.advanceTimeBy(1_000)
        seek("tablet-back-to-code", opaqueFrom = 1) { page = PanelPage.Code }
    }

    @Test
    fun thePhonesCodeComingBackFromTheOrderUnderStill() {
        sale = sale.copy(receipt = sampleReceipt())
        page = PanelPage.Order
        panel(raised = 1f, presets = Motion.Still)
        compose.mainClock.advanceTimeBy(1_000)
        // Nothing grows under Still: the order crossfades off the code (fadeQuick), which is whole under it from then on.
        seek("still-back-to-code", opaqueFrom = 2) { page = PanelPage.Code }
    }

    @Test
    fun theOrderGrowingOutOfTheCard() {
        panel(raised = 1f)
        seek("order-grow") { page = PanelPage.Order }
    }
}
