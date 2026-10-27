package app.monokular.push

import app.monokular.core.Tor
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where a Monokular Bell is (`docs/design.md` §3): `https://bell.example`,
 * or `http://….onion`, which Orbot carries (decision 55), with a path when
 * the Bell is served under one. Made by [parse], which refuses anything
 * else, so a kept one is always a Bell the app would talk to.
 */
@Serializable
data class BellAddress(val url: String) {
    init {
        require(problem(url, allowHttp = true) == null) { "not a Bell address: $url" }
    }

    /** The host, with its port when it isn't the scheme's own: what the screens show. */
    val host: String
        get() {
            val parsed = url.toHttpUrlOrNull() ?: return url
            return if (parsed.port == HttpUrl.defaultPort(parsed.scheme)) parsed.host else "${parsed.host}:${parsed.port}"
        }

    /** The Bell is on an onion address, so Orbot must be running to reach it. */
    val onion: Boolean get() = Tor.isOnion(url)

    /** The route's address: [url] followed by [path] (`/v1/info`). */
    fun route(path: String): String = url + path

    override fun toString(): String = url

    companion object {
        /**
         * The Bell address [text] names, or null when it isn't one ([problem]
         * says why). A missing scheme means https; a trailing slash is
         * dropped. Plain http reaches onion addresses only, and anything in
         * a debug build ([allowHttp]: a Bell on the developer's machine).
         */
        fun parse(text: String, allowHttp: Boolean = false): BellAddress? {
            if (problem(text, allowHttp) != null) return null
            val parsed = parsed(text) ?: return null
            return BellAddress(parsed.toString().trimEnd('/'))
        }

        /** Why [text] isn't a Bell address, in the cashier's words, or null when it is one. */
        fun problem(text: String, allowHttp: Boolean = false): String? {
            if (text.isBlank()) return "Enter the Bell's web address, such as https://bell.example.org."
            val parsed = parsed(text) ?: return "That isn't a web address."
            if (parsed.scheme == "http" && !allowHttp && !parsed.host.lowercase().endsWith(".onion")) {
                return "A Bell's address must start with https:// (or be an onion address)."
            }
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return "Leave the user name and password out of the address."
            if (parsed.query != null || parsed.fragment != null) return "Leave out everything after the Bell's path."
            return null
        }

        /** [text] as a web address, https when it names no scheme. */
        private fun parsed(text: String): HttpUrl? {
            val trimmed = text.trim()
            return (if ("://" in trimmed) trimmed else "https://$trimmed").toHttpUrlOrNull()
        }
    }
}
