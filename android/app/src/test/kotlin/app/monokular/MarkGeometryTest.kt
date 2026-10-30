package app.monokular

import app.monokular.ui.Fixed
import app.monokular.ui.LensLook
import app.monokular.ui.MarkGeometry
import app.monokular.ui.Motion
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mark's rings, border arc and greens (`docs/motion.md` §12.5, §13.5):
 * the facets' list order is the lens's rings from the core out, which the
 * bubble's spread colours one after another, so the list must stay the
 * SVG's 1 + 6 + 12 + 12 and each ring one shade of it.
 */
class MarkGeometryTest {
    @Test
    fun `the facets are the core, the inner six, the middle twelve and the rim twelve, in that order`() {
        val facets = MarkGeometry.facets
        assertEquals(31, facets.size)
        val sizes = facets.indices.groupingBy(MarkGeometry::ring).eachCount()
        assertEquals(mapOf(0 to 1, 1 to 6, 2 to 12, 3 to 12), sizes)
        assertEquals(facets.indices.map(MarkGeometry::ring), facets.indices.map(MarkGeometry::ring).sorted(), "rings run core to rim")
        assertEquals(Motion.SPREAD_RINGS, MarkGeometry.RINGS, "the spread has a preset for each ring")
    }

    @Test
    fun `each ring is the one shade the SVG gives its facets, the base circle the middle twelve's`() {
        MarkGeometry.facets.forEachIndexed { i, (_, shade) ->
            assertEquals(shade, MarkGeometry.shade(MarkGeometry.ring(i)), "facet $i")
        }
        assertEquals(0, MarkGeometry.shade(MarkGeometry.BASE_RING), "the base circle under the facets is the light shade, as the SVG draws it")
    }

    @Test
    fun `the chasing segment is the canvas's 30 percent of the border, from 12 o'clock`() {
        assertEquals(0.30f, MarkGeometry.ARC_SHARE)
        assertEquals(-90f, MarkGeometry.ARC_START, "12 o'clock in the draw's angles, clockwise from 3")
        val border = 2 * Math.PI * MarkGeometry.BORDER_RADIUS
        assertEquals(34.0, border * MarkGeometry.ARC_SHARE, 0.1, "34 of the border's 113.1 u, as the canvas's dash")
    }

    @Test
    fun `the green lens is the fixed greens, the core darkest`() {
        assertEquals(Fixed.Paid, LensLook.Green[0])
        assertEquals(Fixed.PaidMid, LensLook.Green[1])
        assertEquals(Fixed.PaidLight, LensLook.Green[2])
        assertEquals(Fixed.PaidMid, LensLook.Green[3])
    }
}
