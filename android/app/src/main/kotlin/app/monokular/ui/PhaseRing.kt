package app.monokular.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
 */
@Composable
fun PhaseRing(
    phase: SalePhase,
    size: Dp,
    modifier: Modifier = Modifier,
    track: Color = mk.chip,
    fill: Color = mk.accent,
    pupil: Boolean = false,
) {
    val fraction = ringFill(phase)
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val stroke = this.size.minDimension * 0.115f
        val inset = stroke / 2 + this.size.minDimension * 0.07f
        val arcSize = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
        val topLeft = Offset(inset, inset)
        drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
        if (fraction > 0f) {
            drawArc(fill, -90f, 360f * fraction, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (pupil) drawCircle(fill, radius = this.size.minDimension * 0.11f)
    }
}

/** The ring at the design's row size. */
val RingRowSize = 36.dp
