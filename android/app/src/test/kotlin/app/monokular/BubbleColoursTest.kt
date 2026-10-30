package app.monokular

import androidx.compose.ui.graphics.Color
import app.monokular.ui.Fixed
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The logo bubble's fixed colours (`docs/motion.md` §12.5, decision 267)
 * hold the contrast the design claims for what a cashier reads off the
 * bubble: the white tick on each of the lens's greens and the ink
 * segment on the pale track it chases round, each at least WCAG's 3:1 for
 * graphics. The bubble sits over another app, so these are [Fixed], the
 * same in both themes.
 */
class BubbleColoursTest {
    private fun channel(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun `the white tick reads on every green of the lens`() {
        for (green in listOf(Fixed.PaidLight, Fixed.PaidMid, Fixed.Paid)) {
            val ratio = contrast(Fixed.White, green)
            assertTrue(ratio >= 3.0, "white on $green is ${"%.2f".format(ratio)}:1, under 3:1")
        }
    }

    @Test
    fun `the lens darkens toward its core, as the orange mark does`() {
        assertTrue(luminance(Fixed.PaidLight) > luminance(Fixed.PaidMid), "the light facets are lighter than the middle ones")
        assertTrue(luminance(Fixed.PaidMid) > luminance(Fixed.Paid), "and the middle ones lighter than the core")
    }

    @Test
    fun `the ink segment reads on the track it chases round`() {
        val ratio = contrast(Fixed.Ink, Fixed.ChaseTrack)
        assertTrue(ratio >= 3.0, "ink on the chase track is ${"%.2f".format(ratio)}:1, under 3:1")
        assertTrue(contrast(Fixed.ChaseTrack, Fixed.White) < 3.0, "the track is pale: it is not itself a mark on the white glass")
    }
}
