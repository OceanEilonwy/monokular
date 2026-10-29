package app.monokular

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.monokular.RunningMoves.Companion.flashes
import app.monokular.RunningMoves.Companion.lum
import app.monokular.RunningMoves.Companion.range
import app.monokular.core.SalePhase
import app.monokular.ui.Motion
import app.monokular.ui.codeGround
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertTrue

/**
 * Every screen change run as the device runs it (frame by frame under the
 * one driver, [RunningMoves]), in light and dark, both ways, and checked on
 * every frame (decisions 146 to 149): one move from the first frame to the
 * last on both screens; the code at full alpha on its white; no glyph cut by
 * a ground's edge; and in the dark theme no flash. The seeked frames
 * ([MotionFramesTest]) hold the stage, the match and the overlay in one
 * state, which a running move doesn't.
 */
class RunningMovesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val moves = RunningMoves(compose, testSale("a1", expiresAt = 1_600).copy(receipt = sampleReceipt()))

    @Test fun aRowGrowsIntoItsSaleLight() = rowGrow(dark = false)

    @Test fun aRowGrowsIntoItsSaleDark() = rowGrow(dark = true)

    @Test fun aSaleShrinksIntoItsRowLight() = rowShrink(dark = false)

    @Test fun aSaleShrinksIntoItsRowDark() = rowShrink(dark = true)

    @Test fun theCodeGrowsLight() = codeGrow(dark = false)

    @Test fun theCodeGrowsDark() = codeGrow(dark = true)

    @Test fun theCodeShrinksLight() = codeShrink(dark = false)

    @Test fun theCodeShrinksDark() = codeShrink(dark = true)

    @Test fun theOrderGrowsLight() = orderGrow(dark = false)

    @Test fun theOrderGrowsDark() = orderGrow(dark = true)

    @Test fun theOrderShrinksLight() = orderShrink(dark = false)

    @Test fun theOrderShrinksDark() = orderShrink(dark = true)

    @Test fun aSaleRisesLight() = rise(dark = false)

    @Test fun aSaleRisesDark() = rise(dark = true)

    @Test fun thePaidFoldLight() = paidFold(dark = false)

    @Test fun thePaidFoldDark() = paidFold(dark = true)

    @Test fun theCodeGrowsUnderStillLight() = stillEnlarge(dark = false)

    @Test fun theCodeGrowsUnderStillDark() = stillEnlarge(dark = true)

    private fun rowGrow(dark: Boolean) {
        moves.start(Screen.Till, dark)
        check("row-grow", dark, moves.run(Screen.Sale("a1"), groundKey = "sale/a1"), Move.RowGrow, glyphs = true, grows = true)
    }

    private fun rowShrink(dark: Boolean) {
        moves.start(Screen.Sale("a1"), dark)
        // The shift's other rows stand still under the shrinking ground, opaque: a card going over them, not a fade.
        check("row-shrink", dark, moves.run(Screen.Till, groundKey = "sale/a1"), Move.RowGrow, glyphs = false, grows = true)
    }

    private fun codeGrow(dark: Boolean) {
        moves.start(Screen.Sale("a1"), dark)
        val frames = moves.run(Screen.Code("a1"), groundKey = codeGround("a1"))
        check("code-grow", dark, frames, Move.CodeGrow, glyphs = true, grows = true)
    }

    private fun codeShrink(dark: Boolean) {
        moves.start(Screen.Code("a1"), dark)
        val frames = moves.run(Screen.Sale("a1"), groundKey = codeGround("a1"))
        check("code-shrink", dark, frames, Move.CodeGrow, glyphs = true, grows = true)
    }

    private fun orderGrow(dark: Boolean) {
        moves.start(Screen.Sale("a1"), dark)
        check("order-grow", dark, moves.run(Screen.Order("a1"), groundKey = "order/a1"), Move.OrderGrow, glyphs = true, grows = true)
    }

    private fun orderShrink(dark: Boolean) {
        moves.start(Screen.Order("a1"), dark)
        check("order-shrink", dark, moves.run(Screen.Sale("a1"), groundKey = "order/a1"), Move.OrderGrow, glyphs = true, grows = true)
    }

    /** A paid sale's page back into its row: the hero becomes the row's badge, the ground shrinks without a hold. */
    @Test fun aPaidSaleStepsBackIntoItsRowLight() = paidStepBack(dark = false)

    @Test fun aPaidSaleStepsBackIntoItsRowDark() = paidStepBack(dark = true)

    private fun paidStepBack(dark: Boolean) {
        moves.shown = moves.sale.copy(phase = SalePhase.Paid, paidAt = 1_100)
        moves.start(Screen.Sale("a1"), dark)
        check("paid-step-back", dark, moves.run(Screen.Till, groundKey = "sale/a1"), Move.RowGrow, glyphs = false, grows = true)
    }

    private fun rise(dark: Boolean) {
        moves.start(Screen.Till, dark)
        check("rise", dark, moves.run(Screen.Sale("a1"), byItself = true), Move.Rise, glyphs = false)
    }

    private fun stillEnlarge(dark: Boolean) {
        moves.start(Screen.Sale("a1"), dark, Motion.Still)
        check("still-grow", dark, moves.run(Screen.Code("a1")), move = null, glyphs = false)
    }

    /**
     * The paid fold: the hero's centre travels from the card's centre to the page's hero without standing still
     * while the fold runs, and the page's words come in only once it is past halfway (decision 131).
     */
    private fun paidFold(dark: Boolean) {
        moves.start(Screen.Sale("a1"), dark)
        val frames = moves.runFor(FOLD_MS, { moves.shown = moves.sale.copy(phase = SalePhase.Paid, paidAt = 1_100) })
        val failures = mutableListOf<String>()
        if (dark) failures += flashes(frames)
        val card = frames.first().codes.first()
        val path = frames.mapNotNull { f -> f.hero?.let { f to it.center } }
        val rest = path.last().second
        val from = card.center
        val whole = hypot(rest.x - from.x, rest.y - from.y)
        var still = 0
        var last = from
        var halfway: Int? = null
        var started = false
        for ((f, c) in path) {
            val step = hypot(c.x - last.x, c.y - last.y)
            val left = hypot(rest.x - c.x, rest.y - c.y)
            // Counted from its first step (a change composes its new face on one frame and starts its clock on the
            // next, as every Compose animation does), until it is within a few pixels of rest (the spring's tail).
            if (step >= 0.5f) started = true
            if (started && left > NEAR && step < 0.5f) still++ else still = 0
            if (still > 1) failures += "f${f.index} the hero stands still at $c, ${"%.0f".format(left)} px from its place"
            if (hypot(c.x - last.x, c.y - last.y) > 0 && hypot(rest.x - last.x, rest.y - last.y) + 1f < left) failures += "f${f.index} the hero moves away from its place"
            if (halfway == null && left <= whole / 2) halfway = f.index
            last = c
        }
        val before = frames.first().bitmap
        // The heading's place is under the card as the fold begins: its ink counts once the hero has left it.
        val firstWords = frames.firstOrNull { f ->
            val r = f.heading
            r != null && f.hero?.overlaps(r) != true && ink(f.bitmap, r) && changed(f.bitmap, before, r)
        }?.index
        if (halfway != null && firstWords != null && firstWords < halfway) failures += "the page's words show at f$firstWords, before the hero is halfway at f$halfway"
        report("paid-fold-${theme(dark)}", frames, failures)
    }

    /** Every check a running [move] keeps, on each of its [frames]. */
    private fun check(name: String, dark: Boolean, frames: List<RunningMoves.Frame>, move: Move?, glyphs: Boolean, grows: Boolean = false) {
        val failures = mutableListOf<String>()
        val moving = frames.drop(1).dropLast(RunningMoves.SETTLE_FRAMES)
        if (move != null) {
            for (f in moving) {
                val said = f.moves.filterNotNull()
                if (said.isEmpty()) failures += "f${f.index}: no screen says a move is under way before the stage lands"
                if (said.any { it != move }) failures += "f${f.index}: the screens disagree on the move: $said, not $move"
            }
        }
        // The ground is drawn through its clip on every frame of its move but the first and the last, where the match
        // may not be made yet, or already gone (decisions 146, 149): it never gives up half way.
        if (grows) {
            val shaped = moving.indices.filter { moving[it].ground != null }
            if (shaped.isEmpty()) failures += "the moving ground never has a shape"
            else for (i in shaped.first()..shaped.last()) if (moving[i].ground == null) failures += "f${moving[i].index}: the moving ground has no shape mid-move"
        }
        for (f in frames) failures += if (move == Move.CodeGrow) codeOnWhite(f) else codeNotGrey(f)
        if (glyphs) for (f in moving) failures += cutGlyphs(f, frames.first().bitmap)
        if (grows) failures += groundTravels(frames)
        if (dark) failures += flashes(frames)
        report("$name-${theme(dark)}", frames, failures)
    }

    private fun report(name: String, frames: List<RunningMoves.Frame>, failures: List<String>) {
        val bad = failures.mapNotNull { Regex("^f(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }.toSet()
        frames.filter { it.index in bad || it.index == 0 || it === frames.last() }.take(12).forEach { RunningMoves.save(it, name) }
        assertTrue(failures.isEmpty(), "$name:\n" + failures.distinct().take(40).joinToString("\n"))
    }

    /**
     * The enlarge's white on [f], where its track says it is drawn (the code moves in the overlay, away from where
     * either side laid it out): white just inside each edge (the ground is drawn where its bounds are, decision 146),
     * and nothing grey inside it (the code on it whole, never through a fade or two codes through each other).
     */
    private fun codeOnWhite(f: RunningMoves.Frame): List<String> {
        val g = f.ground ?: return emptyList()
        val b = f.bitmap
        val inset = 6f
        val out = listOf(g.left + inset to g.center.y, g.right - inset to g.center.y, g.center.x to g.top + inset, g.center.x to g.bottom - inset)
            .filter { (x, y) -> x.toInt() in 0 until b.width && y.toInt() in 0 until b.height }
            .filter { (x, y) -> lum(b.getPixel(x.toInt(), y.toInt())) < WHITE }
            .map { (x, y) -> "f${f.index}: the code's white at $g is not drawn at ($x, $y)" }
            .toMutableList()
        greyShare(b, g)?.let { if (it > GREY_SHARE) out += "f${f.index}: the code on its white at $g is grey, drawn below full alpha (${(100 * it).toInt()} % grey)" }
        return out
    }

    /** How much of [r] in [b] is neither ink nor white: a code through a fade. Null if [r] is off the picture. */
    private fun greyShare(b: Bitmap, r: Rect): Float? {
        var grey = 0
        var n = 0
        for (y in r.top.toInt().coerceAtLeast(0) until r.bottom.toInt().coerceAtMost(b.height) step 2) {
            for (x in r.left.toInt().coerceAtLeast(0) until r.right.toInt().coerceAtMost(b.width) step 2) {
                if (lum(b.getPixel(x, y)) in GREY) grey++
                n++
            }
        }
        return if (n == 0) null else grey.toFloat() / n
    }

    /** The arriving screen's code on [f], where it is laid out: never grey, as a code drawn through a fade is (decision 148). */
    private fun codeNotGrey(f: RunningMoves.Frame): List<String> = f.codes.filter { it.width > 20f }.mapNotNull { code ->
        greyShare(f.bitmap, code)?.takeIf { it > GREY_SHARE }?.let { "f${f.index}: the code at $code is grey, drawn below full alpha (${(100 * it).toInt()} % grey)" }
    }

    /**
     * No arriving text run on [f] half under the moving ground's edge: ink on the side away from the ground and none
     * under it (decision 147). Words on the ground, clipped to it, are the other way about, and stay.
     */
    private fun cutGlyphs(f: RunningMoves.Frame, first: Bitmap): List<String> {
        val g = f.ground ?: return emptyList()
        val out = mutableListOf<String>()
        for ((text, t) in f.texts) {
            if (t.width < 4f || t.height < 4f || !t.overlaps(g)) continue
            val (under, beside) = split(t, g) ?: continue
            if (ink(f.bitmap, beside) && changed(f.bitmap, first, beside) && !ink(f.bitmap, under)) out += "f${f.index}: \"$text\" at $t is cut by the ground's edge at $g"
        }
        return out
    }

    /**
     * [t] split by the first of [g]'s edges that crosses it, inside and outside, each kept [EDGE] off the edge (the
     * ground's own edge is a contrast too); null if none crosses by more than a sliver.
     */
    private fun split(t: Rect, g: Rect): Pair<Rect, Rect>? {
        val sliver = 4f + EDGE
        return when {
            g.top > t.top + sliver && g.top < t.bottom - sliver -> Rect(t.left, g.top + EDGE, t.right, t.bottom) to Rect(t.left, t.top, t.right, g.top - EDGE)
            g.bottom > t.top + sliver && g.bottom < t.bottom - sliver -> Rect(t.left, t.top, t.right, g.bottom - EDGE) to Rect(t.left, g.bottom + EDGE, t.right, t.bottom)
            g.left > t.left + sliver && g.left < t.right - sliver -> Rect(g.left + EDGE, t.top, t.right, t.bottom) to Rect(t.left, t.top, g.left - EDGE, t.bottom)
            g.right > t.left + sliver && g.right < t.right - sliver -> Rect(t.left, t.top, g.right - EDGE, t.bottom) to Rect(g.right + EDGE, t.top, t.right, t.bottom)
            else -> null
        }
    }

    /** Whether [region] of [a] is not as it was in [b], the frame the move began on: words arrived there, not the leaving screen's. */
    private fun changed(a: Bitmap, b: Bitmap, region: Rect): Boolean {
        var diff = 0.0
        var n = 0
        for (y in region.top.toInt().coerceAtLeast(0) until region.bottom.toInt().coerceAtMost(a.height) step 2) {
            for (x in region.left.toInt().coerceAtLeast(0) until region.right.toInt().coerceAtMost(a.width) step 2) {
                diff += abs(lum(a.getPixel(x, y)) - lum(b.getPixel(x, y)))
                n++
            }
        }
        return n > 0 && diff / n > CHANGED
    }

    /** Whether [region] of [bitmap] shows words: a contrast in it a glyph would make. */
    private fun ink(bitmap: Bitmap, region: Rect): Boolean {
        val (lo, hi) = range(bitmap, region)
        return hi - lo > INK
    }

    /**
     * The moving ground goes from its first size to its last without turning back or standing still on the way:
     * its area only grows on a grow and only shrinks on a shrink, and no two frames running leave it where it was
     * while it is still short of its place (a ground that gives up for a frame, a dissolve that restarts, a hold
     * then a jump: decisions 146, 149 and round 3's review).
     */
    private fun groundTravels(frames: List<RunningMoves.Frame>): List<String> {
        val areas = frames.mapNotNull { f -> f.ground?.let { f.index to it.width * it.height } }
        if (areas.size < 3) return emptyList()
        val first = areas.first().second
        val last = areas.last().second
        val whole = abs(last - first)
        if (whole < 1f) return emptyList()
        val grows = last > first
        val out = mutableListOf<String>()
        var still = 0
        for (i in 1 until areas.size) {
            val (index, area) = areas[i]
            val step = area - areas[i - 1].second
            if (if (grows) step < -whole * TURN else step > whole * TURN) out += "f$index: the moving ground turns back (${"%.0f".format(step)} px²)"
            if (abs(step) <= whole * TURN && abs(last - area) > whole * NEAR_SHARE) still++ else still = 0
            if (still > 1) out += "f$index: the moving ground stands still ${"%.0f".format(abs(last - area))} px² short of its place"
        }
        return out
    }

    private fun theme(dark: Boolean) = if (dark) "dark" else "light"

    private companion object {
        /** How close to rest the hero's spring is done: a pixel or two of tail. */
        const val NEAR = 4f

        /** A region whose mean luminance moved this much from the move's first frame has something new in it. */
        const val CHANGED = 6f

        /** The paid fold and the hero's settling, with room. */
        const val FOLD_MS = 900L

        /** Neither a module nor the white round it: a code through a fade, over either theme's ground. */
        val GREY = 80f..200f

        /** More of a code's square than this grey is a code drawn through a fade; its edges' anti-aliasing is far less. */
        const val GREY_SHARE = 0.25f

        /** The white round a code, and its ground: nothing darker. */
        const val WHITE = 235f

        /** A glyph's contrast against what it is drawn on. */
        const val INK = 60f

        /** A step of the ground's area this small, as a share of its whole change, is no step: a spring's rounding. */
        const val TURN = 0.002f

        /** Within this share of its whole change the ground is at its place: the spring's tail. */
        const val NEAR_SHARE = 0.02f

        /** How far off a ground's edge a glyph's ink is looked for: the ground's own edge is a contrast too. */
        const val EDGE = 3f
    }
}
