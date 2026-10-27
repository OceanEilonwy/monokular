package app.monokular.push

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What Settings › Wake-ups takes as "My own Bell". */
class BellAddressTest {
    @Test
    fun `a bare host is https, and a trailing slash goes`() {
        assertEquals("https://bell.example.org", BellAddress.parse(" bell.example.org/ ")?.url)
        assertEquals("https://bell.example.org", BellAddress.parse("https://bell.example.org")?.url)
    }

    @Test
    fun `a path, a port and an onion address are kept`() {
        assertEquals("https://example.org/bell", BellAddress.parse("https://example.org/bell/")?.url)
        assertEquals("https://bell.example.org:8445", BellAddress.parse("bell.example.org:8445")?.url)
        val onion = assertNotNull(BellAddress.parse("http://pg6mmjiyjmcrsslvykfwnntlaru7p5svn6y2ymmju6nubxndf4pscryd.onion"))
        assertEquals(true, onion.onion)
    }

    @Test
    fun `the host is shown with a port only when it isn't the scheme's`() {
        assertEquals("bell.example.org", BellAddress.parse("https://bell.example.org:443/")?.host)
        assertEquals("bell.example.org:8445", BellAddress.parse("https://bell.example.org:8445")?.host)
    }

    @Test
    fun `plain http reaches onion addresses only, unless allowed`() {
        assertNull(BellAddress.parse("http://bell.example.org"))
        assertEquals("A Bell's address must start with https:// (or be an onion address).", BellAddress.problem("http://bell.example.org"))
        assertEquals("http://10.0.2.2:8445", BellAddress.parse("http://10.0.2.2:8445", allowHttp = true)?.url)
    }

    @Test
    fun `nothing, a user name, a query or a fragment is refused`() {
        assertNull(BellAddress.parse(""))
        assertNull(BellAddress.parse("   "))
        assertNull(BellAddress.parse("https://user:pw@bell.example.org"))
        assertNull(BellAddress.parse("https://bell.example.org/?x=1"))
        assertNull(BellAddress.parse("https://bell.example.org/#top"))
        assertNull(BellAddress.parse("not a url at all"))
        assertEquals("Enter the Bell's web address, such as https://bell.example.org.", BellAddress.problem(""))
    }

    @Test
    fun `routes hang off the address`() {
        assertEquals("https://example.org/bell/v1/info", BellAddress.parse("https://example.org/bell/")?.route("/v1/info"))
    }

    @Test
    fun `a kept address that isn't one doesn't restore`() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(
            WakeUpChoice.OwnBell(BellAddress("https://bell.example.org")),
            json.decodeFromString(WakeUpChoice.serializer(), """{"type":"own","bell":{"url":"https://bell.example.org"}}"""),
        )
        assertEquals(WakeUpChoice.TimerOnly, json.decodeFromString(WakeUpChoice.serializer(), """{"type":"timer"}"""))
        assertFailsWith<IllegalArgumentException> { BellAddress("javascript:alert(1)") }
    }
}
