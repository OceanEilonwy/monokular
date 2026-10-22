package app.monokular.core

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A connect link from a store's Monokulo dashboard
 * (`docs/design.md` §2): `https://monokular.app/connect#host=…&token=…`.
 *
 * The store's host and the one-time token travel in the fragment, which the
 * browser never sends to monokular.app, so only the app sees them.
 */
data class ConnectLink(val host: String, val token: String) {
    /** The store's Monokulo, where the token is redeemed. */
    val storeUrl: String get() = "https://$host"

    companion object {
        private const val LINK_HOST = "monokular.app"
        private const val LINK_PATH = "/connect"
        private val TOKEN = Regex("ct_[A-Za-z0-9_-]{16,128}")

        /** The link's host and token, or null for anything that isn't a valid connect link. */
        fun parse(link: String): ConnectLink? {
            val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host != LINK_HOST || uri.path != LINK_PATH) return null
            val params = uri.rawFragment?.split('&')
                ?.mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq <= 0) null else part.substring(0, eq) to decode(part.substring(eq + 1))
                }
                ?.toMap() ?: return null
            val host = params["host"]?.takeIf(::isStoreHost) ?: return null
            val token = params["token"]?.takeIf { TOKEN.matches(it) } ?: return null
            return ConnectLink(host, token)
        }

        private fun decode(s: String): String = URLDecoder.decode(s, StandardCharsets.UTF_8)

        /**
         * A host name, optionally with a port: no scheme, path, credentials or
         * spaces, so the link can't send the app anywhere but a store's host.
         */
        private fun isStoreHost(host: String): Boolean {
            if (host.isEmpty() || host.length > 253) return false
            val uri = runCatching { URI("https://$host") }.getOrNull() ?: return false
            return uri.host != null && uri.userInfo == null && uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null && uri.rawFragment == null && uri.authority == host
        }
    }
}
