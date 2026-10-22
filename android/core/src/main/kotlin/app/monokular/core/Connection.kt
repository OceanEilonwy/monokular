package app.monokular.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * This device's connection to one store, as `POST /apps/connect` answered
 * it. The app keeps it (the key encrypted) until it is disconnected.
 */
@Serializable
data class Connection(
    @SerialName("public_key") val publicKey: String,
    /** The app's own key: `Authorization: Bearer ak_…`. */
    @SerialName("app_key") val appKey: String,
    /** Where orders are made and the event stream opened. */
    val endpoint: String,
    @SerialName("onion_endpoint") val onionEndpoint: String? = null,
    @SerialName("app_id") val appId: String,
    @SerialName("device_name") val deviceName: String,
    val store: Store,
    val wallet: Wallet? = null,
) {
    @Serializable
    data class Store(
        val name: String,
        @SerialName("base_currency") val baseCurrency: String,
    )

    @Serializable
    data class Wallet(
        val name: String,
        val network: String,
        @SerialName("address_short") val addressShort: String,
    )
}
