package app.monokular

import app.monokular.core.Connection
import app.monokular.sales.CursorStore
import app.monokular.sales.Pkce
import app.monokular.sales.SquareLocation
import app.monokular.sales.SquareTokens
import app.monokular.sales.TokenStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
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

    /**
     * The store's key and API, nothing else about it (decision 50). One the
     * old app saved is rewritten as the new one the first time it's read:
     * its key at its endpoint's API, without the store and wallet it kept.
     */
    var connection: Connection?
        get() {
            val saved = secure.get("connection") ?: return null
            val connection = Connection.restore(saved) ?: return null
            val now = Connection.save(connection)
            if (now != saved) secure.put("connection", now)
            return connection
        }
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
    }
}
