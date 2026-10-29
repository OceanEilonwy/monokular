package app.monokular.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.monokular.SaleText
import app.monokular.core.SalePhase

/** How much of the ring a phase fills: a quarter a step, from the top, clockwise. */
fun ringFill(phase: SalePhase): Float = SaleText.step(phase) / 4f

/**
 * The design's phase ring (its Chips and status rows): an open ring whose
 * [fill] arc grows a quarter a step, Waiting to Paid, with a pupil in the
 * middle when [pupil]. Decoration: the words beside it say the phase.
 *
 * A step sweeps (`docs/motion.md` M6, `motion.sweep`): the arc grows from
 * where it is, so a second step mid-sweep just carries on, and it closes
 * in [paid] once the sale is paid. The first composition draws the phase's
 * own fill, so a ring that appears at Seen doesn't sweep in. Both animated
 * values are read only in the draw lambda: a step redraws the ring and
 * recomposes nothing, and nothing is allocated per frame.
 */
@Composable
fun PhaseRing(
    phase: SalePhase,
    size: Dp,
    modifier: Modifier = Modifier,
    track: Color = mk.chip,
    fill: Color = mk.accent,
    pupil: Boolean = false,
    /** The closed ring's colour: the theme's, or the design's over another app (the bubble). */
    paid: Color = mk.paid,
) {
    val m = motion
    val fraction by animateFloatAsState(ringFill(phase), m.sweep.fraction, label = "ring")
    val arc by animateColorAsState(if (phase == SalePhase.Paid) paid else fill, m.fade.spec(), label = "arc")
    val density = LocalDensity.current
    val strokes = remember(size, density) { RingStrokes(with(density) { size.toPx() }) }
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val inset = strokes.inset
        val arcSize = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
        val topLeft = Offset(inset, inset)
        drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = strokes.track)
        val swept = fraction
        if (swept > 0f) drawArc(arc, -90f, 360f * swept, useCenter = false, topLeft = topLeft, size = arcSize, style = strokes.arc)
        if (pupil) drawCircle(arc, radius = this.size.minDimension * 0.11f)
    }
}

/** The ring's two strokes at one size, made once rather than on every frame of a sweep. */
private class RingStrokes(side: Float) {
    private val width = side * 0.115f
    val inset = width / 2 + side * 0.07f
    val track = Stroke(width)
    val arc = Stroke(width, cap = StrokeCap.Round)
}

/** The ring at the design's row size. */
val RingRowSize = 36.dp
