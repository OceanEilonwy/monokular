package app.monokular

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A shared surface's course over a move (decision 232): where it is at a
 * point of the move, and the parts of the move over which it holds or has
 * cleared a text run, which is when words on it or under it may show
 * (decision 233).
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
        val under = c.holding(10f, 150f, 90f, 170f)
        assertEquals(0.61875f, Course.heldFrom(under), 1e-4f)
        assertEquals(1f, Course.heldUntil(under))
        // The row's own words: held from the first frame to the last.
        val own = c.holding(10f, 102f, 90f, 118f)
        assertEquals(0f, Course.heldFrom(own))
        assertEquals(1f, Course.heldUntil(own))
    }

    @Test
    fun aShrinkingGroundLetsGoOfAWordAsItsEdgePassesIt() {
        val held = course(screen, row).holding(10f, 150f, 90f, 170f)
        assertEquals(0f, Course.heldFrom(held))
        assertEquals(0.38125f, Course.heldUntil(held), 1e-4f)
    }

    @Test
    fun aWordAShrinkingGroundNeverHeldIsNeverHeld() {
        val held = course(row, row).holding(10f, 150f, 90f, 170f)
        assertTrue(Course.heldFrom(held) > Course.heldUntil(held), "never held: ${Course.heldFrom(held)}..${Course.heldUntil(held)}")
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

    /**
     * A live sale's code flying home on a Back (board 6): its card, a 264 square, scaled as one to its course from the
     * card to the row's 22 dp glyph, square on every frame, centred, and the glyph's size as it lands.
     */
    @Test
    fun theCodeFlyingHomeIsOneSquareFittedToItsCourse() {
        val card = floatArrayOf(74f, 180f, 338f, 444f)
        val glyph = floatArrayOf(366f, 610f, 388f, 632f)
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val c = course(card, glyph, p)
            val s = c.fit(264f, 264f)
            assertEquals((c.right - c.left) / 264f, s, 1e-5f)
            assertEquals(c.left, c.fitLeft(264f, s), 1e-3f)
            assertEquals(c.top, c.fitTop(264f, s), 1e-3f)
        }
        val home = course(card, glyph, 1f)
        assertEquals(22f / 264f, home.fit(264f, 264f), 1e-6f)
        assertEquals(1f, course(card, glyph, 0f).fit(264f, 264f))
    }

    @Test
    fun aCodeIsNeverSquashedIntoABoxOfAnotherShape() {
        // A glyph laid out wider than tall: the code is the height's square, centred across it.
        val c = course(floatArrayOf(0f, 0f, 100f, 100f), floatArrayOf(200f, 200f, 240f, 220f), 1f)
        val s = c.fit(100f, 100f)
        assertEquals(0.2f, s, 1e-6f)
        assertEquals(210f, c.fitLeft(100f, s), 1e-4f)
        assertEquals(200f, c.fitTop(100f, s), 1e-4f)
    }

    /**
     * A flight taken over (a back gesture cancelled 40 % of the way home, then a Back again on the way back) goes on
     * from where the code was drawn: its course snaps there and runs to the end it now goes to, and how far it is on
     * its way ([Course.awayAt], what the sale's ground and words fade by) goes on from the same point, so the code's box
     * is on its first line at that share on every frame and nothing it reads jumps.
     */
    @Test
    fun aFlightTakenOverGoesOnFromWhereTheCodeWasDrawn() {
        val card = floatArrayOf(74f, 180f, 338f, 444f)
        val glyph = floatArrayOf(366f, 610f, 388f, 632f)
        val onSale = End("home/a1", Screen.Sale("a1"), Move.RowGrow)
        val onShift = End("home/a1", Screen.Till, Move.RowGrow)
        val c = Course("home/a1")
        fun go(from: FloatArray, to: FloatArray, p: Float, generation: Long, interrupted: Boolean) = c.place(
            if (from === card) onSale else onShift, if (to === card) onSale else onShift,
            from[0], from[1], from[2], from[3], to[0], to[1], to[2], to[3], p, generation, interrupted,
        )
        fun assertOnFirstLine(share: Float, at: String) {
            assertEquals(card[0] + (glyph[0] - card[0]) * share, c.left, 1e-3f, "$at left")
            assertEquals(card[1] + (glyph[1] - card[1]) * share, c.top, 1e-3f, "$at top")
            assertEquals(card[2] + (glyph[2] - card[2]) * share, c.right, 1e-3f, "$at right")
            assertEquals(card[3] + (glyph[3] - card[3]) * share, c.bottom, 1e-3f, "$at bottom")
        }
        go(card, glyph, 0.4f, generation = 1, interrupted = false)
        assertEquals(0.4f, c.awayAt(0.4f, homeward = true), 1e-6f)
        assertFalse(c.tookOver)
        // Cancelled: back toward the card from 0 of a new change.
        c.awayFrom = c.away
        for (p in listOf(0f, 0.25f, 0.5f)) {
            go(glyph, card, p, generation = 2, interrupted = true)
            assertTrue(c.tookOver)
            assertOnFirstLine(c.awayAt(p, homeward = false), "back at $p")
        }
        assertEquals(0.2f, c.away, 1e-6f)
        // Home again from there.
        c.awayFrom = c.away
        for (p in listOf(0f, 0.5f, 1f)) {
            go(card, glyph, p, generation = 3, interrupted = true)
            assertOnFirstLine(c.awayAt(p, homeward = true), "home again at $p")
        }
        assertEquals(1f, c.away, 1e-6f)
    }

    /**
     * A course (the code's bitmap among what it drew between) lasts only
     * while one of its ends is on a screen: a key is a sale's, so the stage
     * would otherwise hold a course and a code for every sale ever opened,
     * and walk them all on every frame of a move.
     */
    @Test
    fun theStageKeepsACourseOnlyWhileOneOfItsEndsIsOnAScreen() {
        // Three sales' screens opened and left, each with its row, its code and its order card: ends come and go.
        val courses = Courses()
        val screens = listOf(Screen.Till, Screen.Sale("a1"))
        for (sale in listOf("a1", "a2", "a3")) {
            val ends = listOf("sale/$sale", "code/$sale", "order/$sale").flatMap { key -> screens.map { End(key, it, Move.RowGrow) } }
            ends.forEach(courses::add)
            assertEquals(3, courses.size(), "one course per surface on screen")
            ends.forEach(courses::remove)
            assertEquals(0, courses.size(), "nothing left of $sale's surfaces")
            assertEquals(null, courses.find("code/$sale"))
        }
        // One end left of a surface keeps its course, and the course is found by key.
        val left = End("sale/a4", Screen.Till, Move.RowGrow)
        courses.add(left)
        courses.add(End("sale/a4", Screen.Sale("a4"), Move.RowGrow))
        courses.remove(left)
        assertEquals(1, courses.size())
        assertEquals(Move.RowGrow, courses.find("sale/a4")?.during)
    }
}
