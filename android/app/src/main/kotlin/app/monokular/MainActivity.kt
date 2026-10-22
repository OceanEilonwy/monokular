package app.monokular

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.monokular.ui.ConfirmScreen
import app.monokular.ui.ConnectedScreen
import app.monokular.ui.LocationsScreen
import app.monokular.ui.MonokularTheme
import app.monokular.ui.ScanScreen
import app.monokular.ui.SettingsScreen
import app.monokular.ui.SquareScreen
import app.monokular.ui.TillScreen
import app.monokular.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MonokularTheme {
                val screen by model.screen.collectAsStateWithLifecycle()
                val busy by model.busy.collectAsStateWithLifecycle()
                val problem by model.problem.collectAsStateWithLifecycle()
                val runner by model.runner.collectAsStateWithLifecycle()
                BackHandler(enabled = screen is Screen.Scan || screen is Screen.Confirm || screen is Screen.Settings) { model.goHome() }
                when (val s = screen) {
                    Screen.Welcome -> WelcomeScreen(onConnect = { model.go(Screen.Scan) })
                    Screen.Scan -> ScanScreen(onCode = model::onCode, onBack = model::goHome)
                    is Screen.Confirm -> ConfirmScreen(s.code, busy, problem, { model.connect(s.code) }, model::goHome)
                    is Screen.Connected -> ConnectedScreen(s.connection, onNext = model::goHome, onWrong = model::disconnect)
                    Screen.Square -> SquareScreen(busy, problem, onSignIn = ::signInToSquare, onSkip = model::withoutSquare)
                    is Screen.Locations -> LocationsScreen(s.locations, model::chooseLocation)
                    Screen.Till -> TillScreen(
                        storeName = model.connection?.store?.name ?: "",
                        running = runner.running,
                        live = runner.live,
                        sales = runner.till.sales,
                        problem = runner.till.problem,
                        signInAgain = runner.till.signInAgain,
                        disconnected = runner.disconnected,
                        onSettings = { model.go(Screen.Settings) },
                        onRefund = model::refund,
                        onDismiss = model::dismiss,
                        onSignIn = ::signInToSquare,
                        onForget = model::forget,
                    )
                    Screen.Settings -> SettingsScreen(
                        connection = model.connection,
                        location = model.location,
                        squareSignedIn = model.squareSignedIn,
                        shiftOn = model.shiftOn,
                        debug = model.debug,
                        squareBaseUrl = model.squareBaseUrl,
                        onShift = { model.setShift(it); model.go(Screen.Settings) },
                        onSquare = { model.go(Screen.Square) },
                        onSignOutSquare = model::signOutOfSquare,
                        onDisconnect = model::disconnect,
                        onSquareBaseUrl = { model.squareBaseUrl = it },
                        onBack = model::goHome,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (model.connection != null && model.shiftOn) model.restartIfStopped()
        model.wake()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                if (isSquareRedirect(uri)) model.onSquareRedirect(uri)
            }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                text.split(Regex("\\s+")).firstOrNull { model.onCode(it) }
            }
        }
    }

    private fun isSquareRedirect(uri: Uri): Boolean {
        val expected = BuildConfig.SQUARE_REDIRECT_URI.toUri()
        return uri.scheme == expected.scheme && uri.host == expected.host && uri.path.orEmpty() == expected.path.orEmpty()
    }

    private fun signInToSquare() {
        CustomTabsIntent.Builder().build().launchUrl(this, model.squareSignInUrl().toUri())
    }
}
