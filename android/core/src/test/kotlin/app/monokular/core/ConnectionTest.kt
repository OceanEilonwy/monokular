package app.monokular.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The connection as the app keeps it on the device (`Stores`, the same JSON settings). */
class ConnectionTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `a connection saved before the whole address was sent still loads, without it`() {
        val saved = """{"public_key":"pk_1","app_key":"ak_x","endpoint":"https://pay.example","app_id":"app_1",
            "device_name":"Front counter","store":{"name":"Bakery","base_currency":"USD"},
            "wallet":{"name":"Shop","network":"mainnet","address_short":"4Ad…7s"}}"""
        val wallet = json.decodeFromString(Connection.serializer(), saved).wallet!!
        assertEquals("4Ad…7s", wallet.addressShort)
        assertNull(wallet.address)
    }

    @Test
    fun `the whole address is kept with the rest of the connection`() {
        val connection = Connection(
            publicKey = "pk_1", appKey = "ak_x", endpoint = "https://pay.example", appId = "app_1",
            deviceName = "Front counter", store = Connection.Store("Bakery", "USD"),
            wallet = Connection.Wallet("Shop", "mainnet", "4Ad…7s", address = "4Ad" + "x".repeat(90) + "7s"),
        )
        val saved = json.encodeToString(Connection.serializer(), connection)
        assertEquals(connection, json.decodeFromString(Connection.serializer(), saved))
    }
}
