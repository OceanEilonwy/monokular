package app.monokular.push

import android.util.Log
import app.monokular.core.SaleEvent
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * The distributor's side of UnifiedPush. The connector decrypts Web Push
 * messages (RFC 8291) before they reach [onMessage], so the content here is
 * the sale event's JSON.
 */
class MonokularPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet
        if (keys == null) {
            // Without keys Monokulo can't encrypt for us; it only sends encrypted pushes.
            Log.w(TAG, "distributor gave an endpoint without Web Push keys for $instance")
            Push.onEndpoint(null)
            return
        }
        Push.onEndpoint(PushTarget(endpoint.url, keys.pubKey, keys.auth))
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) {
            Log.w(TAG, "dropped a push for $instance that the connector couldn't decrypt")
            return
        }
        runCatching { SaleEvent.decode(message.content.decodeToString()) }
            .onSuccess(Push::onEvent)
            .onFailure { Log.w(TAG, "dropped a push for $instance that isn't a sale event", it) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "push registration for $instance failed: $reason")
        Push.onEndpoint(null)
    }

    override fun onUnregistered(instance: String) {
        Push.onEndpoint(null)
    }

    private companion object {
        const val TAG = "MonokularPush"
    }
}
