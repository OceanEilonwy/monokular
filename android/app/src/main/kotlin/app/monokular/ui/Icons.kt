package app.monokular.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The design's line icons, from its SVG paths. Tinted by `Icon`, so drawn in black here. */
object MkIcons {
    private fun icon(name: String, box: Float, vararg strokes: Pair<String, Float>, fills: List<String> = emptyList()) =
        ImageVector.Builder(name, box.dp, box.dp, box, box).apply {
            strokes.forEach { (d, width) ->
                addPath(
                    PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            fills.forEach { d -> addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black)) }
        }.build()

    val Back = icon("back", 24f, "M20 12H5M11 5l-7 7 7 7" to 2.2f)
    val Close = icon("close", 14f, "M2 2l10 10M12 2L2 12" to 2f)
    val More = icon(
        "more", 24f,
        fills = listOf(
            "M12 3a2 2 0 1 0 0.01 0Z",
            "M12 10a2 2 0 1 0 0.01 0Z",
            "M12 17a2 2 0 1 0 0.01 0Z",
        ),
    )
    val ChevronDown = icon("chevron down", 16f, "M3 6l5 5 5-5" to 2.2f)
    val Minus = icon("minus", 16f, "M3 8h10" to 2.2f)
    val Settings = icon(
        "settings", 24f,
        "M12 8.8a3.2 3.2 0 1 0 0.01 0Z" to 2f,
        "M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1" to 2f,
    )
    val Bag = icon("bag", 24f, "M4 7h16l-1.5 12.5a1 1 0 0 1-1 .9H6.5a1 1 0 0 1-1-.9L4 7Z M9 7V5.5a3 3 0 0 1 6 0V7" to 1.8f)
    val Code = icon("code", 24f, "M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h2v2h-2z M18 18h2v2h-2z M14 18h2 M18 14h2" to 1.8f)
    val CheckCircle = icon("check circle", 24f, "M12 3a9 9 0 1 0 0.01 0Z" to 1.8f, "M8 12.3l2.6 2.6L16 9.5" to 1.8f)
    val Tick = icon("tick", 24f, "M5.5 12.5l4 4L18.5 7.5" to 2.6f)
    val Lock = icon("lock", 24f, "M7 10h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2Z" to 2.4f, "M8 10V7a4 4 0 0 1 8 0v3" to 2.4f)
    val Copy = icon(
        "copy", 24f,
        "M10.5 8h7a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5h-7A2.5 2.5 0 0 1 8 17.5v-7A2.5 2.5 0 0 1 10.5 8Z" to 2f,
        "M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2" to 2f,
    )
    val Clock = icon("clock", 24f, "M12 3a9 9 0 1 0 0.01 0Z" to 2f, "M12 7v5.5l3.5 2" to 2f)
    val Bell = icon("bell", 24f, "M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15L6 16Z M10 20.5a2 2 0 0 0 4 0" to 1.8f)
}
