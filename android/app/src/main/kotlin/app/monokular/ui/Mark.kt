package app.monokular.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/**
 * The Monokulo mark (the design's Logo and LoadingMark parts): an orange
 * lens of facets in a ring, drawn from the same paths as the web's SVG.
 */
private object MarkPaths {
    private fun p(d: String): Path = PathParser().parsePathString(d).toPath()

    /** Each facet and its shade: 0 #ff6600, 1 #e85d00, 2 #c24e00, in the SVG's order. */
    val facets: List<Pair<Path, Int>> = listOf(
        "M30,23L34.33,25.5L34.33,30.5L30,33L25.67,30.5L25.67,25.5Z" to 2,
        "M30,23L34.33,25.5L35.5,18.47Z" to 1,
        "M34.33,25.5L34.33,30.5L41,28Z" to 1,
        "M34.33,30.5L30,33L35.5,37.53Z" to 1,
        "M30,33L25.67,30.5L24.5,37.53Z" to 1,
        "M25.67,30.5L25.67,25.5L19,28Z" to 1,
        "M25.67,25.5L30,23L24.5,18.47Z" to 1,
        "M30,23L24.5,18.47L30,4Z" to 0,
        "M30,23L30,4L35.5,18.47Z" to 0,
        "M34.33,25.5L35.5,18.47L50.78,16Z" to 0,
        "M34.33,25.5L50.78,16L41,28Z" to 0,
        "M34.33,30.5L41,28L50.78,40Z" to 0,
        "M34.33,30.5L50.78,40L35.5,37.53Z" to 0,
        "M30,33L35.5,37.53L30,52Z" to 0,
        "M30,33L30,52L24.5,37.53Z" to 0,
        "M25.67,30.5L24.5,37.53L9.22,40Z" to 0,
        "M25.67,30.5L9.22,40L19,28Z" to 0,
        "M25.67,25.5L19,28L9.22,16Z" to 0,
        "M25.67,25.5L9.22,16L24.5,18.47Z" to 0,
        "M35.5,18.47L30,4L42,7.22Z" to 1,
        "M35.5,18.47L42,7.22L50.78,16Z" to 1,
        "M41,28L50.78,16L54,28Z" to 1,
        "M41,28L54,28L50.78,40Z" to 1,
        "M35.5,37.53L50.78,40L42,48.78Z" to 1,
        "M35.5,37.53L42,48.78L30,52Z" to 1,
        "M24.5,37.53L30,52L18,48.78Z" to 1,
        "M24.5,37.53L18,48.78L9.22,40Z" to 1,
        "M19,28L9.22,40L6,28Z" to 1,
        "M19,28L6,28L9.22,16Z" to 1,
        "M24.5,18.47L9.22,16L18,7.22Z" to 1,
        "M24.5,18.47L18,7.22L30,4Z" to 1,
    ).map { (d, shade) -> p(d) to shade }

    val core: Path = p("M30,23L34.33,25.5L34.33,30.5L30,33L25.67,30.5L25.67,25.5Z")

    /** The loading mark's glints, each with its delay in ms (one turn is [GLINT_MS]). */
    val spokes: List<Pair<Path, Int>> = listOf(
        "M35.5,18.47L30,4L42,7.22Z" to 0,
        "M30,23L30,4L35.5,18.47Z" to 92,
        "M30,23L34.33,25.5L35.5,18.47Z" to 181,
        "M35.5,37.53L42,48.78L30,52Z" to 701,
        "M30,33L35.5,37.53L30,52Z" to 790,
        "M34.33,30.5L30,33L35.5,37.53Z" to 882,
        "M24.5,18.47L9.22,16L18,7.22Z" to 1399,
        "M25.67,25.5L9.22,16L24.5,18.47Z" to 1491,
        "M25.67,25.5L30,23L24.5,18.47Z" to 1583,
        "M41,28L50.78,16L54,28Z" to 2100,
        "M34.33,25.5L50.78,16L41,28Z" to 2192,
        "M34.33,25.5L34.33,30.5L41,28Z" to 2281,
        "M24.5,37.53L18,48.78L9.22,40Z" to 2801,
        "M25.67,30.5L24.5,37.53L9.22,40Z" to 2890,
        "M30,33L25.67,30.5L24.5,37.53Z" to 2982,
        "M35.5,37.53L50.78,40L42,48.78Z" to 3499,
        "M34.33,30.5L50.78,40L35.5,37.53Z" to 3591,
    ).map { (d, delay) -> p(d) to delay }

    val lens: Path = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(Offset(30f, 28f), 15.75f))
    }
}

const val GLINT_MS = 4200
const val CORE_MS = 700

private val Orange = listOf(Color(0xFFFF6600), Color(0xFFE85D00), Color(0xFFC24E00))

/** The same shades through a greyscale filter (Rec. 709 luma), for the idle bubble. */
private val Grey = listOf(Color(0xFF7F7F7F), Color(0xFF747474), Color(0xFF616161))
private val Glint = Color(0xFFFFF3E8)

/** The glint's opacity [p] of the way through its turn: `mk-glint` in the web's CSS. */
fun glintAlpha(p: Float): Float = when {
    p < 0.01f -> 0.95f * p / 0.01f
    p < 0.09f -> 0.95f
    p < 0.12f -> 0.95f * (0.12f - p) / 0.03f
    else -> 0f
}

/** The core's opacity [p] of the way through its turn: `mk-core`. */
fun coreAlpha(p: Float): Float = when {
    p < 0.39f -> 0f
    p < 0.48f -> 0.95f * (p - 0.39f) / 0.09f
    p < 0.78f -> 0.95f
    p < 0.96f -> 0.95f * (0.96f - p) / 0.18f
    else -> 0f
}

private fun DrawScope.mark(ring: Color, grey: Boolean, time: Float?) {
    val shades = if (grey) Grey else Orange
    scale(size.minDimension / 64f, pivot = Offset.Zero) {
        translate(32f, 32f) {
            scale(1.42f, pivot = Offset.Zero) {
                translate(-30f, -28f) {
                    clipPath(MarkPaths.lens) {
                        drawCircle(shades[0], 15.75f, Offset(30f, 28f))
                        MarkPaths.facets.forEach { (path, shade) -> drawPath(path, shades[shade]) }
                        if (time != null) {
                            drawPath(MarkPaths.core, Glint, alpha = coreAlpha((time % CORE_MS) / CORE_MS))
                            MarkPaths.spokes.forEach { (path, delay) ->
                                val p = (((time - delay) % GLINT_MS) + GLINT_MS) % GLINT_MS / GLINT_MS
                                val a = glintAlpha(p)
                                if (a > 0f) drawPath(path, Glint, alpha = a)
                            }
                        }
                    }
                    drawCircle(ring, 18f, Offset(30f, 28f), style = Stroke(4.5f))
                }
            }
        }
    }
}

/** Whether the person asked for less motion ("Remove animations" sets the animator scale to 0). */
@Composable
fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** The Monokulo logo; [label] null when it's decoration. */
@Composable
fun Logo(size: Dp, modifier: Modifier = Modifier, ring: Color = mk.ink, grey: Boolean = false, label: String? = null) {
    val described = if (label != null) Modifier.semantics { contentDescription = label } else Modifier
    Canvas(modifier.size(size).then(described)) { mark(ring, grey, null) }
}

/**
 * The loading mark: the logo with glints running round it, which says
 * "watching" or "waiting". Still when the person has asked for less
 * motion, as the web's `prefers-reduced-motion` does.
 */
@Composable
fun LoadingMark(size: Dp, label: String, modifier: Modifier = Modifier, ring: Color = mk.ink) {
    val described = modifier.size(size).semantics { contentDescription = label }
    if (reducedMotion()) {
        Canvas(described) { mark(ring, false, null) }
        return
    }
    // Read in the draw phase only: each frame redraws, nothing recomposes.
    val time = rememberInfiniteTransition(label = "loading mark").animateFloat(
        initialValue = 0f,
        targetValue = GLINT_MS.toFloat(),
        animationSpec = infiniteRepeatable(tween(GLINT_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "glints",
    )
    Canvas(described) { mark(ring, false, time.value) }
}

