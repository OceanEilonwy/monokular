package app.monokular

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import app.monokular.OneClockTest.Companion.noActions
import app.monokular.OneClockTest.Companion.status
import app.monokular.ui.CodeScreen
import app.monokular.ui.OrderPage
import app.monokular.ui.SaleScreen
import app.monokular.ui.ShiftScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertTrue

/**
 * Every screen [Move] seeked to a quarter, a half and three quarters, each
 * frame written out for review (`docs/reports/motion/frames/`), and the
 * code checked on every one: what a camera would catch mid-move.
 */
class MotionFramesTest {
    @get:Rule
    val compose = createComposeRule()

    private val sale = testSale("a1", expiresAt = 1_600).copy(receipt = sampleReceipt())
    private val sales = listOf(testSale("z0", expiresAt = 1_600), sale, testSale("b2", expiresAt = 1_600))
    private val now = mutableLongStateOf(1_000)
    private lateinit var scope: CoroutineScope

    private fun stage(from: Screen, byItself: Boolean = false): SeekableTransitionState<Screen> {
        val state = SeekableTransitionState(from)
        compose.setContent {
            scope = rememberCoroutineScope()
            Till {
                TillStage(state, cameUpByItself = { byItself }) { s ->
                    when (s) {
                        is Screen.Code -> CodeScreen(sale, onBack = {})
                        is Screen.Order -> OrderPage(sale, "Back to the sale", onBack = {}, onShowCode = {})
                        is Screen.Sale -> SaleScreen(sale, "Front", refunding = false, refundProblem = null, actions = noActions, now = now)
                        else -> ShiftScreen(status, sales, noActions, now)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        return state
    }

    /** [state] seeked toward [to] at each of [Points], [check] run and the frame saved as `<name>-<percent>.png`. */
    private fun seek(state: SeekableTransitionState<Screen>, to: Screen, name: String, check: (Float, ImageBitmap) -> Unit = { _, _ -> }) {
        scope.launch { state.seekTo(0f, to) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val failures = mutableListOf<String>()
        for (p in Points) {
            scope.launch { state.seekTo(p, to) }
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            val frame = compose.onRoot().captureToImage()
            save(frame, "$name-${(p * 100).roundToInt()}")
            runCatching { check(p, frame) }.exceptionOrNull()?.let { failures += it.message.orEmpty() }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun bounds(description: String): Rect =
        compose.onNodeWithContentDescription(description, substring = true, useUnmergedTree = true).fetchSemanticsNode().drawn()

    @Test
    fun theEnlargingCodeIsOneWholeSquareCodeOnItsWhiteOnEveryFrame() {
        val state = stage(Screen.Sale("a1"))
        val rest = bounds("Payment code for")
        seek(state, Screen.Code("a1"), "code-grow") { p, frame ->
            val code = bounds("Payment code, tap to shrink")
            assertTrue(abs(code.width - code.height) <= 1f, "at $p the code is square: $code")
            assertTrue(code.width >= rest.width - 1f, "at $p it is no smaller than at rest: ${code.width} < ${rest.width}")
            assertQuietZone(frame, code, "at $p")
        }
    }

    @Test
    fun theShrinkingCodeIsOneWholeSquareCodeOnItsWhiteOnEveryFrame() {
        val state = stage(Screen.Code("a1"))
        seek(state, Screen.Sale("a1"), "code-shrink") { p, frame ->
            val code = bounds("Payment code, tap to shrink")
            assertTrue(abs(code.width - code.height) <= 1f, "at $p the code is square: $code")
            assertQuietZone(frame, code, "at $p")
        }
    }

    @Test
    fun aRowGrowsIntoItsSale() = seek(stage(Screen.Till), Screen.Sale("a1"), "row-grow")

    @Test
    fun aSaleThatCameUpByItselfRises() = seek(stage(Screen.Till, byItself = true), Screen.Sale("a1"), "rise")

    @Test
    fun theOrderCardGrowsIntoTheOrderPage() = seek(stage(Screen.Sale("a1")), Screen.Order("a1"), "order-grow")

    @Test
    fun theOrderPageStartsAsTheSaleItself() {
        val state = stage(Screen.Sale("a1"))
        val sale = compose.onRoot().captureToImage().asAndroidBitmap()
        scope.launch { state.seekTo(0f, Screen.Order("a1")) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val start = compose.onRoot().captureToImage().asAndroidBitmap()
        var differ = 0
        for (y in 0 until sale.height step 4) for (x in 0 until sale.width step 4) if (sale.getPixel(x, y) != start.getPixel(x, y)) differ++
        val total = (sale.height / 4) * (sale.width / 4)
        assertTrue(differ * 200 < total, "at 0 the page's bar, heading and foot are unseen and the card is the sale's own: $differ of $total differ")
    }

    /** The code drawn whole: a ring of pure white just outside its edges on every side, so nothing overlaps or crops it. */
    private fun assertQuietZone(frame: ImageBitmap, code: Rect, at: String) {
        val bitmap = frame.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        val out = 3
        val left = (code.left - out).roundToInt()
        val right = (code.right + out).roundToInt()
        val top = (code.top - out).roundToInt()
        val bottom = (code.bottom + out).roundToInt()
        val stepX = ((right - left) / 24).coerceAtLeast(1)
        val stepY = ((bottom - top) / 24).coerceAtLeast(1)
        val offWhite = mutableListOf<String>()
        fun look(x: Int, y: Int) {
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return
            val c = bitmap.getPixel(x, y)
            if (c != WHITE) offWhite += "($x,$y)=${Integer.toHexString(c)}"
        }
        for (x in left..right step stepX) { look(x, top); look(x, bottom) }
        for (y in top..bottom step stepY) { look(left, y); look(right, y) }
        assertTrue(offWhite.isEmpty(), "$at the code has white all round it (its quiet zone, on its ground): ${offWhite.take(6)}")
    }

    private fun save(frame: ImageBitmap, name: String) {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "frames")
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { frame.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        val Points = listOf(0.25f, 0.5f, 0.75f)
        const val WHITE = -0x1
    }
}
