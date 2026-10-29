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
import app.monokular.push.BellAddress
import app.monokular.push.WakeUpChoice
import app.monokular.push.WakeUpStatus
import app.monokular.sales.Pkce
import app.monokular.sales.SquareAuth
import app.monokular.sales.SquareException
import app.monokular.sales.SquareLocation
import app.monokular.sales.TillSale
import app.monokular.sales.live
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** The shift (ShiftList): this shift's sales, the till's home. */
    data object Till : Screen
    /** One sale: its code while it's live (SaleA), else its page by standing (row 2b). */
    data class Sale(val orderId: String) : Screen
    /** A live sale's code enlarged, for the customer to scan (CodeB). */
    data class Code(val orderId: String) : Screen
    /** What a live sale's customer bought, every line (PanelE, from the sale's order card). */
    data class Order(val orderId: String) : Screen
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

    /** Sales whose full screen the cashier left (Back, or a paid one that stepped back itself): they don't come back up on their own. */
    val left = MutableStateFlow(emptySet<String>())

    /** Sales whose refund the POS hasn't answered yet: their Refund buttons wait. */
    val refunding = MutableStateFlow(emptySet<String>())

    /** Where Settings was opened from, to go back to. */
    private var beforeSettings: Screen? = null

    /** The Square sign-in under way went through a Bell's application, from Settings: back there once it's done. */
    private var bellSignIn = false

    /** The sales that were live at the last look, to tell a sale paid on screen. */
    private var liveBefore = emptySet<String>()
    private var stepBack: Job? = null

    val runner get() = graph.runner.state
    val bubbleWanted get() = graph.bubbleWanted

    val connection: Connection? get() = stores.connection

    /** The store as last read, this session only (decision 50). */
    val store get() = graph.store.now

    /** Settings › Wake-ups: what the cashier chose, and where the chosen Bell stands. */
    val wakeUpChoice get() = graph.wakeUps.choice
    val wakeUpStatus get() = graph.wakeUps.status

    /** The last "My own Bell" address used, kept through Timer only. */
    val ownBell: BellAddress? get() = stores.wakeUps.ownBell

    /** The project's Bell, as the graph parsed it from `BuildConfig.BELL_URL` (decision 98). */
    val monokularBell: BellAddress get() = graph.monokularBell

    init {
        // On app start: the store's name and base currency, read live.
        graph.store.read(stores.connection)
        viewModelScope.launch { graph.runner.state.collect { follow(it.till.sales) } }
        // Not watching: the shift's sales still list, to open and refund.
        if (!stores.shiftOn) graph.runner.showSaved()
    }

    /** The sales changed: bring up a new one, move off a code that's down, and step back from one paid on screen. */
    private fun follow(sales: List<TillSale>) {
        val next = TillNav.follow(screenState.value, sales, left.value)
        if (next != screenState.value) screenState.value = next
        TillNav.paidOnScreen(next, sales, liveBefore)?.let { paid ->
            stepBack?.cancel()
            stepBack = viewModelScope.launch {
                delay(TillNav.PAID_STEP_BACK_MS)
                if (screenState.value == Screen.Sale(paid)) leave(paid)
            }
        }
        liveBefore = sales.filter { it.live }.map { it.orderId }.toSet()
    }

    /** Reads the store again: its name, wallet and this device's name can change at any time. */
    fun readStore() = graph.store.read(stores.connection)

    /** Settings, with the store read again as it opens. */
    fun openSettings() {
        readStore()
        retryWakeUps()
        beforeSettings = screenState.value.takeIf { it is Screen.Till || it is Screen.Sale || it is Screen.Code || it is Screen.Order }
        go(Screen.Settings)
    }

    /**
     * The app came forward, or Settings opened: a Bell that couldn't be reached is tried
     * again now rather than at the end of its wait, since what was in the way (a UnifiedPush
     * app just installed and not yet opened, say) may have been seen to (decision 123).
     * Nothing else is retried here: a refusal keeps decision 95's rules.
     */
    fun retryWakeUps() {
        if (graph.wakeUps.status.value is WakeUpStatus.Unreachable) graph.wakeUps.recheck()
    }

    /** Back from Settings: to the sale or the shift it was opened from. */
    fun closeSettings() {
        val before = beforeSettings
        beforeSettings = null
        if (before == null || !stores.setupDone) return goHome()
        go(TillNav.follow(before, graph.runner.state.value.till.sales, left.value))
    }
    val location: SquareLocation? get() = stores.location
    val squareSignedIn: Boolean get() = graph.squareAuth().signedIn
    val shiftOn: Boolean get() = stores.shiftOn
    val shiftStartedAt: Long? get() = stores.shiftStartedAt
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

    /** One step back: in setup, from a sale to the shift, from its code or order to the sale; home from elsewhere. */
    fun back() = when (val screen = screenState.value) {
        is Screen.Sale -> leave(screen.orderId)
        is Screen.Code -> go(Screen.Sale(screen.orderId))
        is Screen.Order -> go(Screen.Sale(screen.orderId))
        Screen.Settings -> closeSettings()
        Screen.Scan -> go(Screen.Welcome)
        is Screen.Confirm -> go(Screen.Scan)
        is Screen.Locations -> go(Screen.Square)
        Screen.Alerts -> go(Screen.Square)
        Screen.Float -> go(Screen.Alerts)
        Screen.Ready -> go(Screen.Float)
        else -> goHome()
    }

    /**
     * Back from [from] only if it's still on screen: the enlarged code goes
     * back by itself once its sale isn't live, which the sales changing may
     * already have done ([TillNav]), and a second Back would leave the sale.
     */
    fun back(from: Screen) {
        if (screenState.value == from) back()
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
            // A registration left from an earlier store goes before the stores forget its id.
            graph.wakeUps.unregister(forget = true)
            stores.forgetAll()
            stores.connection = connection
            graph.store.forget()
            graph.store.read(connection)
            go(Screen.Connected(connection))
        } catch (e: MonokuloException) {
            problem.value = Problems.say(e)
        }
    }

    /**
     * Square's sign-in page, for this attempt: through [applicationId]
     * when that's a Bell's (Settings › Wake-ups' "Sign in to Square",
     * decision 96), else through the application the till signs in
     * through now. The application is kept with the attempt, and becomes
     * the till's only once the sign-in finishes, so the working sign-in
     * stands until then.
     */
    fun squareSignInUrl(applicationId: String? = null): String {
        val pkce = Pkce.create()
        val through = applicationId?.takeIf { it != graph.squareConfig().applicationId }
        stores.pendingSignIn = PendingSignIn(pkce, through)
        return squareAuth(through).authorizeUrl(pkce)
    }

    /** Square's sign-in through [applicationId], or through the till's own application when null. */
    private fun squareAuth(applicationId: String?): SquareAuth =
        if (applicationId == null) graph.squareAuth() else SquareAuth(graph.http, graph.squareConfig().copy(applicationId = applicationId), stores.tokens)

    /** Square's redirect back into the app. */
    fun onSquareRedirect(uri: Uri) = work {
        val pending = stores.pendingSignIn ?: run {
            problem.value = "That Square sign-in wasn't started here. Try again."
            return@work
        }
        try {
            squareAuth(pending.applicationId)
                .finish(pending.pkce, uri.getQueryParameter("state"), uri.getQueryParameter("code"), uri.getQueryParameter("error"))
            stores.pendingSignIn = null
            if (pending.applicationId != null) {
                // Signed in through the Bell's application: the till's from now on (decision 96).
                stores.squareApplicationId = pending.applicationId
                bellSignIn = true
            }
            val locations = graph.squareApi().locations()
            when {
                locations.size == 1 -> chooseLocation(locations.single())
                else -> {
                    // The running till still asks Square as the old application: it starts again as the new one.
                    if (pending.applicationId != null) restart()
                    go(Screen.Locations(locations))
                }
            }
        } catch (e: SquareException) {
            // A failed sign-in after a good one (Square's locations) leaves no Bell sign-in pending.
            bellSignIn = false
            // go() clears the problem, so Square's words come after it.
            go(if (pending.applicationId != null && stores.setupDone) Screen.Settings else Screen.Square)
            problem.value = e.message
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
        // The Bell rings this till for the location it registers: registered again for this one.
        graph.wakeUps.recheck()
        // The till starts watching now, so setup can show the test sale.
        restart()
        val forBell = bellSignIn
        bellSignIn = false
        go(
            when {
                forBell && stores.setupDone -> Screen.Settings
                stores.setupDone -> Screen.Till
                else -> Screen.Square
            },
        )
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

    /**
     * Watching on or off. Turning it off brings every live code down;
     * the shift's sales stay listed. Turning it on after it was off
     * starts a new shift: "since" is now and the last one's sales go,
     * but for those that still need the cashier (decisions 64, 86).
     * The first shift starts when the till first runs, in setup.
     */
    fun setShift(on: Boolean) {
        if (on && !stores.shiftOn) {
            stores.shift.save(ShiftList.carried(stores.shift.load()))
            stores.shiftStartedAt = System.currentTimeMillis() / 1000
        }
        if (!on && stores.shiftOn) graph.runner.closeLive()
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

    /**
     * Signs out of Square. The Bell's registration is taken back first,
     * awaited, while what it needs is still here; the Wake-ups choice
     * stays, so signing in again registers again (acceptance A10.2).
     */
    fun signOutOfSquare() = work {
        graph.wakeUps.unregister()
        graph.squareAuth().signOut()
        stores.location = null
        restart()
        go(Screen.Square)
    }

    fun disconnect() = work {
        val connection = stores.connection
        // The Bell forgets this till before the store does (acceptance A10.3).
        graph.wakeUps.unregister(forget = true)
        if (connection != null) {
            // The store may already have disconnected it: forget it either way.
            runCatching { graph.monokulo.disconnect(connection) }
        }
        forgetHere()
    }

    /** The store disconnected this device: forget it and start again, the Bell's registration taken back first. */
    fun forget() = work {
        graph.wakeUps.unregister(forget = true)
        forgetHere()
    }

    /** Forgets the store and everything about this till, once nothing outside it needs what's forgotten. */
    private fun forgetHere() {
        TillService.stop(getApplication())
        graph.squareAuth().signOut()
        stores.forgetAll()
        graph.store.forget()
        left.value = emptySet()
        beforeSettings = null
        bellSignIn = false
        go(Screen.Welcome)
    }

    /** Settings › Wake-ups' choice. */
    fun chooseWakeUps(choice: WakeUpChoice) = graph.wakeUps.choose(choice)

    /** "Use this Bell": the address as typed, which Settings only offers once it parses. */
    fun useOwnBell(text: String) {
        val bell = BellAddress.parse(text, allowHttp = BuildConfig.DEBUG) ?: return
        graph.wakeUps.choose(WakeUpChoice.OwnBell(bell))
    }

    /** The UnifiedPush app to deliver wake-ups through, picked from several. */
    fun chooseDistributor(packageName: String) = graph.wakeUps.chooseDistributor(packageName)

    /** Refunds a sale's Square sale ([close] it first: the close sheet's "No"); its button waits for the POS's answer. */
    fun refund(orderId: String, close: Boolean = false) {
        if (orderId in refunding.value) return
        val job = graph.runner.refund(orderId, close) ?: return
        refunding.update { it + orderId }
        job.invokeOnCompletion { refunding.update { it - orderId } }
    }

    /** The close sheet's answer for the sale on screen ([Closing]). */
    fun closeSale(orderId: String, choice: CloseChoice) {
        if (!Closing.applies(graph.runner.state.value.till.sales.firstOrNull { it.orderId == orderId })) return
        val outcome = Closing.outcome(choice)
        when {
            outcome.refund -> refund(orderId, close = outcome.close)
            outcome.close -> graph.runner.close(orderId)
        }
        if (outcome.then == CloseOutcome.Then.Shift) leave(orderId)
    }

    /** "Show the code again" on a closed sale: its code is back up, on screen and on the bubble. */
    fun reopen(orderId: String) {
        graph.runner.reopen(orderId)
        open(orderId)
    }

    /**
     * The cashier left a sale's full screen for the shift (it stays live, on
     * the bubble and in the shift); another live sale they haven't left
     * comes up in its place.
     */
    fun leave(orderId: String) {
        left.update { it + orderId }
        val screen = screenState.value
        if (screen.orderIdOrNull() == orderId) {
            go(Screen.Till)
            follow(graph.runner.state.value.till.sales)
        }
    }

    private fun Screen.orderIdOrNull(): String? = when (this) {
        is Screen.Sale -> orderId
        is Screen.Code -> orderId
        is Screen.Order -> orderId
        else -> null
    }

    /** Shows a sale full screen (from the shift, a notification or the bubble's panel): its code, or its page. */
    fun open(orderId: String) {
        left.update { it - orderId }
        stepBack?.cancel()
        if (stores.setupDone) go(Screen.Sale(orderId))
    }

    /** The enlarged code, from the sale's code. */
    fun enlarge(orderId: String) = go(Screen.Code(orderId))

    /** Every line of the sale's order, from its order card. */
    fun showOrder(orderId: String) = go(Screen.Order(orderId))

    /** The shift (from the panel's "Open the shift"): every sale's full screen is left for now. */
    fun openShift() {
        left.update { it + graph.runner.state.value.till.sales.map { s -> s.orderId } }
        if (stores.setupDone) go(Screen.Till)
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
