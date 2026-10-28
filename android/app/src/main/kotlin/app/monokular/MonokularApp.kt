package app.monokular

import android.app.Application
import android.os.Build
import app.monokular.core.AppIdentity
import app.monokular.core.MonokuloApi
import app.monokular.core.Tor
import app.monokular.push.BellAddress
import app.monokular.push.BellApi
import app.monokular.push.BellWakeUps
import app.monokular.push.SquareSide
import app.monokular.push.UnifiedPushSubscriptions
import app.monokular.push.WakePushService
import app.monokular.push.WakeUps
import app.monokular.sales.SquareApi
import app.monokular.sales.SquareAuth
import app.monokular.sales.SquareConfig
import app.monokular.sales.SquareException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

class MonokularApp : Application(), WakePushService.Host {
    val graph by lazy { Graph(this) }

    // The connector's service hands the Bell's dings to the graph (decision 93).
    override val pushSubscriptions get() = graph.push
}

/** The app's long-lived parts. */
class Graph(app: Application) {
    val stores = Stores(SecureStore(app))

    val identity = AppIdentity(
        slug = "monokular",
        name = "Monokular",
        version = BuildConfig.VERSION_NAME,
        platform = "android",
        deviceName = listOf(Build.MANUFACTURER, Build.MODEL).distinct().joinToString(" ").take(40),
    )

    val http: OkHttpClient = OkHttpClient.Builder()
        // Onion addresses through Orbot's SOCKS proxy (decision 55); the rest straight out.
        .proxySelector(Tor.Proxies())
        // Tor takes longer to build a circuit.
        .connectTimeout(30, TimeUnit.SECONDS)
        // The event stream sends a comment every 25 s; longer silence is a dead connection.
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val monokulo = MonokuloApi(http, identity)

    fun squareConfig() = SquareConfig(
        // A Bell's application once the till has signed in through it (decision 95).
        applicationId = stores.squareApplicationId ?: BuildConfig.SQUARE_APPLICATION_ID,
        sandbox = BuildConfig.SQUARE_SANDBOX,
        redirectUri = BuildConfig.SQUARE_REDIRECT_URI,
        baseUrl = if (BuildConfig.DEBUG) stores.squareBaseUrl ?: BuildConfig.SQUARE_BASE_URL.ifEmpty { null } else null,
    )

    fun squareAuth() = SquareAuth(http, squareConfig(), stores.tokens)

    fun squareApi() = SquareApi(http, squareConfig(), squareAuth())

    /** The till's push subscription for its Bell (decision 90); a ding goes from here to the running shift. */
    val push = UnifiedPushSubscriptions(app)

    /**
     * Settings › Wake-ups (`docs/design.md` §3): made on first use, which
     * registers with the chosen Bell again (decision 95's app start). A
     * process the connector started for a ding alone never makes it, so a
     * ding outside a shift costs the Bell nothing (decision 93).
     */
    val wakeUps: WakeUps by lazy {
        BellWakeUps(
            push = push,
            distributors = push,
            bells = BellApi(http),
            store = stores.wakeUps,
            monokular = requireNotNull(BellAddress.parse(BuildConfig.BELL_URL, allowHttp = BuildConfig.DEBUG)) {
                "-Pmonokular.bell.url isn't a Bell address this build takes: ${BuildConfig.BELL_URL}"
            },
            squareSandbox = BuildConfig.SQUARE_SANDBOX,
            square = squareSide,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            now = { System.currentTimeMillis() / 1000 },
        )
    }

    /** The till's Square sign-in, as a Bell registration needs it. */
    private val squareSide = object : SquareSide {
        override val signedInApplicationId: String?
            get() = if (stores.withoutSquare || stores.tokens.load() == null) null else squareConfig().applicationId

        override val locationId: String? get() = stores.location?.id

        override suspend fun accessToken(): String? = try {
            squareAuth().accessToken()
        } catch (e: SquareException) {
            if (e.signInAgain) null else throw IOException(e.message, e)
        }
    }

    val runner = TillRunner(this)

    /** The store as last read, this session only. */
    val store = LiveStore(monokulo, onDisconnected = runner::disconnected, onOnionApi = ::onionApi)

    /**
     * Keeps where the store's onion entry is with the connection: a till
     * that connected before Monokulo gave it learns it here. One the store
     * no longer has takes the till off Tor.
     */
    private fun onionApi(url: String?) {
        val connection = stores.connection ?: return
        if (connection.onionApi == url) return
        stores.connection = connection.copy(onionApi = url, tor = connection.tor && url != null)
    }

    /** Monokular's own screen is showing: the bubble steps aside. */
    val appVisible = MutableStateFlow(false)

    /** The cashier wants the bubble (it shows only once Android allows it, too). */
    val bubbleWanted = MutableStateFlow(stores.bubbleOn)

    /** Bumped when the app comes back to the front, so the service sees permissions granted meanwhile. */
    val permissions = MutableStateFlow(0)

    fun setBubble(on: Boolean) {
        stores.bubbleOn = on
        bubbleWanted.value = on
    }
}
