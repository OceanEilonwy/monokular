package app.monokular

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.monokular.core.ConnectCode
import app.monokular.core.Connection
import app.monokular.core.MonokuloException
import app.monokular.sales.Pkce
import app.monokular.sales.SquareException
import app.monokular.sales.SquareLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the app is. */
sealed interface Screen {
    data object Welcome : Screen
    data object Scan : Screen
    data class Confirm(val code: ConnectCode) : Screen
    data class Connected(val connection: Connection) : Screen
    data object Square : Screen
    data class Locations(val locations: List<SquareLocation>) : Screen
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

    val runner get() = graph.runner.state

    val connection: Connection? get() = stores.connection
    val location: SquareLocation? get() = stores.location
    val squareSignedIn: Boolean get() = graph.squareAuth().signedIn
    val shiftOn: Boolean get() = stores.shiftOn
    val debug: Boolean get() = BuildConfig.DEBUG
    var squareBaseUrl: String?
        get() = stores.squareBaseUrl
        set(value) {
            stores.squareBaseUrl = value
        }

    private fun home(): Screen = when {
        stores.connection == null -> Screen.Welcome
        stores.location == null && !stores.withoutSquare -> Screen.Square
        else -> Screen.Till
    }

    fun go(screen: Screen) {
        problem.value = null
        screenState.value = screen
    }

    fun goHome() = go(home())

    /** A scanned or pasted code; false when it isn't one. */
    fun onCode(text: String): Boolean {
        val code = ConnectCode.parse(text, allowCleartext = BuildConfig.DEBUG) ?: return false
        go(Screen.Confirm(code))
        return true
    }

    fun connect(code: ConnectCode) = work {
        try {
            val connection = graph.monokulo.connect(code)
            stores.forgetAll()
            stores.connection = connection
            go(Screen.Connected(connection))
        } catch (e: MonokuloException) {
            problem.value = when (e.status) {
                401 -> "That code has been used or has expired. Show a new one on the store's settings."
                else -> e.message ?: "The store didn't answer."
            }
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

    fun chooseLocation(location: SquareLocation) {
        stores.location = location
        stores.withoutSquare = false
        restart()
        go(Screen.Till)
    }

    fun withoutSquare() {
        stores.withoutSquare = true
        restart()
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
        TillService.stop(getApplication())
        graph.squareAuth().signOut()
        stores.forgetAll()
        go(Screen.Welcome)
    }

    /** The store disconnected this device: forget it and start again. */
    fun forget() {
        TillService.stop(getApplication())
        graph.squareAuth().signOut()
        stores.forgetAll()
        go(Screen.Welcome)
    }

    fun refund(orderId: String) = graph.runner.refund(orderId)

    fun dismiss(orderId: String) = graph.runner.dismiss(orderId)

    fun wake() = graph.runner.wake()

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
