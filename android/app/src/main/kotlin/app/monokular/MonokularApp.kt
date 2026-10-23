package app.monokular

import android.app.Application
import android.os.Build
import app.monokular.core.AppIdentity
import app.monokular.core.MonokuloApi
import app.monokular.sales.SquareApi
import app.monokular.sales.SquareAuth
import app.monokular.sales.SquareConfig
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MonokularApp : Application() {
    val graph by lazy { Graph(this) }
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
        .connectTimeout(15, TimeUnit.SECONDS)
        // The event stream sends a comment every 25 s; longer silence is a dead connection.
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val monokulo = MonokuloApi(http, identity)

    fun squareConfig() = SquareConfig(
        applicationId = BuildConfig.SQUARE_APPLICATION_ID,
        sandbox = BuildConfig.SQUARE_SANDBOX,
        redirectUri = BuildConfig.SQUARE_REDIRECT_URI,
        baseUrl = if (BuildConfig.DEBUG) stores.squareBaseUrl ?: BuildConfig.SQUARE_BASE_URL.ifEmpty { null } else null,
    )

    fun squareAuth() = SquareAuth(http, squareConfig(), stores.tokens)

    fun squareApi() = SquareApi(http, squareConfig(), squareAuth())

    val runner = TillRunner(this)

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
