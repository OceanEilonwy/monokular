package app.monokular.sales

/** Which Square, and as which application (`docs/design.md` §1). */
data class SquareConfig(
    /** The Square application's id (public: no secret is ever on the device). */
    val applicationId: String,
    val sandbox: Boolean,
    /** Where Square sends the sign-in back: an App Link that opens the app. */
    val redirectUri: String,
    /** The name of the Other tender the cashier taps. */
    val tenderName: String = "Monero",
    /** Square's address, for tests; otherwise production's or the sandbox's. */
    private val baseUrl: String? = null,
) {
    val connectBase: String
        get() = baseUrl ?: if (sandbox) "https://connect.squareupsandbox.com" else "https://connect.squareup.com"

    companion object {
        /** The API version every request asks for. */
        const val API_VERSION = "2026-09-16"

        val SCOPES = listOf("PAYMENTS_READ", "ORDERS_READ", "MERCHANT_PROFILE_READ", "PAYMENTS_WRITE")
    }
}
