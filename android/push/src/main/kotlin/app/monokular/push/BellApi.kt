package app.monokular.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What `POST /v1/register` answers (`docs/bell.md`). */
@Serializable
data class BellRegistered(
    @SerialName("registration_id") val registrationId: String,
    @SerialName("square_application_id") val squareApplicationId: String,
)

/** The Bell's three routes a till uses, and nothing else. [BellWakeUps] drives it; tests fake it. */
interface BellClient {
    /** `GET /v1/info`. */
    suspend fun info(bell: BellAddress): BellInfo

    /** `POST /v1/register`: the one request that carries the Square token (A9.1). */
    suspend fun register(bell: BellAddress, accessToken: String, locationId: String, subscription: PushSubscription): BellRegistered

    /** `DELETE /v1/register/{id}`: a `404` (taken back already) is as good as a `204`. */
    suspend fun unregister(bell: BellAddress, registrationId: String)
}

/** The Bell answered with an error: its problem's [type] (the slug of `docs/bell.md`'s section), [detail] and [retryAfter] seconds. */
class BellException(
    val status: Int,
    val type: String?,
    val detail: String?,
    val retryAfter: Long?,
) : IOException("the Bell answered $status${type?.let { " $it" }.orEmpty()}")

/** An answer that isn't a Bell's: a web page at a wrong address, say. */
class NotABellException : IOException("not a Bell")

/**
 * The Bell's HTTP API (`docs/bell.md`), over the app's client. The Bell is
 * reached directly, never through Orbot (decision 98), and every call ends
 * within [timeout]. No request carries an `Authorization` header; the
 * Square token goes in [register]'s body and nowhere else (A9.1, A9.2).
 */
class BellApi(http: OkHttpClient, timeout: Duration = 10.seconds) : BellClient {
    private val http = http.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .callTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun info(bell: BellAddress): BellInfo = call(Request.Builder().url(bell.route("/v1/info")).get()) { response, text ->
        if (!response.isSuccessful) throw failure(response, text, notABell = response.code < 500)
        val info = runCatching { json.decodeFromString(BellInfo.serializer(), text) }.getOrNull() ?: throw NotABellException()
        if (info.environment !in ENVIRONMENTS || info.vapidPublicKey.isEmpty()) throw NotABellException()
        info
    }

    override suspend fun register(
        bell: BellAddress,
        accessToken: String,
        locationId: String,
        subscription: PushSubscription,
    ): BellRegistered {
        val body = RegisterBody(
            squareAccessToken = accessToken,
            locationId = locationId,
            subscription = RegisterBody.Subscription(subscription.endpoint, RegisterBody.Keys(subscription.p256dh, subscription.auth)),
        )
        val request = Request.Builder().url(bell.route("/v1/register"))
            .post(json.encodeToString(RegisterBody.serializer(), body).toRequestBody(JSON))
        return call(request) { response, text ->
            if (!response.isSuccessful) throw failure(response, text, notABell = false)
            runCatching { json.decodeFromString(BellRegistered.serializer(), text) }.getOrNull() ?: throw NotABellException()
        }
    }

    override suspend fun unregister(bell: BellAddress, registrationId: String) =
        call(Request.Builder().url(bell.route("/v1/register/$registrationId")).delete()) { response, text ->
            if (!response.isSuccessful && response.code != 404) throw failure(response, text, notABell = false)
        }

    private suspend fun <T> call(request: Request.Builder, read: (Response, String) -> T): T = withContext(Dispatchers.IO) {
        http.newCall(request.header("Accept", "application/json").build()).execute().use { response ->
            read(response, response.body.string())
        }
    }

    /** An error answer: the Bell's problem when it gave one, else [NotABellException] when [notABell] or a bare status. */
    private fun failure(response: Response, text: String, notABell: Boolean): IOException {
        val problem = runCatching { json.decodeFromString(Problem.serializer(), text) }.getOrNull()
            ?.takeIf { response.header("Content-Type").orEmpty().startsWith("application/problem+json") }
        if (problem == null && notABell) return NotABellException()
        return BellException(
            status = response.code,
            // The type is the section's address on the Bell's page: `…/docs/bell.md#rate-limited`.
            type = problem?.type?.substringAfterLast('#')?.takeIf { it.isNotEmpty() },
            detail = problem?.detail?.takeIf { it.isNotBlank() },
            retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull() ?: problem?.retryAfter,
        )
    }

    @Serializable
    private data class RegisterBody(
        @SerialName("square_access_token") val squareAccessToken: String,
        @SerialName("location_id") val locationId: String,
        val subscription: Subscription,
    ) {
        @Serializable
        data class Subscription(val endpoint: String, val keys: Keys)

        @Serializable
        data class Keys(val p256dh: String, val auth: String)
    }

    @Serializable
    private data class Problem(
        val type: String? = null,
        val detail: String? = null,
        @SerialName("retry_after") val retryAfter: Long? = null,
    )

    private companion object {
        val JSON = "application/json".toMediaType()
        val ENVIRONMENTS = setOf(SANDBOX, PRODUCTION)
    }
}

/** `/v1/info`'s `environment` for Square's sandbox, and for its production accounts. */
const val SANDBOX = "sandbox"
const val PRODUCTION = "production"
