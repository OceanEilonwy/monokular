package app.monokular

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.monokular.core.SalePhase
import app.monokular.ui.Fixed
import app.monokular.ui.LocalMotion
import app.monokular.ui.MonokularTheme
import app.monokular.ui.Motion
import app.monokular.ui.PhaseRing
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertTrue

/** The ring's sweep on a real window, frame by frame on the test clock (M6). */
class PhaseRingOnDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val track = Fixed.Track
    private val fill = Fixed.Accent
    private val paid = Fixed.Paid

    private var phase by mutableStateOf(SalePhase.Waiting)

    private fun ring(motion: Motion, from: SalePhase) {
        phase = from
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MonokularTheme {
                CompositionLocalProvider(LocalMotion provides motion) {
                    Box(Modifier.testTag("ring").padding(0.dp)) { PhaseRing(phase, 120.dp, track = track, fill = fill, paid = paid) }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun frame(): ImageBitmap = compose.onNodeWithTag("ring").captureToImage()

    /** The colour on the middle of the stroke at [degrees] clockwise from the top. */
    private fun ImageBitmap.at(degrees: Double): Color {
        val side = width.toFloat()
        val stroke = side * 0.115f
        val radius = side / 2 - (stroke / 2 + side * 0.07f)
        val angle = Math.toRadians(degrees)
        val x = (side / 2 + radius * sin(angle)).toInt()
        val y = (side / 2 - radius * cos(angle)).toInt()
        return toPixelMap()[x, y]
    }

    private fun assertColour(expected: Color, actual: Color, what: String) {
        val close = abs(expected.red - actual.red) < 0.08f && abs(expected.green - actual.green) < 0.08f && abs(expected.blue - actual.blue) < 0.08f
        assertTrue(close, "$what: expected $expected, got $actual")
    }

    @Test
    fun aRingThatAppearsAtSeenIsAlreadyHalfFull() {
        ring(Motion.Full, SalePhase.Seen)
        val image = frame()
        assertColour(fill, image.at(170.0), "the half is drawn on the first frame")
        assertColour(track, image.at(200.0), "and no more")
    }

    @Test
    fun aStepSweepsTheNextQuarterAndNeverPassesIt() {
        ring(Motion.Full, SalePhase.Waiting)
        assertColour(track, frame().at(135.0), "Waiting fills the first quarter only")
        phase = SalePhase.Seen
        compose.mainClock.advanceTimeBy(100)
        val early = frame()
        assertColour(fill, early.at(105.0), "a tenth of a second in, the arc has started into the second quarter")
        assertColour(track, early.at(160.0), "but is still on its way")
        repeat(20) {
            compose.mainClock.advanceTimeBy(100)
            assertColour(track, frame().at(196.0), "the sweep never passes the half")
        }
        val settled = frame()
        assertColour(fill, settled.at(170.0), "it reaches the half")
    }

    @Test
    fun aSecondStepMidSweepCarriesOnFromWhereTheArcIs() {
        ring(Motion.Full, SalePhase.Waiting)
        phase = SalePhase.Seen
        compose.mainClock.advanceTimeBy(100)
        phase = SalePhase.Confirming
        compose.mainClock.advanceTimeByFrame()
        assertColour(fill, frame().at(105.0), "the arc doesn't go back to retarget")
        compose.mainClock.advanceTimeBy(2_000)
        val settled = frame()
        assertColour(fill, settled.at(260.0), "three quarters")
        assertColour(track, settled.at(286.0), "and no more")
    }

    @Test
    fun paidClosesTheRingInThePaidColour() {
        ring(Motion.Full, SalePhase.Confirming)
        phase = SalePhase.Paid
        compose.mainClock.advanceTimeBy(2_000)
        val image = frame()
        for (degrees in listOf(45.0, 135.0, 225.0, 315.0)) assertColour(paid, image.at(degrees), "closed at $degrees")
    }

    /** How far [c] is from the nearest colour between the track and [to]: a stroke fading over the track is on that line. */
    private fun offLine(c: Color, to: Color): Float {
        val d = floatArrayOf(to.red - track.red, to.green - track.green, to.blue - track.blue)
        val v = floatArrayOf(c.red - track.red, c.green - track.green, c.blue - track.blue)
        val a = ((v[0] * d[0] + v[1] * d[1] + v[2] * d[2]) / (d[0] * d[0] + d[1] * d[1] + d[2] * d[2])).coerceIn(0f, 1f)
        return maxOf(abs(v[0] - a * d[0]), abs(v[1] - a * d[1]), abs(v[2] - a * d[2]))
    }

    /** Every frame of a change, the arc where it's filled on both sides is the fill or the paid colour over the track, never a mix of the two (decision 190). */
    private fun assertNoMixedHue(what: String) {
        repeat(60) { frame ->
            val c = frame().at(45.0)
            val off = minOf(offLine(c, fill), offLine(c, paid))
            assertTrue(off < 0.06f, "$what, frame $frame: $c is neither colour over the track (off by $off)")
            compose.mainClock.advanceTimeByFrame()
        }
    }

    @Test
    fun paidAndTheNextSaleNeverDrawTheRingInAMixOfTheirColours() {
        ring(Motion.Full, SalePhase.Confirming)
        phase = SalePhase.Paid
        assertNoMixedHue("closing on paid")
        assertColour(paid, frame().at(45.0), "closed in the paid colour")
        phase = SalePhase.Waiting
        assertNoMixedHue("the next sale's ring")
        assertColour(fill, frame().at(45.0), "back to the fill")
    }

    @Test
    fun underStillMotionAStepIsDrawnOnTheNextFrame() {
        ring(Motion.Still, SalePhase.Waiting)
        phase = SalePhase.Seen
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertColour(fill, frame().at(170.0), "snapped to the half")
    }
}
