package app.monokular

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A shared surface's course over a move (decision 175): where it is at a
 * point of the move, and the parts of the move over which it holds or has
 * cleared a text run, which is when words on it or under it may show
 * (decision 176).
 */
class CourseTest {
    private val row = floatArrayOf(0f, 100f, 100f, 120f)
    private val screen = floatArrayOf(0f, 0f, 100f, 200f)

    private fun course(from: FloatArray, to: FloatArray, p: Float = 0f) =
        Course("sale/a1").apply { line(from[0], from[1], from[2], from[3], to[0], to[1], to[2], to[3], p) }

    @Test
    fun itIsTheStraightLineBetweenItsEndsAtTheStagesProgress() {
        val c = course(row, screen, 0.25f)
        assertEquals(0f, c.left)
        assertEquals(75f, c.top)
        assertEquals(100f, c.right)
        assertEquals(140f, c.bottom)
    }

    @Test
    fun aGrowingGroundHoldsAWordOnlyOnceItsEdgeHasPassedIt() {
        val c = course(row, screen)
        // A word under the row, on the sale: the ground's bottom reaches 169.5 (170 less half a pixel) at 0.61875 of the move.
        c.holding(10f, 150f, 90f, 170f)
        assertEquals(0.61875f, c.heldFrom, 1e-4f)
        assertEquals(1f, c.heldUntil)
        // The row's own words: held from the first frame to the last.
        c.holding(10f, 102f, 90f, 118f)
        assertEquals(0f, c.heldFrom)
        assertEquals(1f, c.heldUntil)
    }

    @Test
    fun aShrinkingGroundLetsGoOfAWordAsItsEdgePassesIt() {
        val c = course(screen, row)
        c.holding(10f, 150f, 90f, 170f)
        assertEquals(0f, c.heldFrom)
        assertEquals(0.38125f, c.heldUntil, 1e-4f)
    }

    @Test
    fun aWordAShrinkingGroundNeverHeldIsNeverHeld() {
        val c = course(row, row)
        c.holding(10f, 150f, 90f, 170f)
        assertTrue(c.heldFrom > c.heldUntil, "never held: ${c.heldFrom}..${c.heldUntil}")
    }

    @Test
    fun wordsUnderAShrinkingCardShowOnceItHasPassedThem() {
        // The enlarged code's white shrinking back into its card, a word below the card.
        val c = course(screen, floatArrayOf(20f, 50f, 80f, 110f))
        assertEquals(50f / 90f, c.clearOf(10f, 150f, 90f, 170f), 1e-4f)
        // A word the card still covers at rest is never clear of it.
        assertEquals(2f, c.clearOf(30f, 60f, 70f, 100f))
        // One it never met is clear from the start.
        assertEquals(0f, course(row, row).clearOf(10f, 150f, 90f, 170f))
    }

    @Test
    fun itHoldsABoxWithinHalfAPixel() {
        val c = course(row, screen, 1f)
        assertTrue(c.holds(0.4f, 0f, 100f, 200.4f))
        assertTrue(!c.holds(-1f, 0f, 100f, 200f))
    }
}
