package app.monokular

import androidx.compose.ui.unit.dp
import app.monokular.ui.CodeCornerShare
import app.monokular.ui.codeSide
import kotlin.test.Test
import kotlin.test.assertEquals

/** Board 8: the sale's code card on a phone. */
class CodeSideTest {
    @Test
    fun `the code card is the window less 20 dp a side, up to 372 dp`() {
        assertEquals(372.dp, codeSide(412.dp), "the design's phone")
        assertEquals(320.dp, codeSide(360.dp), "a narrow phone gives its width")
        assertEquals(372.dp, codeSide(560.dp), "a wider window keeps the cap")
    }

    @Test
    fun `the hero's circle opens from the corners of the card at its full size`() {
        assertEquals(32f / 372f, CodeCornerShare, 1e-6f)
    }

}
