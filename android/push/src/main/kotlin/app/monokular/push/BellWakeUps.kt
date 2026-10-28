package app.monokular.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Which UnifiedPush distributor would carry the dings (decision 91). */
sealed interface Distributor {
    data class Found(val packageName: String) : Distributor

    data object None : Distributor

    /** Several installed and no default: the cashier picks one of [packages]. */
    data class Choose(val packages: List<String>) : Distributor
}

/** The distributors on the device, as [BellWakeUps] needs them; [UnifiedPushSubscriptions] over the connector. */
interface Distributors {
    /** The saved distributor while it is installed, else the default (then saved), else what there is to choose from. */
    fun resolve(): Distributor

    /** The cashier's pick from [Distributor.Choose]. */
    fun save(packageName: String)

    /** An app came or went, which may have been a distributor. */
    val changes: SharedFlow<Unit>
}

/** The till's Square sign-in, as registering with a Bell needs it. */
interface SquareSide {
    /** The Square application the till is signed in through; null when it isn't signed in (or runs without Square). */
    val signedInApplicationId: String?

    /** The Square location the till watches. */
    val locationId: String?

    /** An access token for [BellClient.register], or null when Square wants the cashier to sign in again. */
    suspend fun accessToken(): String?
}

/**
 * Wake-ups over a Bell (decisions 91, 94, 95, 97): keeps the till
 * registered with the Bell [WakeUpStore.choice] names, and nothing
 * registered when it names none. One pass at a time brings what the Bell
 * holds into line with the choice; [status] says how that went, and the
 * shift paces its timer by it. Everything Android sits behind the
 * interfaces it takes, so it runs on the JVM in tests, on [scope]'s clock.
 */
class BellWakeUps(
    private val push: PushSubscriptions,
    private val distributors: Distributors,
    private val bells: BellClient,
    private val store: WakeUpStore,
    /** The project's Bell: what [WakeUpChoice.MonokularBell] means. */
    private val monokular: BellAddress,
    /** The build signs in to Square's sandbox: a Bell for production accounts is no use to it, nor the other way round. */
    private val squareSandbox: Boolean,
    private val square: SquareSide,
    private val scope: CoroutineScope,
    /** Unix seconds. */
    private val now: () -> Long,
) : WakeUps {
    private val mutableChoice = MutableStateFlow(store.choice)
    override val choice: StateFlow<WakeUpChoice> = mutableChoice.asStateFlow()

    private val mutableStatus = MutableStateFlow(if (store.choice == WakeUpChoice.TimerOnly) WakeUpStatus.Off else WakeUpStatus.Connecting)
    override val status: StateFlow<WakeUpStatus> = mutableStatus.asStateFlow()

    /** Held for each pass and each unregistering: what the Bell holds changes under it only. */
    private val lock = Mutex()
    private var pass: Job? = null
    private var retry: Job? = null
    private var waits = 0

    /** The key and the distributor this process subscribed with; null until it has (the connector is asked again at every start). */
    private var subscribedFor: String? = null
    private var subscribedThrough: String? = null

    @Volatile private var lastDingAt: Long? = null
    @Volatile private var connectedAt: Long? = null

    init {
        scope.launch { push.dings.collect { ding() } }
        scope.launch { push.state.collect(::pushChanged) }
        scope.launch { distributors.changes.collect { distributorsChanged() } }
        // An app start: register again, or finish taking a registration back.
        if (store.choice != WakeUpChoice.TimerOnly || store.registration != null) run(restart = false)
    }

    override fun choose(choice: WakeUpChoice) {
        val current = store.choice
        val retrying = mutableStatus.value is WakeUpStatus.Refused || mutableStatus.value is WakeUpStatus.Unreachable
        if (choice == current && !retrying) return
        store.choice = choice
        if (choice is WakeUpChoice.OwnBell) store.ownBell = choice.bell
        mutableChoice.value = choice
        mutableStatus.value = if (choice == WakeUpChoice.TimerOnly) WakeUpStatus.Off else WakeUpStatus.Connecting
        waits = 0
        run(restart = true)
    }

    override fun chooseDistributor(packageName: String) {
        distributors.save(packageName)
        if (store.choice == WakeUpChoice.TimerOnly) return
        mutableStatus.value = WakeUpStatus.Connecting
        run(restart = true)
    }

    override fun recheck() {
        if (store.choice == WakeUpChoice.TimerOnly) return
        run(restart = false)
    }

    override fun timerFound(sales: Int) {
        if (sales <= 0) return
        val connected = mutableStatus.value as? WakeUpStatus.Connected ?: return
        // A sale the Bell rang for, or one from before the till was connected, isn't a missed wake-up.
        val heard = maxOf(lastDingAt ?: Long.MIN_VALUE, connectedAt ?: Long.MIN_VALUE)
        if (now() - heard <= MISSED_AFTER.inWholeSeconds) return
        mutableStatus.value = WakeUpStatus.MissingWakeups(connected.lastDingAt)
        run(restart = false)
    }

    override suspend fun unregister(forget: Boolean) {
        synchronized(this) { retry?.cancel() }
        pass?.cancelAndJoin()
        lock.withLock {
            store.registration?.let { takeBack(it) }
            unsubscribe()
            if (forget) store.choice = WakeUpChoice.TimerOnly
            mutableChoice.value = store.choice
            mutableStatus.value = if (store.choice == WakeUpChoice.TimerOnly) WakeUpStatus.Off else WakeUpStatus.NoSquare
        }
    }

    /** Start a pass now: [restart] stops one under way (the choice changed); otherwise one under way is enough. */
    private fun run(restart: Boolean) = synchronized(this) {
        retry?.cancel()
        val running = pass
        if (running?.isActive == true && !restart) return@synchronized
        running?.cancel()
        pass = scope.launch {
            running?.join()
            lock.withLock { reconcile() }
        }
    }

    /** A pass: what the Bell holds brought into line with the choice, ending in a [status] (decision 95's order). */
    private suspend fun reconcile() {
        val choice = store.choice
        val bell = choice.bell(monokular)
        store.registration?.takeIf { it.bell != bell }?.let { takeBack(it) }
        if (bell == null) {
            unsubscribe()
            settle(WakeUpStatus.Off)
            return
        }
        val before = mutableStatus.value
        if (before !is WakeUpStatus.Connected && before !is WakeUpStatus.MissingWakeups) mutableStatus.value = WakeUpStatus.Connecting
        val outcome = try {
            register(bell)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BellException) {
            problemStatus(e, now())
        } catch (e: IOException) {
            WakeUpStatus.Unreachable(unreachableReason(e))
        }
        settle(outcome)
    }

    /** Register with [bell], stopping at the first thing in the way; the status that leaves. */
    private suspend fun register(bell: BellAddress): WakeUpStatus {
        val info = bells.info(bell)
        val sandbox = info.environment == SANDBOX
        if (sandbox != squareSandbox) {
            release()
            return WakeUpStatus.Refused(if (sandbox) FOR_SANDBOX else FOR_PRODUCTION)
        }
        val applicationId = square.signedInApplicationId
        val locationId = square.locationId
        if (applicationId != null && applicationId != info.squareApplicationId) {
            release()
            return WakeUpStatus.SignInNeeded(info.squareApplicationId)
        }
        if (applicationId == null || locationId == null) {
            release()
            return WakeUpStatus.NoSquare
        }
        val through = when (val distributor = distributors.resolve()) {
            Distributor.None -> {
                release()
                unsubscribe()
                return WakeUpStatus.NoDistributor
            }
            is Distributor.Choose -> return WakeUpStatus.ChooseDistributor(distributor.packages)
            is Distributor.Found -> distributor.packageName
        }
        val subscription = subscription(info.vapidPublicKey, through).let { state ->
            when (state) {
                is PushState.Subscribed -> state.subscription
                is PushState.Failed -> return pushFailedStatus(state.reason)
                else -> return WakeUpStatus.Unreachable(PUSH_SILENT)
            }
        }
        // The Bell keys a registration by its endpoint: a new one is a new registration (decision 95).
        store.registration?.takeIf { it.subscription.endpoint != subscription.endpoint }?.let { takeBack(it) }
        val token = square.accessToken() ?: run {
            release()
            return WakeUpStatus.NoSquare
        }
        // Once the Bell has the request, its answer is kept whatever happens to the pass.
        withContext(NonCancellable) {
            bells.register(bell, token, locationId, subscription).let { answer ->
                store.registration = BellRegistration(
                    bell = bell,
                    registrationId = answer.registrationId,
                    locationId = locationId,
                    squareApplicationId = answer.squareApplicationId,
                    vapidPublicKey = info.vapidPublicKey,
                    subscription = subscription,
                    registeredAt = now(),
                )
            }
        }
        val current = mutableStatus.value
        return if (current is WakeUpStatus.MissingWakeups) current else WakeUpStatus.Connected(lastDingAt)
    }

    /** The subscription for [vapidPublicKey] through [through]: the one held, or a new one the distributor gives. */
    private suspend fun subscription(vapidPublicKey: String, through: String): PushState {
        val held = push.state.value
        if (held is PushState.Subscribed && subscribedFor == vapidPublicKey && subscribedThrough == through) return held
        // A rotated key, or another distributor: the old subscription ends first (D16).
        val previousKey = subscribedFor ?: store.registration?.vapidPublicKey
        if (previousKey != null && previousKey != vapidPublicKey) push.unsubscribe()
        subscribedFor = vapidPublicKey
        subscribedThrough = through
        push.subscribe(vapidPublicKey)
        return withTimeoutOrNull(SUBSCRIBE_WAIT) { push.state.first { it !is PushState.Subscribing } } ?: PushState.Subscribing
    }

    /** Take a registration back: `DELETE`, tried once within the time it gets, and forgotten here whatever the answer (decision 97). */
    private suspend fun takeBack(registration: BellRegistration) {
        withContext(NonCancellable) {
            withTimeoutOrNull(UNREGISTER_WAIT) {
                try {
                    bells.unregister(registration.bell, registration.registrationId)
                } catch (_: IOException) {
                    // The Bell drops a dead endpoint by itself (its failure limit).
                }
            }
            store.registration = null
        }
    }

    /** No registration while the till can't use it: the Bell would ring for nothing. */
    private suspend fun release() {
        store.registration?.let { takeBack(it) }
    }

    /** End the distributor's subscription, one an earlier run of the app made included. */
    private fun unsubscribe() {
        subscribedFor = null
        subscribedThrough = null
        push.unsubscribe()
    }

    /** The pass is over: [outcome] shown, and a retry set for the statuses that retry by themselves (D5). */
    private fun settle(outcome: WakeUpStatus) {
        if (outcome is WakeUpStatus.Connected && mutableStatus.value !is WakeUpStatus.Connected) connectedAt = now()
        mutableStatus.value = outcome
        val wait: Duration = when {
            outcome is WakeUpStatus.Unreachable -> backoff()
            outcome is WakeUpStatus.Refused && outcome.retryAt != null -> maxOf(0, outcome.retryAt - now()).seconds
            else -> {
                waits = 0
                return
            }
        }
        synchronized(this) {
            retry = scope.launch {
                delay(wait)
                run(restart = false)
            }
        }
    }

    /** 1 minute, doubling to 15 (D5). */
    private fun backoff(): Duration {
        val wait = minOf(FIRST_RETRY * (1 shl minOf(waits, 4)), LAST_RETRY)
        waits++
        return wait
    }

    private fun ding() {
        val at = now()
        lastDingAt = at
        mutableStatus.update { status ->
            when (status) {
                is WakeUpStatus.Connected, is WakeUpStatus.MissingWakeups -> WakeUpStatus.Connected(at)
                else -> status
            }
        }
    }

    /** The distributor changed things outside a pass: a new endpoint, or the subscription ended. */
    private fun pushChanged(state: PushState) {
        if (store.choice == WakeUpChoice.TimerOnly) return
        val held = store.registration ?: return
        when {
            state is PushState.Subscribed && state.subscription != held.subscription -> run(restart = false)
            state is PushState.Failed && state.reason == PushFailure.Lost -> scope.launch { distributorGone() }
        }
    }

    /** The distributor ended the subscription: the Bell's registration goes with it (decision 91). */
    private suspend fun distributorGone() {
        pass?.cancelAndJoin()
        lock.withLock {
            store.registration?.let { takeBack(it) }
            subscribedFor = null
            subscribedThrough = null
            if (store.choice != WakeUpChoice.TimerOnly) settle(WakeUpStatus.NoDistributor)
        }
    }

    /** An app came or went: look again when waiting for a distributor, or when the one in use may be gone. */
    private fun distributorsChanged() {
        if (store.choice == WakeUpChoice.TimerOnly) return
        val waiting = mutableStatus.value is WakeUpStatus.NoDistributor || mutableStatus.value is WakeUpStatus.ChooseDistributor
        val through = subscribedThrough
        val moved = through != null && distributors.resolve() != Distributor.Found(through)
        if (waiting || moved) run(restart = false)
    }

    companion object {
        /** A timer-found sale with no ding this long before it is a missed wake-up (decision 94). */
        val MISSED_AFTER = 60.seconds
        val FIRST_RETRY = 1.minutes
        val LAST_RETRY = 15.minutes
        val UNREGISTER_WAIT = 10.seconds

        /** How long the distributor gets to answer with an endpoint (it may have to reach its server). */
        val SUBSCRIBE_WAIT = 30.seconds

        const val FOR_SANDBOX = "it's for Square's sandbox"
        const val FOR_PRODUCTION = "it's for Square's production accounts"
        const val PUSH_REFUSED = "the UnifiedPush app refused"
        const val PUSH_NO_KEYS = "the UnifiedPush app gave no keys"
        const val PUSH_SILENT = "the UnifiedPush app didn't answer"
        const val PUSH_OFFLINE = "the UnifiedPush app has no connection"

        /** Why the Bell couldn't be reached, in the cashier's words (A1.7). */
        fun unreachableReason(e: IOException): String = when (e) {
            is NotABellException -> "it isn't a Bell"
            is BellException -> "it answered ${e.status}"
            is InterruptedIOException -> "it didn't answer"
            else -> "no connection"
        }

        /** What a Bell's problem means for the till (decision 95, A1.7); [now] is Unix seconds. */
        fun problemStatus(e: BellException, now: Long): WakeUpStatus = when (e.type) {
            "square-refused" -> WakeUpStatus.Refused("Square didn't confirm this location. Sign in to Square again.")
            "too-many-registrations" -> WakeUpStatus.Refused("this location already has 32 tills registered")
            "endpoint-registered" -> WakeUpStatus.Refused("this device is registered under another Square account")
            "rate-limited" -> {
                val wait = (e.retryAfter ?: FIRST_RETRY.inWholeSeconds).coerceAtLeast(1)
                WakeUpStatus.Refused("too many registrations from this network: trying again in $wait seconds", retryAt = now + wait)
            }
            "square-unavailable", "internal" -> WakeUpStatus.Unreachable(unreachableReason(e))
            else ->
                if (e.status >= 500) WakeUpStatus.Unreachable(unreachableReason(e))
                else WakeUpStatus.Refused(e.detail ?: "it answered ${e.status}")
        }

        /** The distributor couldn't make the subscription (decision 95). */
        fun pushFailedStatus(reason: PushFailure): WakeUpStatus = when (reason) {
            PushFailure.NoDistributor, PushFailure.Lost -> WakeUpStatus.NoDistributor
            PushFailure.Network -> WakeUpStatus.Unreachable(PUSH_OFFLINE)
            PushFailure.NoKeys -> WakeUpStatus.Unreachable(PUSH_NO_KEYS)
            PushFailure.ActionRequired, PushFailure.Other -> WakeUpStatus.Unreachable(PUSH_REFUSED)
        }
    }
}
