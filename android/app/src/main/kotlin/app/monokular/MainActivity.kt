package app.monokular

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.monokular.core.ConnectLink

class MainActivity : ComponentActivity() {
    private var link by mutableStateOf<ConnectLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        link = intent.connectLink()
        setContent { MonokularTheme { Welcome(link) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        link = intent.connectLink()
    }

    private fun Intent.connectLink(): ConnectLink? = dataString?.let(ConnectLink::parse)
}

/** Monokulo's palette: warm paper, ink, one orange primary. */
@Composable
fun MonokularTheme(content: @Composable () -> Unit) {
    val light = lightColorScheme(
        primary = Color(0xFFFF6600), onPrimary = Color(0xFF111111),
        background = Color(0xFFF4F1EA), onBackground = Color(0xFF1A1917),
        surface = Color(0xFFF4F1EA), onSurface = Color(0xFF1A1917),
        onSurfaceVariant = Color(0xFF5A564C),
    )
    val dark = darkColorScheme(
        primary = Color(0xFFFF6600), onPrimary = Color(0xFF111111),
        background = Color(0xFF1E1E1E), onBackground = Color(0xFFD4D4D4),
        surface = Color(0xFF1E1E1E), onSurface = Color(0xFFD4D4D4),
        onSurfaceVariant = Color(0xFF9D9D9D),
    )
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, content = content)
}

@Composable
fun Welcome(link: ConnectLink?) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Monokular", style = MaterialTheme.typography.headlineLarge)
            if (link == null) {
                Text(
                    "Open your store in Monokulo, go to Settings › Connected apps and choose " +
                        "Connect an app › Monokular.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Connect to ${link.host}?", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Preview
@Composable
private fun WelcomePreview() {
    MonokularTheme { Welcome(link = null) }
}
