package app.monokular.sales

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What makes the till check the POS (`docs/design.md` §3): a timer during
 * a shift, and anything that calls [wake] (the app opening, a ding from a
 * Monokular Bell). Every wake-up does the same idempotent check.
 */
class Shift(
    private val till: Till,
    /** How often to check without a wake-up: 2 s alone, 30 s with a Bell. */
    private val interval: () -> Duration = { 2.seconds },
) {
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /** Check now, rather than at the next tick. */
    fun wake() {
        wakeups.trySend(Unit)
    }

    /** Checks until cancelled. */
    suspend fun run() {
        while (true) {
            till.check()
            withTimeoutOrNull(interval()) { wakeups.receive() }
        }
    }
}
