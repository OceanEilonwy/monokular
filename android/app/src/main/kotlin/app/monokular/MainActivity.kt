package app.monokular

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.monokular.notify.SaleNotifications
import app.monokular.ui.AlertsScreen
import app.monokular.ui.ConfirmScreen
import app.monokular.ui.ConnectedScreen
import app.monokular.ui.FloatScreen
import app.monokular.ui.LocationsScreen
import app.monokular.ui.MonokularTheme
import app.monokular.ui.ReadyScreen
import app.monokular.ui.ScanScreen
import app.monokular.ui.SettingsActions
import app.monokular.ui.SettingsScreen
import app.monokular.ui.SettingsState
import app.monokular.ui.SquareScreen
import app.monokular.ui.CodeScreen
import app.monokular.ui.OrderPage
import app.monokular.ui.SaleScreen
import app.monokular.ui.ShiftScreen
import app.monokular.ui.TillActions
import app.monokular.ui.TillStatus
import app.monokular.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()

    /** Bumped on every resume: permissions granted in Android's settings show at once. */
    private val resumed = mutableIntStateOf(0)

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        resumed.intValue++
        model.permissionsMayHaveChanged()
        if (model.screen.value == Screen.Alerts) model.go(Screen.Float)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            MonokularTheme {
                val screen by model.screen.collectAsStateWithLifecycle()
                val busy by model.busy.collectAsStateWithLifecycle()
                val problem by model.problem.collectAsStateWithLifecycle()
                val runner by model.runner.collectAsStateWithLifecycle()
                val refunding by model.refunding.collectAsStateWithLifecycle()
                val bubbleWanted by model.bubbleWanted.collectAsStateWithLifecycle()
                val store by model.store.collectAsStateWithLifecycle()
                // Read so that coming back from Android's settings recomposes.
                @Suppress("UNUSED_VARIABLE") val tick = resumed.intValue
                // Re-read on every bump of `resumed`, which onMultiWindowModeChanged makes, so the till's actions follow it.
                val multiWindow = isInMultiWindowMode
                BackHandler(enabled = screen != Screen.Welcome && screen != Screen.Till && screen != Screen.Square) { model.back() }
                when (val s = screen) {
                    Screen.Welcome -> WelcomeScreen(onStart = { model.go(Screen.Scan) })
                    Screen.Scan -> ScanScreen(onCode = model::onCode, onBack = model::back)
                    is Screen.Confirm -> ConfirmScreen(s.code, model.deviceName, busy, problem, { model.connect(s.code) }, model::back)
                    is Screen.Connected -> ConnectedScreen(
                        s.connection, store,
                        onNext = { model.go(Screen.Square) }, onRetry = model::readStore, onWrong = model::disconnect,
                    )
                    Screen.Square -> SquareScreen(
                        location = model.location,
                        signedIn = model.squareSignedIn,
                        busy = busy,
                        problem = problem ?: runner.till.problem,
                        testSale = runner.till.sales.firstOrNull { it.found != null },
                        onSignIn = ::signInToSquare,
                        onChangeLocation = model::changeLocation,
                        onSkip = model::withoutSquare,
                        onContinue = {
                            // The test sale has done its job: the till opens on its idle screen, the sale in its list.
                            runner.till.sales.forEach { model.leave(it.orderId) }
                            model.go(Screen.Alerts)
                        },
                    )
                    is Screen.Locations -> LocationsScreen(s.locations, model::chooseLocation, model::back)
                    Screen.Alerts -> AlertsScreen(
                        place = model.location?.name,
                        currency = model.location?.currency ?: store.store?.baseCurrency ?: "USD",
                        allowed = model.notificationsAllowed(),
                        onAllow = ::allowNotifications,
                        onSkip = { model.go(Screen.Float) },
                        onBack = model::back,
                    )
                    Screen.Float -> FloatScreen(
                        allowed = model.overlayAllowed(),
                        onOpenSettings = { model.setBubble(true); openOverlaySettings() },
                        onNotificationsOnly = { model.setBubble(false); model.go(Screen.Ready) },
                        onContinue = { model.setBubble(true); model.go(Screen.Ready) },
                        onBack = model::back,
                    )
                    Screen.Ready -> ReadyScreen(
                        store = store.store?.name.orEmpty(),
                        location = model.location?.name,
                        alerts = model.notificationsAllowed(),
                        bubble = bubbleWanted && model.overlayAllowed(),
                        onStart = model::startShift,
                    )
                    Screen.Till, is Screen.Sale, is Screen.Code, is Screen.Order -> {
                        val sales = runner.till.sales
                        val shown = when (s) {
                            is Screen.Sale -> s.orderId
                            is Screen.Code -> s.orderId
                            is Screen.Order -> s.orderId
                            else -> null
                        }?.let { id -> sales.firstOrNull { it.orderId == id } }
                        val actions = remember(multiWindow) { tillActions(multiWindow) }
                        when {
                            // A sale not in the list (yet: the till is still loading its shift) shows the shift until it is;
                            // a disconnected till shows why, whatever was on screen.
                            shown == null || runner.disconnected -> ShiftScreen(tillStatus(runner, store), sales, actions)
                            s is Screen.Code -> CodeScreen(shown, onBack = { model.back(s) })
                            s is Screen.Order -> OrderPage(shown, "Back to the sale", onBack = { model.back(s) }, onShowCode = { model.back(s) })
                            else -> SaleScreen(shown, model.location?.name, shown.orderId in refunding, runner.till.refundProblems[shown.orderId], actions)
                        }
                    }
                    Screen.Settings -> {
                        val torRunning by produceState<Boolean?>(null, store) { value = model.torRunning() }
                        SettingsScreen(
                            SettingsState(
                                connection = model.connection,
                                store = store,
                                location = model.location,
                                squareSignedIn = model.squareSignedIn,
                                tenderName = model.tenderName,
                                live = runner.live,
                                shiftOn = model.shiftOn,
                                alerts = model.notificationsAllowed(),
                                bubbleWanted = bubbleWanted,
                                bubbleAllowed = model.overlayAllowed(),
                                debug = model.debug,
                                squareBaseUrl = model.squareBaseUrl,
                                torRunning = torRunning,
                            ),
                            SettingsActions(
                                onShift = { model.setShift(it); model.go(Screen.Settings) },
                                onAlerts = ::openNotificationSettings,
                                onBubble = { on ->
                                    model.setBubble(on)
                                    if (on && !model.overlayAllowed()) openOverlaySettings()
                                },
                                onSquare = { model.go(Screen.Square) },
                                onChangeLocation = model::changeLocation,
                                onSignOutSquare = model::signOutOfSquare,
                                onDisconnect = model::disconnect,
                                onSquareBaseUrl = { model.squareBaseUrl = it },
                                onCopyAddress = { SaleNotifications.copyAddress(this, it) },
                                onTor = model::setTor,
                                onBack = model::closeSettings,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun tillStatus(runner: RunnerState, store: StoreNow) = TillStatus(
        store = store.store?.name,
        location = model.location?.name,
        running = runner.running,
        live = runner.live,
        disconnected = runner.disconnected,
        problem = runner.till.problem ?: runner.streamProblem,
        signInAgain = runner.till.signInAgain,
        startedAt = model.shiftStartedAt,
    )

    private fun tillActions(multiWindow: Boolean) = TillActions(
        onSettings = model::openSettings,
        onOpen = model::open,
        onBack = model::leave,
        onEnlarge = model::enlarge,
        onShowOrder = model::showOrder,
        onClose = model::closeSale,
        onRefund = { model.refund(it) },
        onReopen = model::reopen,
        onCopy = { SaleNotifications.copyAddress(this, it.address) },
        onCopyXmr = { SaleNotifications.copyXmr(this, it.xmr) },
        onBackToSquare = if (multiWindow) null else ::backToSquare,
        onSignIn = ::signInToSquare,
        onForget = model::forget,
        onStartShift = { model.setShift(true) },
    )

    override fun onStart() {
        super.onStart()
        model.setVisible(true)
        if (model.connection != null && model.shiftOn) model.restartIfStopped()
        model.wake()
    }

    override fun onResume() {
        super.onResume()
        resumed.intValue++
        model.permissionsMayHaveChanged()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        resumed.intValue++
    }

    override fun onStop() {
        model.setVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ORDER)?.let(model::open)
        if (intent?.getBooleanExtra(EXTRA_SHIFT, false) == true) model.openShift()
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
        val expected = BuildConfig.SQUARE_RETURN_URI.toUri()
        return uri.scheme == expected.scheme && uri.host == expected.host && uri.path.orEmpty() == expected.path.orEmpty()
    }

    private fun signInToSquare() {
        CustomTabsIntent.Builder().build().launchUrl(this, model.squareSignInUrl().toUri())
    }

    private fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !model.notificationsAllowed()) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            model.go(Screen.Float)
        }
    }

    private fun openOverlaySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())) }
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    /** Square Point of Sale, if it's installed; else Monokular steps back to whatever was behind it. */
    private fun backToSquare() {
        val square = packageManager.getLaunchIntentForPackage(SQUARE_POS)
        if (square != null) startActivity(square) else moveTaskToBack(true)
    }

    companion object {
        /** A sale to show full screen: its code, or its page. */
        const val EXTRA_ORDER = "app.monokular.ORDER"

        /** Open on the shift (the panel's "Open the shift"), whatever sale is live. */
        const val EXTRA_SHIFT = "app.monokular.SHIFT"

        /** Square Point of Sale's package. */
        const val SQUARE_POS = "com.squareup"
    }
}
