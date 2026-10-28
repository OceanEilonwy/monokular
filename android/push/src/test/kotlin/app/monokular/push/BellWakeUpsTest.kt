package app.monokular.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Wake-ups' registration lifecycle against a recording Bell, distributor and
 * Square (A13): every transition of A3, A6, A7, A10 and A11, on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BellWakeUpsTest {
    private val monokular = BellAddress.parse("bell.monokulo.com")!!
    private val own = BellAddress.parse("bell.thebeanroom.coffee")!!

    private class FakeBell : BellClient {
        val calls = mutableListOf<String>()
        val infos = mutableMapOf<BellAddress, () -> BellInfo>()
        var register: () -> BellRegistered = { BellRegistered("br_${++ids}", APP) }
        var unregister: suspend () -> Unit = {}
        var ids = 0

        override suspend fun info(bell: BellAddress): BellInfo {
            calls += "GET ${bell.display}"
            return (infos[bell] ?: { throw ConnectException() })()
        }

        override suspend fun register(bell: BellAddress, accessToken: String, locationId: String, subscription: PushSubscription): BellRegistered {
            calls += "POST ${bell.display} $accessToken $locationId ${subscription.endpoint}"
            return register()
        }

        override suspend fun unregister(bell: BellAddress, registrationId: String) {
            calls += "DELETE ${bell.display} $registrationId"
            unregister()
        }
    }

    private class FakePush : PushSubscriptions {
        val calls = mutableListOf<String>()
        var endpoint = "https://ntfy.sh/up1"
        /** What subscribing comes to; null leaves it waiting. */
        var answer: (String) -> PushState? = { PushState.Subscribed(PushSubscription(endpoint, "BKey", "auth")) }
        override val state = MutableStateFlow<PushState>(PushState.None)
        override val dings = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override fun distributors() = emptyList<String>()

        override fun subscribe(vapidPublicKey: String) {
            calls += "subscribe $vapidPublicKey"
            state.value = PushState.Subscribing
            answer(vapidPublicKey)?.let { state.value = it }
        }

        override fun unsubscribe() {
            calls += "unsubscribe"
            state.value = PushState.None
        }
    }

    private class FakeDistributors : Distributors {
        var resolved: Distributor = Distributor.Found("io.heckel.ntfy")
        val saved = mutableListOf<String>()
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override fun resolve() = resolved
        override fun save(packageName: String) {
            saved += packageName
            resolved = Distributor.Found(packageName)
        }
    }

    private class MemoryStore : WakeUpStore {
        override var choice: WakeUpChoice = WakeUpChoice.TimerOnly
        override var ownBell: BellAddress? = null
        override var registration: BellRegistration? = null
    }

    private class FakeSquare : SquareSide {
        override var signedInApplicationId: String? = APP
        override var locationId: String? = "L1"
        var token: String? = "EAAAtoken"
        override suspend fun accessToken() = token
    }

    private val bell = FakeBell()
    private val push = FakePush()
    private val distributors = FakeDistributors()
    private val store = MemoryStore()
    private val square = FakeSquare()

    private fun info(app: String = APP, environment: String = SANDBOX, key: String = KEY) = { BellInfo(app, environment, key, "0.1.0") }

    init {
        bell.infos[monokular] = info()
        bell.infos[own] = info()
    }

    /** The app's process: a new start ends the one before. */
    private var process: Job? = null

    /** A fresh start of the app over [store], on this test's clock. */
    private fun TestScope.start(): BellWakeUps {
        process?.cancel()
        val job = Job(backgroundScope.coroutineContext[Job]).also { process = it }
        return BellWakeUps(
            push = push,
            distributors = distributors,
            bells = bell,
            store = store,
            monokular = monokular,
            squareSandbox = true,
            square = square,
            scope = CoroutineScope(backgroundScope.coroutineContext + job),
            now = { testScheduler.currentTime / 1000 },
        ).also { runCurrent() }
    }

    /** Every status [flow] takes from now on. */
    private fun TestScope.statuses(flow: StateFlow<WakeUpStatus>): List<WakeUpStatus> {
        val seen = mutableListOf<WakeUpStatus>()
        backgroundScope.launch { flow.collect { seen += it } }
        runCurrent()
        return seen
    }

    private fun TestScope.connected(choice: WakeUpChoice = WakeUpChoice.MonokularBell): BellWakeUps = start().also {
        it.choose(choice)
        runCurrent()
        assertEquals(WakeUpStatus.Connected(null), it.status.value)
        bell.calls.clear()
        push.calls.clear()
    }

    private fun TestScope.wait(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    @Test
    fun `the pace is 30 s while connected and 2 s for every other status`() {
        val statuses = listOf(
            WakeUpStatus.Off, WakeUpStatus.NoSquare, WakeUpStatus.NoDistributor, WakeUpStatus.ChooseDistributor(listOf("a", "b")),
            WakeUpStatus.Connecting, WakeUpStatus.MissingWakeups(5), WakeUpStatus.Unreachable("no connection"),
            WakeUpStatus.Refused("x", 5), WakeUpStatus.SignInNeeded("sq0idp-x"),
        )
        statuses.forEach { assertEquals(2.seconds, it.interval, "$it") }
        assertEquals(30.seconds, WakeUpStatus.Connected(null).interval)
        assertEquals(30.seconds, WakeUpStatus.Connected(5).interval)
    }

    @Test
    fun `timer only, a fresh install, asks nothing of anyone (A2_11)`() = runTest {
        val wakeUps = start()
        wait(30.minutes)
        assertEquals(WakeUpChoice.TimerOnly, wakeUps.choice.value)
        assertEquals(WakeUpStatus.Off, wakeUps.status.value)
        assertEquals(emptyList(), bell.calls)
        assertEquals(emptyList(), push.calls)
        wakeUps.recheck()
        wakeUps.timerFound(3)
        runCurrent()
        assertEquals(emptyList(), bell.calls)
    }

    @Test
    fun `picking the Bell saves it, says Connecting at once, then reads, subscribes and registers (A3_13, A3_14)`() = runTest {
        val wakeUps = start()
        wakeUps.choose(WakeUpChoice.MonokularBell)
        assertEquals(WakeUpChoice.MonokularBell, store.choice)
        assertEquals(WakeUpChoice.MonokularBell, wakeUps.choice.value)
        assertEquals(WakeUpStatus.Connecting, wakeUps.status.value)
        runCurrent()
        assertEquals(WakeUpStatus.Connected(null), wakeUps.status.value)
        assertEquals(listOf("GET bell.monokulo.com", "POST bell.monokulo.com EAAAtoken L1 https://ntfy.sh/up1"), bell.calls)
        assertEquals(listOf("subscribe $KEY"), push.calls)
        val registration = assertNotNull(store.registration)
        assertEquals("br_1", registration.registrationId)
        assertEquals(monokular, registration.bell)
        assertEquals(KEY, registration.vapidPublicKey)
        assertEquals("L1", registration.locationId)
        assertEquals("https://ntfy.sh/up1", registration.subscription.endpoint)
    }

    @Test
    fun `picking My own Bell keeps its address for the row`() = runTest {
        val wakeUps = start()
        wakeUps.choose(WakeUpChoice.OwnBell(own))
        runCurrent()
        assertEquals(own, store.ownBell)
        assertEquals(WakeUpStatus.Connected(null), wakeUps.status.value)
        assertEquals("GET bell.thebeanroom.coffee", bell.calls.first())
    }

    @Test
    fun `picking the Bell already chosen does nothing (A3_18)`() = runTest {
        val wakeUps = connected()
        wakeUps.choose(WakeUpChoice.MonokularBell)
        runCurrent()
        assertEquals(emptyList(), bell.calls)
        assertEquals(WakeUpStatus.Connected(null), wakeUps.status.value)
    }

    @Test
    fun `a Bell that can't be reached is Unreachable, retried 1, 2, 4 minutes on to 15, and connects when it's back (A11_1, A4_20)`() = runTest {
        bell.infos.remove(monokular)
        val wakeUps = start()
        wakeUps.choose(WakeUpChoice.MonokularBell)
        runCurrent()
        assertEquals(WakeUpStatus.Unreachable("no connection"), wakeUps.status.value)
        assertEquals(listOf("GET bell.monokulo.com"), bell.calls)
        assertEquals(emptyList(), push.calls, "nothing subscribed for a Bell not read")
        val gaps = listOf(1, 2, 4, 8, 15, 15).map { it.minutes }
        gaps.forEachIndexed { i, gap ->
            wait(gap - 1.seconds)
            assertEquals(i + 1, bell.calls.size, "not before ${gap}")
            wait(1.seconds)
            assertEquals(i + 2, bell.calls.size, "after $gap")
        }
        bell.infos[monokular] = info()
        wait(15.minutes)
        assertEquals(WakeUpStatus.Connected(null), wakeUps.status.value)
        assertTrue(bell.calls.last().startsWith("POST"))
    }

    @Test
    fun `an unreachable Bell's reasons, in the cashier's words`() {
        assertEquals("no connection", BellWakeUps.unreachableReason(ConnectException()))
        assertEquals("no connection", BellWakeUps.unreachableReason(IOException("no route")))
        assertEquals("it didn't answer", BellWakeUps.unreachableReason(SocketTimeoutException()))
        assertEquals("it didn't answer", BellWakeUps.unreachableReason(java.io.InterruptedIOException("timeout")))
        assertEquals("it answered 503", BellWakeUps.unreachableReason(BellException(503, null, null, null)))
        assertEquals("it isn't a Bell", BellWakeUps.unreachableReason(NotABellException()))
    }

    @Test
    fun `an answer that isn't a Bell's is Unreachable and nothing is posted (A11_7)`() = runTest {
        bell.infos[own] = { throw NotABellException() }
        val wakeUps = start()
        wakeUps.choose(WakeUpChoice.OwnBell(own))
        runCurrent()
        assertEquals(WakeUpStatus.Unreachable("it isn't a Bell"), wakeUps.status.value)
        assertEquals(listOf("GET bell.thebeanroom.coffee"), bell.calls)
    }

    @Test
    fun `a Bell for the other environment is refused for good, with no sign-in and no post (A3_14)`() = runTest {
        bell.infos[monokular] = info(app = "sq0idp-other", environment = PRODUCTION)
        val wakeUps = start()
        wakeUps.choose(WakeUpChoice.MonokularBell)
        runCurrent()
        assertEquals(WakeUpStatus.Refused(BellWakeUps.FOR_PRODUCTION), wakeUps.status.value)
        wait(60.minutes)
        assertEquals(listOf("GET bell.monokulo.com"), bell.calls, "no retry on its own")
        assertEquals(emptyList(), push.calls)
        wakeUps.choose(WakeUpChoice.MonokularBell)
        runCurrent()
        assertEquals(2, bell.calls.size, "re-picking a refused Bell tries again")
    }

    @Test
    fun `a sandbox Bell in a production build is refused too`() = runTest {
        val wakeUps = BellWakeUps(push, distributors, bell, store, monokular, squareSandbox = false, square, backgroundScope, { 0 })
        wakeUps.choose(WakeUpChoice.MonokularBell)
        runCurrent()
        assertEquals(WakeUpStatus.Refused(BellWakeUps.FOR_SANDBOX), wakeUps.status.value)
    }

    @Test
    fun `a Bell on another Square application asks for a sign-in through it, and registers nothing until then (A6_26)`() = runTest {
        bell.infos[monokular] = info(app = "sandbox-sq0idb-other")
        val wakeUps = start()
        wakeUps.choose(WakeUpChoice.MonokularBell)
        runCurrent()
        assertEquals(WakeUpStatus.SignInNeeded("sandbox-sq0idb-other"), wakeUps.status.value)
        assertEquals(listOf("GET bell.monokulo.com"), bell.calls)
        assertEquals(emptyList(), push.calls, "no distributor registration")
        wait(60.minutes)
        assertEquals(1, bell.calls.size, "no retry on its own")
        // The sign-in through the Bell's application finished; the app asks again.
        square.signedInApplicationId = "sandbox-sq0idb-other"
        wakeUps.recheck()
        runCurrent()
        assertEquals(WakeUpStatus.Connected(null), wakeUps.status.value)
    }

    @Test
    fun `without a Square sign-in the Bell waits for one (A3_14)`() = runTest {
        square.signedInApplicationId = null
        val wakeUps = start()
        wakeUps.choose(WakeUpChoice.MonokularBell)
        runCurrent()
        assertEquals(WakeUpStatus.NoSquare, wakeUps.status.value)
        assertEquals(WakeUpChoice.MonokularBell, store.choice)
        assertEquals(listOf("GET bell.monokulo.com"), bell.calls)
    }

    @Test
    fun `a sign-in Square ended at the token is NoSquare, and takes back what was registered`() = runTest {
        val wakeUps = connected()
        square.token = null
        wakeUps.recheck()
        runCurrent()
        assertEquals(WakeUpStatus.NoSquare, wakeUps.status.value)
        assertEquals(listOf("GET bell.monokulo.com", "DELETE bell.monokulo.com br_1"), bell.calls)
        assertNull(store.registration)
    }

    @Test
    fun `no distributor is NoDistributor with the choice kept, and one installed connects by itself (A7_29, A7_30)`() = runTest {
        distributors.resolved = Distributor.None
        val wakeUps = start()
        wakeUps.choose(WakeUpChoice.MonokularBell)
        runCurrent()
        assertEquals(WakeUpStatus.NoDistributor, wakeUps.status.value)
        assertEquals(WakeUpChoice.MonokularBell, store.choice)
        assertEquals(listOf("GET bell.monokulo.com"), bell.calls, "no POST")
        distributors.resolved = Distributor.Found("io.heckel.ntfy")
        distributors.changes.emit(Unit)
        runCurrent()
        assertEquals(WakeUpStatus.Connected(null), wakeUps.status.value)
    }

    @Test
    fun `several distributors and no default, the cashier picks, and it is saved (A7_31)`() = runTest {
        distributors.resolved = Distributor.Choose(listOf("io.heckel.ntfy", "org.unifiedpush.distributor.sunup"))
        val wakeUps = start()
        wakeUps.choose(WakeUpChoice.MonokularBell)
        runCurrent()
        assertEquals(WakeUpStatus.ChooseDistributor(listOf("io.heckel.ntfy", "org.unifiedpush.distributor.sunup")), wakeUps.status.value)
        wakeUps.chooseDistributor("org.unifiedpush.distributor.sunup")
        assertEquals(WakeUpStatus.Connecting, wakeUps.status.value)
        runCurrent()
        assertEquals(listOf("org.unifiedpush.distributor.sunup"), distributors.saved)
        assertEquals(WakeUpStatus.Connected(null), wakeUps.status.value)
    }

    @Test
    fun `the distributor failing is Unreachable with its reason, retried`() = runTest {
        push.answer = { PushState.Failed(PushFailure.Other) }
        val wakeUps = start()
        wakeUps.choose(WakeUpChoice.MonokularBell)
        runCurrent()
        assertEquals(WakeUpStatus.Unreachable(BellWakeUps.PUSH_REFUSED), wakeUps.status.value)
        push.answer = { PushState.Failed(PushFailure.NoKeys) }
        wait(1.minutes)
        assertEquals(WakeUpStatus.Unreachable(BellWakeUps.PUSH_NO_KEYS), wakeUps.status.value)
        push.answer = { null }
        wait(2.minutes + BellWakeUps.SUBSCRIBE_WAIT)
        assertEquals(WakeUpStatus.Unreachable(BellWakeUps.PUSH_SILENT), wakeUps.status.value)
        assertFalse(bell.calls.any { it.startsWith("POST") })
        assertEquals(BellWakeUps.PUSH_OFFLINE, (BellWakeUps.pushFailedStatus(PushFailure.Network) as WakeUpStatus.Unreachable).reason)
        assertEquals(WakeUpStatus.NoDistributor, BellWakeUps.pushFailedStatus(PushFailure.NoDistributor))
    }

    @Test
    fun `each problem type is its refusal, and only rate-limited retries by itself (A1_7, A11_5, A11_6)`() {
        fun problem(status: Int, type: String?, detail: String? = null, retryAfter: Long? = null) =
            BellWakeUps.problemStatus(BellException(status, type, detail, retryAfter), now = 1000)
        assertEquals(WakeUpStatus.Refused("Square didn't confirm this location. Sign in to Square again."), problem(401, "square-refused"))
        assertEquals(WakeUpStatus.Refused("this location already has 32 tills registered"), problem(409, "too-many-registrations"))
        assertEquals(WakeUpStatus.Refused("this device is registered under another Square account"), problem(409, "endpoint-registered"))
        assertEquals(
            WakeUpStatus.Refused("too many registrations from this network: trying again in 42 seconds", retryAt = 1042),
            problem(429, "rate-limited", retryAfter = 42),
        )
        assertEquals(WakeUpStatus.Refused("subscription.keys.p256dh isn't a P-256 point"), problem(400, "invalid-request", "subscription.keys.p256dh isn't a P-256 point"))
        assertEquals(WakeUpStatus.Refused("it answered 413"), problem(413, "payload-too-large"))
        assertEquals(WakeUpStatus.Unreachable("it answered 503"), problem(503, "square-unavailable", "Square didn't answer"))
        assertEquals(WakeUpStatus.Unreachable("it answered 500"), problem(500, "internal"))
        assertEquals(WakeUpStatus.Unreachable("it answered 502"), problem(502, null))
    }

    @Test
    fun `a refusal waits for a re-pick, a sign-in or a start, but rate-limited comes back after Retry-After`() = runTest {
        bell.register = { throw BellException(409, "endpoint-registered", null, null) }
        val wakeUps = start()
        wakeUps.choose(WakeUpChoice.MonokularBell)
        runCurrent()
        assertEquals(WakeUpStatus.Refused("this device is registered under another Square account"), wakeUps.status.value)
        wait(60.minutes)
        assertEquals(2, bell.calls.size)
        bell.register = { throw BellException(429, "rate-limited", null, 30) }
        wakeUps.recheck()
        runCurrent()
        val refused = wakeUps.status.value as WakeUpStatus.Refused
        assertEquals(testScheduler.currentTime / 1000 + 30, refused.retryAt)
        bell.register = { BellRegistered("br_9", APP) }
        wait(29.seconds)
        assertEquals(4, bell.calls.size)
        wait(1.seconds)
        assertEquals(WakeUpStatus.Connected(null), wakeUps.status.value)
        assertEquals("br_9", store.registration?.registrationId)
    }

    @Test
    fun `switching Bells takes the old registration back before registering with the new (A3_17)`() = runTest {
        val wakeUps = connected()
        wakeUps.choose(WakeUpChoice.OwnBell(own))
        runCurrent()
        assertEquals(
            listOf("DELETE bell.monokulo.com br_1", "GET bell.thebeanroom.coffee", "POST bell.thebeanroom.coffee EAAAtoken L1 https://ntfy.sh/up1"),
            bell.calls,
        )
        assertEquals(own, store.registration?.bell)
        assertEquals(WakeUpStatus.Connected(null), wakeUps.status.value)
    }

    @Test
    fun `timer only takes the registration back once, ends the subscription and keeps My own Bell's address (A10_1)`() = runTest {
        val wakeUps = connected(WakeUpChoice.OwnBell(own))
        wakeUps.choose(WakeUpChoice.TimerOnly)
        assertEquals(WakeUpStatus.Off, wakeUps.status.value)
        runCurrent()
        assertEquals(listOf("DELETE bell.thebeanroom.coffee br_1"), bell.calls)
        assertEquals(listOf("unsubscribe"), push.calls)
        assertNull(store.registration)
        assertEquals(WakeUpChoice.TimerOnly, store.choice)
        assertEquals(own, store.ownBell)
        wakeUps.recheck()
        runCurrent()
        assertEquals(1, bell.calls.size)
    }

    @Test
    fun `signing out takes the registration back and keeps the choice, awaited (A10_2)`() = runTest {
        val wakeUps = connected()
        wakeUps.unregister()
        assertEquals(listOf("DELETE bell.monokulo.com br_1"), bell.calls)
        assertEquals(listOf("unsubscribe"), push.calls)
        assertNull(store.registration)
        assertEquals(WakeUpChoice.MonokularBell, store.choice)
        assertEquals(WakeUpStatus.NoSquare, wakeUps.status.value)
        // Signed in again (same application): registers with no re-pick.
        wakeUps.recheck()
        runCurrent()
        assertEquals(WakeUpStatus.Connected(null), wakeUps.status.value)
    }

    @Test
    fun `disconnecting takes the registration back and forgets the choice (A10_3)`() = runTest {
        val wakeUps = connected()
        wakeUps.unregister(forget = true)
        assertEquals(listOf("DELETE bell.monokulo.com br_1"), bell.calls)
        assertEquals(WakeUpChoice.TimerOnly, store.choice)
        assertEquals(WakeUpChoice.TimerOnly, wakeUps.choice.value)
        assertEquals(WakeUpStatus.Off, wakeUps.status.value)
        wait(30.minutes)
        assertEquals(1, bell.calls.size, "and nothing after")
    }

    @Test
    fun `a Bell that can't take the registration back is tried once and dropped here (A10_7)`() = runTest {
        val wakeUps = connected()
        bell.unregister = { throw ConnectException() }
        wakeUps.unregister()
        assertEquals(listOf("DELETE bell.monokulo.com br_1"), bell.calls)
        assertNull(store.registration)
    }

    @Test
    fun `a Bell that never answers the DELETE holds the sign-out 10 s at most (A10_7)`() = runTest {
        val wakeUps = connected()
        bell.unregister = { awaitCancellation() }
        val started = testScheduler.currentTime
        wakeUps.unregister()
        assertEquals(10_000L, testScheduler.currentTime - started)
        assertNull(store.registration)
        assertEquals(WakeUpStatus.NoSquare, wakeUps.status.value)
    }

    @Test
    fun `unregistering stops a registration under way, and takes back what it got`() = runTest {
        val wakeUps = start()
        val held = CompletableDeferred<Unit>()
        bell.register = { BellRegistered("br_1", APP).also { held.complete(Unit) } }
        push.answer = { null }
        wakeUps.choose(WakeUpChoice.MonokularBell)
        runCurrent()
        assertEquals(WakeUpStatus.Connecting, wakeUps.status.value, "waiting on the distributor")
        wakeUps.unregister(forget = true)
        assertFalse(bell.calls.any { it.startsWith("POST") })
        assertEquals(WakeUpStatus.Off, wakeUps.status.value)
        assertFalse(held.isCompleted)
    }

    @Test
    fun `every app start registers again with the same subscription (A4_21, A3_19)`() = runTest {
        connected()
        val second = start()
        assertEquals(WakeUpChoice.MonokularBell, second.choice.value)
        assertEquals(listOf("GET bell.monokulo.com", "POST bell.monokulo.com EAAAtoken L1 https://ntfy.sh/up1"), bell.calls)
        assertEquals(listOf("subscribe $KEY"), push.calls, "the connector asked again, as its docs say")
        assertEquals(WakeUpStatus.Connected(null), second.status.value)
    }

    @Test
    fun `a shift starting registers again without leaving Connected, so the pace holds (A4_21)`() = runTest {
        val wakeUps = connected()
        val seen = statuses(wakeUps.status)
        wakeUps.recheck()
        runCurrent()
        assertEquals(listOf("GET bell.monokulo.com", "POST bell.monokulo.com EAAAtoken L1 https://ntfy.sh/up1"), bell.calls)
        assertEquals(emptyList(), push.calls, "the subscription this run made is still good")
        assertTrue(seen.all { it is WakeUpStatus.Connected }, "$seen")
    }

    @Test
    fun `an app start with timer only finishes taking back a registration left behind`() = runTest {
        store.registration = BellRegistration(monokular, "br_old", "L1", APP, KEY, PushSubscription("https://ntfy.sh/up0", "BKey", "auth"), 0)
        val wakeUps = start()
        assertEquals(listOf("DELETE bell.monokulo.com br_old"), bell.calls)
        assertNull(store.registration)
        assertEquals(WakeUpStatus.Off, wakeUps.status.value)
    }

    @Test
    fun `a new endpoint from the distributor deletes the old registration, then registers the new (A7_32)`() = runTest {
        val wakeUps = connected()
        push.state.value = PushState.Subscribed(PushSubscription("https://ntfy.sh/up2", "BKey2", "auth2"))
        runCurrent()
        assertEquals(
            listOf("GET bell.monokulo.com", "DELETE bell.monokulo.com br_1", "POST bell.monokulo.com EAAAtoken L1 https://ntfy.sh/up2"),
            bell.calls,
        )
        assertEquals("https://ntfy.sh/up2", store.registration?.subscription?.endpoint)
        assertEquals(WakeUpStatus.Connected(null), wakeUps.status.value)
    }

    @Test
    fun `an endpoint changed while the app was away is caught at the next start`() = runTest {
        connected()
        push.endpoint = "https://ntfy.sh/up2"
        start()
        assertEquals(
            listOf("GET bell.monokulo.com", "DELETE bell.monokulo.com br_1", "POST bell.monokulo.com EAAAtoken L1 https://ntfy.sh/up2"),
            bell.calls,
        )
    }

    @Test
    fun `the distributor ending the subscription takes the registration back and is NoDistributor (A7_32)`() = runTest {
        val wakeUps = connected()
        push.state.value = PushState.Failed(PushFailure.Lost)
        runCurrent()
        assertEquals(listOf("DELETE bell.monokulo.com br_1"), bell.calls)
        assertNull(store.registration)
        assertEquals(WakeUpStatus.NoDistributor, wakeUps.status.value)
    }

    @Test
    fun `the distributor uninstalled takes the registration back, and one installed again connects (A7_32, A7_30)`() = runTest {
        val wakeUps = connected()
        distributors.resolved = Distributor.None
        distributors.changes.emit(Unit)
        runCurrent()
        assertEquals(WakeUpStatus.NoDistributor, wakeUps.status.value)
        assertEquals(listOf("GET bell.monokulo.com", "DELETE bell.monokulo.com br_1"), bell.calls)
        assertEquals(listOf("unsubscribe"), push.calls)
        distributors.resolved = Distributor.Found("io.heckel.ntfy")
        distributors.changes.emit(Unit)
        runCurrent()
        assertEquals(WakeUpStatus.Connected(null), wakeUps.status.value)
    }

    @Test
    fun `an app installed that isn't the distributor changes nothing`() = runTest {
        connected()
        distributors.changes.emit(Unit)
        runCurrent()
        assertEquals(emptyList(), bell.calls)
    }

    @Test
    fun `a rotated VAPID key subscribes again for it, then registers the new subscription (A9_8)`() = runTest {
        val wakeUps = connected()
        bell.infos[monokular] = info(key = "BNewKey")
        push.endpoint = "https://ntfy.sh/up2"
        wakeUps.recheck()
        runCurrent()
        assertEquals(listOf("unsubscribe", "subscribe BNewKey"), push.calls)
        assertEquals(
            listOf("GET bell.monokulo.com", "DELETE bell.monokulo.com br_1", "POST bell.monokulo.com EAAAtoken L1 https://ntfy.sh/up2"),
            bell.calls,
        )
        assertEquals("BNewKey", store.registration?.vapidPublicKey)
    }

    @Test
    fun `a rotated key is caught at an app start too`() = runTest {
        connected()
        bell.infos[monokular] = info(key = "BNewKey")
        start()
        assertEquals(listOf("unsubscribe", "subscribe BNewKey"), push.calls)
    }

    @Test
    fun `a timer-found sale with no ding in 60 s is a missed wake-up, 2 s and one registration again (A11_3)`() = runTest {
        val wakeUps = connected()
        wait(61.seconds)
        wakeUps.timerFound(1)
        assertEquals(WakeUpStatus.MissingWakeups(null), wakeUps.status.value)
        assertEquals(2.seconds, wakeUps.status.value.interval)
        runCurrent()
        assertEquals(listOf("GET bell.monokulo.com", "POST bell.monokulo.com EAAAtoken L1 https://ntfy.sh/up1"), bell.calls)
        assertEquals(WakeUpStatus.MissingWakeups(null), wakeUps.status.value, "registering again proves nothing")
        wakeUps.timerFound(2)
        runCurrent()
        assertEquals(2, bell.calls.size, "once")
        wait(5.seconds)
        push.dings.emit(Unit)
        runCurrent()
        val at = testScheduler.currentTime / 1000
        assertEquals(WakeUpStatus.Connected(at), wakeUps.status.value)
        assertEquals(30.seconds, wakeUps.status.value.interval)
    }

    @Test
    fun `a ding then a timer-found sale stays Connected (A11_3)`() = runTest {
        val wakeUps = connected()
        wait(5.minutes)
        push.dings.emit(Unit)
        runCurrent()
        val at = testScheduler.currentTime / 1000
        assertEquals(WakeUpStatus.Connected(at), wakeUps.status.value, "Last wake-up")
        wait(30.seconds)
        wakeUps.timerFound(1)
        runCurrent()
        assertEquals(WakeUpStatus.Connected(at), wakeUps.status.value)
        assertEquals(emptyList(), bell.calls)
        wait(31.seconds)
        wakeUps.timerFound(1)
        assertEquals(WakeUpStatus.MissingWakeups(at), wakeUps.status.value, "61 s after the ding")
    }

    @Test
    fun `a timer check that finds nothing, or one just after connecting, proves nothing`() = runTest {
        val wakeUps = connected()
        wait(30.seconds)
        wakeUps.timerFound(1)
        assertEquals(WakeUpStatus.Connected(null), wakeUps.status.value, "a sale from before the Bell was connected")
        wait(10.minutes)
        wakeUps.timerFound(0)
        assertEquals(WakeUpStatus.Connected(null), wakeUps.status.value)
    }

    @Test
    fun `a timer-found sale while not connected changes nothing`() = runTest {
        bell.infos.remove(monokular)
        val wakeUps = start()
        wakeUps.choose(WakeUpChoice.MonokularBell)
        runCurrent()
        wait(10.seconds)
        wakeUps.timerFound(3)
        assertEquals(WakeUpStatus.Unreachable("no connection"), wakeUps.status.value)
    }

    @Test
    fun `a ding while not connected changes no status`() = runTest {
        square.signedInApplicationId = null
        val wakeUps = start()
        wakeUps.choose(WakeUpChoice.MonokularBell)
        runCurrent()
        push.dings.emit(Unit)
        runCurrent()
        assertEquals(WakeUpStatus.NoSquare, wakeUps.status.value)
    }

    private companion object {
        const val APP = "sandbox-sq0idb-dev"
        const val KEY = "BOS-key"
    }
}
