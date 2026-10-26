package app.monokular

import android.app.Application
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.monokular.core.ConnectCode
import app.monokular.core.Connection
import app.monokular.core.MonokuloException
import app.monokular.core.Problems
import app.monokular.core.Tor
import app.monokular.notify.SaleNotifications
import app.monokular.sales.Pkce
import app.monokular.sales.SquareException
import app.monokular.sales.SquareLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the app is. Setup follows the design's six onboarding steps. */
sealed interface Screen {
    /** 1: what Monokular does. */
    data object Welcome : Screen
    /** 2: scan or paste the store's connect code. */
    data object Scan : Screen
    /** 3: connect to the server, then check the store it is. */
    data class Confirm(val code: ConnectCode) : Screen
    data class Connected(val connection: Connection) : Screen
    /** 4: Square's sign-in and location. */
    data object Square : Screen
    data class Locations(val locations: List<SquareLocation>) : Screen
    /** 5: notifications, then the bubble over other apps. */
    data object Alerts : Screen
    data object Float : Screen
    /** 6: start the shift. */
    data object Ready : Screen
    data object Till : Screen
    data object Settings : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as MonokularApp).graph
    private val stores = graph.stores

    private val screenState = MutableStateFlow(home())
    val screen: StateFlow<Screen> = screenState.asStateFlow()

    /** Busy with a network call: buttons wait. */
    val busy = MutableStateFlow(false)

    /** What went wrong last, to show. */
    val problem = MutableStateFlow<String?>(null)

    /** Sales the cashier closed (or that closed themselves once paid): the till shows its idle screen past them. */
    val closed = MutableStateFlow(emptySet<String>())

    /** The sale on screen, or opened from the list or a notification. */
    val focus = MutableStateFlow<String?>(null)

    val runner get() = graph.runner.state
    val bubbleWanted get() = graph.bubbleWanted

    val connection: Connection? get() = stores.connection

    /** The store as last read, this session only (decision 50). */
    val store get() = graph.store.now

    init {
        // On app start: the store's name and base currency, read live.
        graph.store.read(stores.connection)
    }

    /** Reads the store again: its name, wallet and this device's name can change at any time. */
    fun readStore() = graph.store.read(stores.connection)

    /** Settings, with the store read again as it opens. */
    fun openSettings() {
        readStore()
        go(Screen.Settings)
    }
    val location: SquareLocation? get() = stores.location
    val squareSignedIn: Boolean get() = graph.squareAuth().signedIn
    val shiftOn: Boolean get() = stores.shiftOn
    val debug: Boolean get() = BuildConfig.DEBUG
    val deviceName: String get() = graph.identity.deviceName
    val tenderName: String get() = graph.squareConfig().tenderName
    var squareBaseUrl: String?
        get() = stores.squareBaseUrl
        set(value) {
            stores.squareBaseUrl = value
        }

    fun overlayAllowed(): Boolean = Settings.canDrawOverlays(getApplication())

    fun notificationsAllowed(): Boolean = SaleNotifications(getApplication()).allowed()

    private fun home(): Screen = when {
        stores.connection == null -> Screen.Welcome
        stores.setupDone -> Screen.Till
        stores.location == null && !stores.withoutSquare -> Screen.Square
        else -> Screen.Alerts
    }

    fun go(screen: Screen) {
        problem.value = null
        screenState.value = screen
    }

    fun goHome() = go(home())

    /** One step back in setup, or home from elsewhere. */
    fun back() = when (screenState.value) {
        Screen.Scan -> go(Screen.Welcome)
        is Screen.Confirm -> go(Screen.Scan)
        is Screen.Locations -> go(Screen.Square)
        Screen.Alerts -> go(Screen.Square)
        Screen.Float -> go(Screen.Alerts)
        Screen.Ready -> go(Screen.Float)
        else -> goHome()
    }

    /** A scanned or pasted code; false when it isn't one. */
    fun onCode(text: String): Boolean {
        val code = ConnectCode.parse(text, allowCleartext = BuildConfig.DEBUG) ?: return false
        go(Screen.Confirm(code))
        return true
    }

    fun connect(code: ConnectCode) = work {
        try {
            val redeemed = graph.monokulo.connect(code)
            // Over Tor when the store has an onion address and Orbot is running (decision 55).
            val connection = redeemed.copy(tor = redeemed.onionApi != null && withContext(Dispatchers.IO) { Tor.running() })
            stores.forgetAll()
            stores.connection = connection
            graph.store.forget()
            graph.store.read(connection)
            go(Screen.Connected(connection))
        } catch (e: MonokuloException) {
            problem.value = Problems.say(e)
        }
    }

    /** Square's sign-in page, for this attempt. */
    fun squareSignInUrl(): String {
        val pkce = Pkce.create()
        stores.pendingSignIn = pkce
        return graph.squareAuth().authorizeUrl(pkce)
    }

    /** Square's redirect back into the app. */
    fun onSquareRedirect(uri: Uri) = work {
        val pkce = stores.pendingSignIn ?: run {
            problem.value = "That Square sign-in wasn't started here. Try again."
            return@work
        }
        try {
            graph.squareAuth().finish(pkce, uri.getQueryParameter("state"), uri.getQueryParameter("code"), uri.getQueryParameter("error"))
            stores.pendingSignIn = null
            val locations = graph.squareApi().locations()
            if (locations.size == 1) chooseLocation(locations.single()) else go(Screen.Locations(locations))
        } catch (e: SquareException) {
            problem.value = e.message
            go(Screen.Square)
        }
    }

    /** The Square locations, to change this till's. */
    fun changeLocation() = work {
        try {
            go(Screen.Locations(graph.squareApi().locations()))
        } catch (e: SquareException) {
            problem.value = e.message
        }
    }

    fun chooseLocation(location: SquareLocation) {
        stores.location = location
        stores.withoutSquare = false
        // The till starts watching now, so setup can show the test sale.
        restart()
        go(if (stores.setupDone) Screen.Till else Screen.Square)
    }

    fun withoutSquare() {
        stores.withoutSquare = true
        restart()
        go(if (stores.setupDone) Screen.Till else Screen.Alerts)
    }

    fun setBubble(on: Boolean) = graph.setBubble(on)

    /** Setup's last step: the shift starts, and the till takes over. */
    fun startShift() {
        stores.setupDone = true
        setShift(true)
        go(Screen.Till)
    }

    fun setShift(on: Boolean) {
        stores.shiftOn = on
        if (on) TillService.start(getApplication()) else TillService.stop(getApplication())
    }

    /** Starts (or restarts) the till's service when a shift is on. */
    fun restart() {
        TillService.stop(getApplication())
        if (stores.shiftOn && stores.connection != null) TillService.start(getApplication())
    }

    /** Starts the till's service if a shift is on and it isn't running (the app was reopened). */
    fun restartIfStopped() {
        if (!graph.runner.state.value.running && stores.shiftOn && stores.connection != null) {
            TillService.start(getApplication())
        }
    }

    /** Reach the store over Tor, at its onion address, or not (decision 55). */
    fun setTor(on: Boolean) {
        val connection = stores.connection ?: return
        val onion = connection.onionApi ?: graph.store.now.value.onionApi ?: return
        stores.connection = connection.copy(onionApi = onion, tor = on)
        graph.store.read(stores.connection)
        restart()
        go(Screen.Settings)
    }

    /** Whether Orbot's proxy answers: Settings says so beside the Tor switch. */
    suspend fun torRunning(): Boolean = withContext(Dispatchers.IO) { Tor.running() }

    fun signOutOfSquare() {
        graph.squareAuth().signOut()
        stores.location = null
        restart()
        go(Screen.Square)
    }

    fun disconnect() = work {
        val connection = stores.connection
        if (connection != null) {
            // The store may already have disconnected it: forget it either way.
            runCatching { graph.monokulo.disconnect(connection) }
        }
        forget()
    }

    /** The store disconnected this device: forget it and start again. */
    fun forget() {
        TillService.stop(getApplication())
        graph.squareAuth().signOut()
        stores.forgetAll()
        graph.store.forget()
        closed.value = emptySet()
        go(Screen.Welcome)
    }

    fun refund(orderId: String) = graph.runner.refund(orderId)

    /** The cashier closed a sale's full screen (it stays in the shift's list). */
    fun close(orderId: String) {
        closed.update { it + orderId }
        if (focus.value == orderId) focus.value = null
    }

    /** The cashier closed the sale itself: off the screen, the bubble and the shift's list. */
    fun closeSale(orderId: String) {
        close(orderId)
        graph.runner.dismiss(orderId)
    }

    /** What the till screen shows now, so it stays there through Paid. */
    fun showing(orderId: String?) {
        if (orderId != null) focus.value = orderId
    }

    /** Shows a sale full screen again (from the list, a notification or the bubble's panel). */
    fun open(orderId: String) {
        closed.update { it - orderId }
        focus.value = orderId
        if (stores.setupDone && screenState.value !is Screen.Till) go(Screen.Till)
    }

    fun wake() = graph.runner.wake()

    fun setVisible(visible: Boolean) {
        graph.appVisible.value = visible
    }

    /** Back from Android's settings or a permission prompt: let the service look again. */
    fun permissionsMayHaveChanged() {
        graph.permissions.value++
    }

    private fun work(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        problem.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                problem.value = e.message ?: "Something went wrong."
            } finally {
                busy.value = false
            }
        }
    }
}
