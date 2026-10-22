package app.monokular.push

import android.content.Context
import app.monokular.core.SaleEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.unifiedpush.android.connector.UnifiedPush

/**
 * A Web Push subscription the app hands to its store's Monokulo
 * (`PUT /api/v1/apps/push-targets/{client_id}`, `docs/design.md` §3).
 */
data class PushTarget(val endpoint: String, val p256dh: String, val auth: String)

/** What push tells the rest of the app: the current subscription and each sale event. */
object Push {
    private val targetState = MutableStateFlow<PushTarget?>(null)
    private val eventFlow = MutableSharedFlow<SaleEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val target: StateFlow<PushTarget?> = targetState.asStateFlow()
    val events: SharedFlow<SaleEvent> = eventFlow.asSharedFlow()

    /**
     * Registers with the user's UnifiedPush distributor for one store, restricted
     * to that store's VAPID key so only its Monokulo can push to the endpoint.
     * One instance per connected store, named by its app connection id.
     */
    fun register(context: Context, appConnectionId: String, vapidPublicKey: String) {
        UnifiedPush.register(context, appConnectionId, null, vapidPublicKey)
    }

    fun unregister(context: Context, appConnectionId: String) {
        UnifiedPush.unregister(context, appConnectionId)
    }

    internal fun onEndpoint(target: PushTarget?) {
        targetState.value = target
    }

    internal fun onEvent(event: SaleEvent) {
        eventFlow.tryEmit(event)
    }
}
