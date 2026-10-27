package app.monokular

import app.monokular.core.MonokuloException
import app.monokular.core.Problems
import app.monokular.core.StreamEvent
import app.monokular.sales.FoundSale
import app.monokular.sales.SaleSource
import app.monokular.sales.Shift
import app.monokular.sales.SquareSource
import app.monokular.sales.Till
import app.monokular.sales.TillState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** The till as the screens and the service see it. */
data class RunnerState(
    val running: Boolean = false,
    /** The store's event stream is open. */
    val live: Boolean = false,
    /** The store disconnected this device: its key no longer works. */
    val disconnected: Boolean = false,
    /** What the event stream's last failure said, while it's closed: `too-many-streams`, say. */
    val streamProblem: String? = null,
    val till: TillState = TillState(),
)

/** A source for a till that runs without Square: it shows other tills' sales only. */
private object NoSource : SaleSource {
    override suspend fun newSales() = emptyList<FoundSale>()
    override suspend fun receipt(sale: FoundSale) = null
    override suspend fun refund(sale: FoundSale) = Unit
    override fun handled(sale: FoundSale) = Unit
}

/**
 * Runs the till while a shift is on (`docs/design.md` §3): the POS checked
 * on a timer and whenever woken, and the store's event stream followed,
 * reconnecting with a growing wait. Held by the app; [TillService] keeps
 * the process alive while it runs.
 */
class TillRunner(private val graph: Graph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutable = MutableStateFlow(RunnerState())
    val state: StateFlow<RunnerState> = mutable.asStateFlow()
    private var job: Job? = null
    private var till: Till? = null
    private var shift: Shift? = null

    fun start() {
        if (job?.isActive == true) return
        val connection = graph.stores.connection ?: return
        graph.store.read(connection)
        val location = graph.stores.location
        val source: SaleSource = if (location != null && graph.squareAuth().signedIn) {
            SquareSource(graph.squareApi(), graph.squareConfig(), location, graph.stores.cursor)
        } else {
            NoSource
        }
        val till = Till(source, graph.monokulo, connection, store = graph.stores.shift).also { till = it }
        val shift = Shift(till).also { shift = it }
        mutable.value = RunnerState(running = true)
        job = scope.launch {
            launch { till.sales.collect { s -> mutable.update { it.copy(till = s) } } }
            launch { shift.run() }
            launch { follow(till) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        mutable.update { it.copy(running = false, live = false) }
    }

    /** The store disconnected this device (it answered 401): stop asking. */
    fun disconnected() {
        mutable.update { it.copy(live = false, disconnected = true) }
        job?.cancel()
    }

    /** Check the POS now (the app came to the front). */
    fun wake() = shift?.wake()

    /** Refunds the sale's POS sale; [close] it first (the close sheet's "No"). The job ends when the POS has answered. */
    fun refund(orderId: String, close: Boolean = false): Job? {
        val till = till ?: return null
        return scope.launch {
            if (close) till.close(orderId)
            till.refund(orderId)
        }
    }

    /** Closes the sale for now (the close sheet's "Not sure"): its code comes down, it stays in the shift. */
    fun close(orderId: String) {
        val till = till ?: return
        scope.launch { till.close(orderId) }
    }

    /** "Show the code again" on a closed sale. */
    fun reopen(orderId: String) {
        val till = till ?: return
        scope.launch { till.reopen(orderId) }
    }

    private suspend fun follow(till: Till) {
        val connection = graph.stores.connection ?: return
        var wait = 1.seconds
        while (true) {
            try {
                graph.monokulo.events(connection).collect { event ->
                    when (event) {
                        StreamEvent.Ready, StreamEvent.Lagged -> {
                            wait = 1.seconds
                            mutable.update { it.copy(live = true, streamProblem = null) }
                            resync(till)
                        }
                        is StreamEvent.Sale -> till.onEvent(event.event)
                    }
                }
            } catch (e: MonokuloException) {
                if (e.status == 401) {
                    disconnected()
                    return
                }
                if (e.problem != null) mutable.update { it.copy(streamProblem = Problems.say(e)) }
            } catch (_: Exception) {
            }
            mutable.update { it.copy(live = false) }
            delay(wait)
            wait = (wait * 2).coerceAtMost(30.seconds)
        }
    }

    /** Re-reads the sales on screen, after the stream (re)opens. */
    private suspend fun resync(till: Till) {
        val connection = graph.stores.connection ?: return
        // An expired sale too, until Monokulo stops watching it for a late payment.
        for (sale in till.sales.value.sales.filter { it.open }) {
            val url = sale.url ?: continue
            runCatching { graph.monokulo.order(connection, url) }
                .onSuccess { till.onStatus(sale.orderId, it.phase, it.isFinal) }
        }
    }
}
