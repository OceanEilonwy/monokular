package app.monokular

import app.monokular.core.Connection
import app.monokular.push.BellRegistration
import app.monokular.push.WakeUpChoice
import app.monokular.push.WakeUpStore
import app.monokular.sales.CursorStore
import app.monokular.sales.Pkce
import app.monokular.sales.ShiftStore
import app.monokular.sales.SquareLocation
import app.monokular.sales.SquareTokens
import app.monokular.sales.TillSale
import app.monokular.sales.TokenStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Everything the app keeps, by name, in its [SecureStore]. */
class Stores(private val secure: SecureStore) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun <T> read(name: String, serializer: KSerializer<T>): T? =
        secure.get(name)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    private fun <T> write(name: String, serializer: KSerializer<T>, value: T?) =
        secure.put(name, value?.let { json.encodeToString(serializer, it) })

    /** The store's key and API, nothing else about it (decision 50). */
    var connection: Connection?
        get() = secure.get("connection")?.let(Connection::restore)
        set(value) = secure.put("connection", value?.let(Connection::save))

    /** The Square location this till watches. */
    var location: SquareLocation?
        get() = read("square.location", SquareLocation.serializer())
        set(value) = write("square.location", SquareLocation.serializer(), value)

    /** The cashier chose to run without Square (sales from other tills only). */
    var withoutSquare: Boolean
        get() = secure.get("square.skipped") == "true"
        set(value) = secure.put("square.skipped", value.toString().takeIf { value })

    /** Whether the till watches for sales: on until the cashier stops it. */
    var shiftOn: Boolean
        get() = secure.get("shift.on") != "false"
        set(value) = secure.put("shift.on", value.toString())

    /** When this shift began, Unix seconds: the cashier turned watching on (the shift's "since 9:02 am"). */
    var shiftStartedAt: Long?
        get() = secure.get("shift.started")?.toLongOrNull()
        set(value) = secure.put("shift.started", value?.toString())

    /** The cashier went through setup to the end (OnbReady's "Start Monokular"). */
    var setupDone: Boolean
        get() = secure.get("setup.done") == "true"
        set(value) = secure.put("setup.done", value.toString().takeIf { value })

    /** Show the bubble over other apps when allowed; false when the cashier chose notifications only. */
    var bubbleOn: Boolean
        get() = secure.get("bubble.on") != "false"
        set(value) = secure.put("bubble.on", value.toString())

    /** Where the cashier left the bubble: which side, and how far down (px). */
    var bubbleOnLeft: Boolean
        get() = secure.get("bubble.left") == "true"
        set(value) = secure.put("bubble.left", value.toString())

    var bubbleY: Int?
        get() = secure.get("bubble.y")?.toIntOrNull()
        set(value) = secure.put("bubble.y", value?.toString())

    /** A Square sign-in under way, kept in case Android ends the app meanwhile. */
    var pendingSignIn: Pkce?
        get() = read("square.pending", PkceState.serializer())?.let { Pkce(it.verifier, it.state) }
        set(value) = write("square.pending", PkceState.serializer(), value?.let { PkceState(it.verifier, it.state) })

    /**
     * The Square application the till signs in through when it isn't the
     * build's own: a Bell's (`docs/design.md` §3, decision 95). Set once a
     * sign-in through it has finished, never before.
     */
    var squareApplicationId: String?
        get() = secure.get("square.application")
        set(value) = secure.put("square.application", value?.takeIf { it != BuildConfig.SQUARE_APPLICATION_ID })

    /** Settings › Wake-ups: the choice, and the registration the Bell holds while there is one. */
    val wakeUps = object : WakeUpStore {
        override var choice: WakeUpChoice
            get() = read("wakeups.choice", WakeUpChoice.serializer()) ?: WakeUpChoice.TimerOnly
            set(value) = write("wakeups.choice", WakeUpChoice.serializer(), value.takeIf { it != WakeUpChoice.TimerOnly })

        override var registration: BellRegistration?
            get() = read("wakeups.registration", BellRegistration.serializer())
            set(value) = write("wakeups.registration", BellRegistration.serializer(), value)
    }

    /** Debug builds: a stand-in Square on the developer's machine. */
    var squareBaseUrl: String?
        get() = secure.get("debug.square.base")
        set(value) = secure.put("debug.square.base", value?.takeIf { it.isNotBlank() })

    @Serializable
    private data class PkceState(val verifier: String, val state: String)

    val tokens = object : TokenStore {
        override fun load() = read("square.tokens", SquareTokens.serializer())
        override fun save(tokens: SquareTokens?) = write("square.tokens", SquareTokens.serializer(), tokens)
    }

    val cursor = object : CursorStore {
        override fun since() = secure.get("square.since")
        override fun handled(): Set<String> = read("square.handled", SetSerializer(String.serializer())) ?: emptySet()
        override fun save(since: String, handled: Set<String>) {
            secure.put("square.since", since)
            write("square.handled", SetSerializer(String.serializer()), handled)
        }
    }

    /** This shift's sales, receipts and all, encrypted like everything else here (decision 64). */
    val shift = object : ShiftStore {
        override fun load() = read("shift.sales", ListSerializer(TillSale.serializer())).orEmpty()
        override fun save(sales: List<TillSale>) = write("shift.sales", ListSerializer(TillSale.serializer()), sales.ifEmpty { null })
    }

    /** Forgets the store and everything about this till's Square. */
    fun forgetAll() {
        connection = null
        setupDone = false
        location = null
        withoutSquare = false
        pendingSignIn = null
        tokens.save(null)
        secure.put("square.since", null)
        secure.put("square.handled", null)
        shift.save(emptyList())
        shiftStartedAt = null
        squareApplicationId = null
        wakeUps.choice = WakeUpChoice.TimerOnly
        wakeUps.registration = null
    }
}
