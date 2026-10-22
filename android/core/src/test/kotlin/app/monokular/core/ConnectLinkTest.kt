package app.monokular.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConnectLinkTest {
    private val token = "ct_9fK2aLx81QzR7mWp4Tn0"

    @Test
    fun `a dashboard link gives the store's host and token`() {
        val link = ConnectLink.parse("https://monokular.app/connect#host=pay.yourshop.com&token=$token")
        assertEquals(ConnectLink("pay.yourshop.com", token), link)
        assertEquals("https://pay.yourshop.com", link?.storeUrl)
    }

    @Test
    fun `a host with a port is kept`() {
        val link = ConnectLink.parse("https://monokular.app/connect#token=$token&host=shop.example%3A8443")
        assertEquals("shop.example:8443", link?.host)
    }

    @Test
    fun `links from anywhere but monokular_app are refused`() {
        assertNull(ConnectLink.parse("https://evil.example/connect#host=pay.yourshop.com&token=$token"))
        assertNull(ConnectLink.parse("http://monokular.app/connect#host=pay.yourshop.com&token=$token"))
        assertNull(ConnectLink.parse("https://monokular.app/other#host=pay.yourshop.com&token=$token"))
    }

    @Test
    fun `a host that smuggles a path or credentials is refused`() {
        assertNull(ConnectLink.parse("https://monokular.app/connect#host=evil.example%2Fsteal&token=$token"))
        assertNull(ConnectLink.parse("https://monokular.app/connect#host=user%40evil.example&token=$token"))
        assertNull(ConnectLink.parse("https://monokular.app/connect#host=&token=$token"))
    }

    @Test
    fun `a missing or malformed token is refused`() {
        assertNull(ConnectLink.parse("https://monokular.app/connect#host=pay.yourshop.com"))
        assertNull(ConnectLink.parse("https://monokular.app/connect#host=pay.yourshop.com&token=short"))
        assertNull(ConnectLink.parse("not a link"))
    }
}
