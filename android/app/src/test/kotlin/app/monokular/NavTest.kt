package app.monokular

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavTest {
    private fun move(from: Screen, to: Screen, byItself: Boolean = false, still: Boolean = false) = Nav.move(from, to, byItself, still)

    @Test
    fun `a tapped row grows into its sale, and the sale shrinks back into it`() {
        assertEquals(Move.RowGrow, move(Screen.Till, Screen.Sale("a")))
        assertEquals(Move.RowGrow, move(Screen.Sale("a"), Screen.Till))
        assertEquals(Move.RowGrow, move(Screen.Sale("a"), Screen.Till, byItself = true), "a paid sale stepping back settles into its row")
    }

    @Test
    fun `a sale that came up by itself rises, and so does one coming up in another's place`() {
        assertEquals(Move.Rise, move(Screen.Till, Screen.Sale("a"), byItself = true))
        assertEquals(Move.Rise, move(Screen.Sale("a"), Screen.Sale("b"), byItself = true))
    }

    @Test
    fun `a sale's code and order grow out of their cards, and only their own sale's`() {
        assertEquals(Move.CodeGrow, move(Screen.Sale("a"), Screen.Code("a")))
        assertEquals(Move.CodeGrow, move(Screen.Code("a"), Screen.Sale("a"), byItself = true), "a code going down when it's paid")
        assertEquals(Move.OrderGrow, move(Screen.Sale("a"), Screen.Order("a")))
        assertEquals(Move.OrderGrow, move(Screen.Order("a"), Screen.Sale("a")))
        assertEquals(Move.Crossfade, move(Screen.Code("a"), Screen.Till), "leaving from the code: no card to go back into")
    }

    @Test
    fun `Settings fades through, from anywhere and back`() {
        assertEquals(Move.FadeThrough, move(Screen.Till, Screen.Settings))
        assertEquals(Move.FadeThrough, move(Screen.Settings, Screen.Sale("a")))
        assertEquals(Move.FadeThrough, move(Screen.Settings, Screen.Welcome), "disconnecting from Settings")
    }

    @Test
    fun `onboarding slides forward through its steps and back the other way`() {
        assertEquals(Move.Slide(forward = true), move(Screen.Welcome, Screen.Scan))
        assertEquals(Move.Slide(forward = false), move(Screen.Scan, Screen.Welcome))
        assertEquals(Move.Slide(forward = true), move(Screen.Float, Screen.Ready))
        assertEquals(Move.Slide(forward = true), move(Screen.Ready, Screen.Till), "starting the first shift")
        assertEquals(Move.Slide(forward = false), move(Screen.Alerts, Screen.PointOfSale))
    }

    @Test
    fun `under Still every pair is a crossfade`() {
        for ((from, to) in listOf(
            Screen.Till to Screen.Sale("a"), Screen.Sale("a") to Screen.Code("a"), Screen.Sale("a") to Screen.Order("a"),
            Screen.Till to Screen.Settings, Screen.Welcome to Screen.Scan,
        )) {
            assertEquals(Move.Crossfade, move(from, to, still = true), "$from to $to")
        }
    }

    @Test
    fun `back is toward the shift, and backwards through setup`() {
        assertTrue(Nav.isBack(Screen.Sale("a"), Screen.Till))
        assertTrue(Nav.isBack(Screen.Code("a"), Screen.Sale("a")))
        assertTrue(Nav.isBack(Screen.Settings, Screen.Till))
        assertTrue(Nav.isBack(Screen.Scan, Screen.Welcome))
        assertFalse(Nav.isBack(Screen.Till, Screen.Sale("a")))
        assertFalse(Nav.isBack(Screen.Sale("a"), Screen.Order("a")))
        assertFalse(Nav.isBack(Screen.Welcome, Screen.Scan))
    }
}
