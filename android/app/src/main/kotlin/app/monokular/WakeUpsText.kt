package app.monokular

import app.monokular.push.BellAddress
import app.monokular.push.WakeUpStatus
import app.monokular.push.interval
import java.time.ZoneId
import java.util.Locale

/**
 * Settings › Wake-ups in the cashier's words (acceptance A1.6–A1.8): every
 * status's word and footnote from one exhaustive `when` each, so a status
 * added later doesn't compile until it has words (D15). Pure: tested
 * string by string in `WakeUpsTextTest`.
 */
object WakeUpsText {
    /** The Timer only row's detail. */
    const val TIMER_DETAIL = "Checks Square every 2 seconds"

    /** The My own Bell row's detail when no address is saved. */
    const val NO_OWN_BELL = "Enter its address"

    /** Under the choices while My own Bell is picked and no address is saved yet: nothing has changed until one is used. */
    const val NO_OWN_BELL_YET = "Enter the address of the Bell you run, then press Use this Bell. Until then Wake-ups stay as they were."

    /** Under every Bell's status: what the Bell learns, and what it doesn't. */
    const val PRIVACY = "The Bell learns which Square location this till is at and when it takes payments, not what was sold. " +
        "Your Square sign-in is shown to it once, to prove the location, and never kept."

    /** Under the choices while Timer only is chosen. */
    const val TIMER_ONLY = "Monokular checks Square every 2 seconds during a shift. A Bell lets Square wake the till within a second " +
        "or two of a sale, so it can check every 30 seconds instead."

    /** The My own Bell row's detail: the saved address without its scheme, or how to start. */
    fun ownDetail(saved: BellAddress?): String = saved?.display ?: NO_OWN_BELL

    /**
     * What the address field starts with: the saved address as the row
     * shows it, with its scheme only when that isn't https (a debug
     * build's http Bell), so the field reads back as the same address.
     */
    fun ownField(saved: BellAddress?): String = when {
        saved == null -> ""
        saved.url == "https://${saved.display}" -> saved.display
        else -> saved.url
    }

    /** The status card's Checking Square row: the pace the shift's timer runs at. */
    fun pace(status: WakeUpStatus): String = "Every ${status.interval.inWholeSeconds} s"

    /** The status card's Bell row. */
    fun word(status: WakeUpStatus): String = when (status) {
        WakeUpStatus.Off -> "Off"
        WakeUpStatus.NoSquare -> "Sign in needed"
        WakeUpStatus.NoDistributor -> "Needs a UnifiedPush app"
        is WakeUpStatus.ChooseDistributor -> "Choose a UnifiedPush app"
        WakeUpStatus.Connecting -> "Connecting…"
        is WakeUpStatus.Connected -> "Connected"
        is WakeUpStatus.MissingWakeups -> "Wake-ups not arriving"
        is WakeUpStatus.Unreachable -> "Not connected"
        is WakeUpStatus.Refused -> "Not connected"
        is WakeUpStatus.SignInNeeded -> "Sign in needed"
    }

    /**
     * The status card's Last wake-up row, written as the shift writes
     * times; null while the status has no ding clock (the row is left out).
     */
    fun lastWakeUp(status: WakeUpStatus, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val at = when (status) {
            is WakeUpStatus.Connected -> status.lastDingAt
            is WakeUpStatus.MissingWakeups -> status.lastDingAt
            else -> return null
        }
        return at?.let { SaleText.time(it, locale, zone) } ?: "None yet"
    }

    /** The footnote under the cards. */
    fun footnote(status: WakeUpStatus): String = when (status) {
        WakeUpStatus.Off -> TIMER_ONLY
        WakeUpStatus.NoSquare -> "Sign in to Square to use the Bell. Until then Monokular shows other tills' sales only."
        WakeUpStatus.NoDistributor -> "Wake-ups need a UnifiedPush app such as ntfy to reach this till. Install one, then come back here. " +
            "Until then Monokular checks every 2 seconds."
        is WakeUpStatus.ChooseDistributor -> "Deliver wake-ups through one of the UnifiedPush apps above. " +
            "Until you choose, Monokular checks every 2 seconds."
        WakeUpStatus.Connecting -> "Connecting to the Bell…"
        is WakeUpStatus.Connected -> "Square wakes this till through the Bell within a second or two of a sale. " +
            "Monokular still checks every 30 seconds in case a wake-up is lost."
        is WakeUpStatus.MissingWakeups -> "A sale came in without a wake-up from the Bell. " +
            "Monokular is checking Square every 2 seconds until the Bell's wake-ups come back."
        is WakeUpStatus.Unreachable -> "Monokular couldn't reach the Bell (${status.reason}). " +
            "It's checking Square every 2 seconds and will try the Bell again."
        is WakeUpStatus.Refused -> "The Bell refused this till: ${sentence(status.reason)} Monokular is checking Square every 2 seconds."
        is WakeUpStatus.SignInNeeded -> "This Bell uses a different Square application. Sign in to Square through it to use the Bell."
    }

    /** The Shift section's footnote, by the pace. */
    fun shift(status: WakeUpStatus): String = when (status) {
        is WakeUpStatus.Connected -> "While it's on, the Bell wakes Monokular when a sale comes in and it checks Square every 30 seconds " +
            "between wake-ups. Turn it off at the end of the day."
        WakeUpStatus.Off, WakeUpStatus.NoSquare, WakeUpStatus.NoDistributor, is WakeUpStatus.ChooseDistributor,
        WakeUpStatus.Connecting, is WakeUpStatus.MissingWakeups, is WakeUpStatus.Unreachable, is WakeUpStatus.Refused,
        is WakeUpStatus.SignInNeeded,
        -> "While it's on, Monokular checks Square every 2 seconds and keeps a notification. Turn it off at the end of the day."
    }

    /** [reason] ending in one full stop: some refusals are sentences of their own ("… Sign in to Square again."). */
    private fun sentence(reason: String): String = if (reason.endsWith('.')) reason else "$reason."
}
