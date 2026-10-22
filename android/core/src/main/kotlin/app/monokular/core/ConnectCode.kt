package app.monokular.core

import java.net.URI

/**
 * A connect code from a store's Monokulo dashboard (`docs/design.md` §2):
 * `https://pay.yourshop.com/apps/connect#token=ct_…`, scanned from its QR
 * code or pasted.
 *
 * The link is on the store's own Monokulo, so the app learns where to
 * connect from it, and the token travels in the fragment, which a browser
 * never sends to a server.
 */
data class ConnectCode(
    /** The store's Monokulo, `https://host[:port]`: where the token is redeemed. */
    val baseUrl: String,
    val token: String,
) {
    /** The host shown on the confirm screen. */
    val host: String get() = URI(baseUrl).authority

    companion object {
        private const val PATH = "/apps/connect"
        private val TOKEN = Regex("ct_[0-9a-f]{64}")

        /**
         * The code in [text], or null for anything that isn't one. Plain
         * `http` is only taken for an onion address, or with [allowCleartext]
         * (debug builds, for a Monokulo on the developer's machine).
         */
        fun parse(text: String, allowCleartext: Boolean = false): ConnectCode? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val secure = when (uri.scheme?.lowercase()) {
                "https" -> true
                "http" -> host.endsWith(".onion") || allowCleartext
                else -> false
            }
            if (!secure || uri.rawUserInfo != null || uri.rawQuery != null) return null
            if (uri.rawPath != PATH) return null
            val token = uri.rawFragment
                ?.split('&')
                ?.firstNotNullOfOrNull { it.removePrefix("token=").takeIf { v -> v != it } }
                ?.takeIf { TOKEN.matches(it) }
                ?: return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return ConnectCode("${uri.scheme.lowercase()}://$host$port", token)
        }
    }
}
