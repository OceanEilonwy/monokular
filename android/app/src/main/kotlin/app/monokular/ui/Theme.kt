package app.monokular.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Orange = Color(0xFFFF6600)
val Paid = Color(0xFF2E7D32)

/** Monokulo's palette: warm paper, ink, one orange primary. */
@Composable
fun MonokularTheme(content: @Composable () -> Unit) {
    val light = lightColorScheme(
        primary = Orange, onPrimary = Color(0xFF111111),
        background = Color(0xFFF4F1EA), onBackground = Color(0xFF1A1917),
        surface = Color(0xFFF4F1EA), onSurface = Color(0xFF1A1917),
        surfaceVariant = Color(0xFFE9E4D8), onSurfaceVariant = Color(0xFF5A564C),
        surfaceContainer = Color(0xFFFFFDF8),
    )
    val dark = darkColorScheme(
        primary = Orange, onPrimary = Color(0xFF111111),
        background = Color(0xFF1E1E1E), onBackground = Color(0xFFD4D4D4),
        surface = Color(0xFF1E1E1E), onSurface = Color(0xFFD4D4D4),
        surfaceVariant = Color(0xFF2A2A2A), onSurfaceVariant = Color(0xFF9D9D9D),
        surfaceContainer = Color(0xFF262626),
    )
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, content = content)
}
