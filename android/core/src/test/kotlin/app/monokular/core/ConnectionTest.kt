package app.monokular.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The connection as the app keeps it on the device (`Stores`). */
class ConnectionTest {
    @Test
    fun `a connection is kept as its key and the API's entry, nothing about the store`() {
        val connection = Connection("ak_x", "https://pay.example/api/v1")
        val saved = Connection.save(connection)
        assertEquals("""{"app_key":"ak_x","api":"https://pay.example/api/v1"}""", saved)
        assertEquals(connection, Connection.restore(saved))
    }

    @Test
    fun `a connection the old app saved carries on at its endpoint's API`() {
        val saved = """{"public_key":"pk_1","app_key":"ak_x","endpoint":"https://pay.example","onion_endpoint":"http://abc.onion",
            "app_id":"app_1","device_name":"Front counter","store":{"name":"Bakery","base_currency":"USD"},
            "wallet":{"name":"Shop","network":"mainnet","address_short":"4Ad…7s","address":"4Adxyz"}}"""
        assertEquals(Connection("ak_x", "https://pay.example/api/v1"), Connection.restore(saved))
    }

    @Test
    fun `an old endpoint keeps its port, and loses any path`() {
        assertEquals(
            "http://10.0.2.2:8080/api/v1",
            Connection.restore("""{"app_key":"ak_x","endpoint":"http://10.0.2.2:8080/"}""")?.api,
        )
        assertEquals(
            "https://pay.example/api/v1",
            Connection.restore("""{"app_key":"ak_x","endpoint":"https://pay.example/pay"}""")?.api,
        )
    }

    @Test
    fun `nothing usable restores nothing`() {
        assertNull(Connection.restore("not json"))
        assertNull(Connection.restore("""{"endpoint":"https://pay.example"}"""))
        assertNull(Connection.restore("""{"app_key":"ak_x"}"""))
    }

    @Test
    fun `the host and whether it's encrypted come from the API's address`() {
        val tls = Connection("ak_x", "https://pay.example/api/v1")
        assertEquals("pay.example", tls.host)
        assertTrue(tls.encrypted)
        val local = Connection("ak_x", "http://10.0.2.2:8080/api/v1")
        assertEquals("10.0.2.2:8080", local.host)
        assertFalse(local.encrypted)
    }

    @Test
    fun `a wallet address is shortened on the device to its first and last 8`() {
        assertEquals("4AdUndXH…NDXhdS7s", Store.shorten("4AdUndXHHZ6cfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv2bNDXhdS7s"))
        assertEquals("short", Store.shorten("short"))
    }
}
