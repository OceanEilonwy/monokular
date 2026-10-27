package app.monokular.push

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Settings › Wake-ups (`docs/design.md` §3): what makes the till check
 * Square, as the cashier chose it.
 */
@Serializable
sealed interface WakeUpChoice {
    /** The default: the till checks Square every 2 seconds during a shift. */
    @Serializable
    @SerialName("timer")
    data object TimerOnly : WakeUpChoice

    /** The project's Bell, `BuildConfig.BELL_URL`. */
    @Serializable
    @SerialName("monokular")
    data object MonokularBell : WakeUpChoice

    /** A Bell the merchant runs. */
    @Serializable
    @SerialName("own")
    data class OwnBell(val bell: BellAddress) : WakeUpChoice

    /** The Bell this choice names, given the project's; null for the timer alone. */
    fun bell(monokular: BellAddress): BellAddress? = when (this) {
        TimerOnly -> null
        MonokularBell -> monokular
        is OwnBell -> bell
    }
}

/** What a Bell says about itself: `GET /v1/info` (`docs/bell.md`). */
@Serializable
data class BellInfo(
    /** The Square application the till must sign in through for this Bell to hear its sales. */
    @SerialName("square_application_id") val squareApplicationId: String,
    /** `production` or `sandbox`. */
    val environment: String,
    /** The key every ding is signed with: what the subscription is made for. */
    @SerialName("vapid_public_key") val vapidPublicKey: String,
    val version: String,
)

/** A registration the Bell holds for this till: `POST /v1/register`'s answer and what it was made with. */
@Serializable
data class BellRegistration(
    val bell: BellAddress,
    /** `br_…`: known only to this till, and all `DELETE /v1/register/{id}` needs. */
    @SerialName("registration_id") val registrationId: String,
    /** The Square location the Bell rings this till for. */
    @SerialName("location_id") val locationId: String,
    /** The Square application the Bell answered with. */
    @SerialName("square_application_id") val squareApplicationId: String,
    /** The subscription registered, to tell when the distributor has changed it. */
    val subscription: PushSubscription,
    /** Unix seconds. */
    @SerialName("registered_at") val registeredAt: Long,
)

/** Where Wake-ups stands, for the service's pacing and the Settings screen. */
sealed interface WakeUpStatus {
    /** Timer only: nothing to register. */
    data object Off : WakeUpStatus

    /** A Bell is chosen, but no UnifiedPush distributor is installed to carry its dings. */
    data object NoDistributor : WakeUpStatus

    /** Reading the Bell, subscribing with the distributor, or registering. */
    data class Registering(val bell: BellAddress) : WakeUpStatus

    /** Registered: the Bell's dings reach the till, and the timer can slow down. */
    data class On(val bell: BellAddress, val registration: BellRegistration) : WakeUpStatus

    /**
     * The Bell needs a Square sign-in the till doesn't have: none at all,
     * or one through another application ([applicationId] is the Bell's,
     * which the till must sign in through: `docs/design.md` §3).
     */
    data class NeedsSquareSignIn(val bell: BellAddress, val applicationId: String) : WakeUpStatus

    /**
     * Registration failed, or was lost: [problem] says why in the cashier's
     * words. [retryAt] (Unix seconds) is when it tries again by itself;
     * null when only "Try again" will.
     */
    data class Failing(val bell: BellAddress, val problem: String, val retryAt: Long? = null) : WakeUpStatus
}

/** The Bell this status is about, if any. */
val WakeUpStatus.bell: BellAddress?
    get() = when (this) {
        WakeUpStatus.Off, WakeUpStatus.NoDistributor -> null
        is WakeUpStatus.Registering -> bell
        is WakeUpStatus.On -> bell
        is WakeUpStatus.NeedsSquareSignIn -> bell
        is WakeUpStatus.Failing -> bell
    }

/** What Wake-ups keeps between runs, encrypted with the rest of the till's state. */
interface WakeUpStore {
    var choice: WakeUpChoice

    /** The registration the Bell holds, while it does. */
    var registration: BellRegistration?
}

/**
 * Wake-ups, the Bell side (`docs/design.md` §3): keeps the till registered
 * with the chosen Bell while there is one, and takes the registration back
 * when there isn't. The service reads [status] to pace its timer; Settings
 * shows it.
 */
interface WakeUps {
    val choice: StateFlow<WakeUpChoice>

    val status: StateFlow<WakeUpStatus>

    /** The cashier chose (Settings): registers with the Bell, or takes the registration back. */
    fun choose(choice: WakeUpChoice)

    /**
     * Register again from the start: "Try again", a new Square sign-in, a
     * changed location, a shift starting, or the timer finding a sale the
     * Bell didn't ring for. Nothing happens for [WakeUpChoice.TimerOnly]
     * or while a registration is already under way.
     */
    fun recheck()

    /**
     * Take the registration back and end the subscription: the cashier
     * signed out of Square, or the device was disconnected. The choice
     * stays unless [forget]; the next sign-in registers again.
     */
    suspend fun unregister(forget: Boolean = false)
}
