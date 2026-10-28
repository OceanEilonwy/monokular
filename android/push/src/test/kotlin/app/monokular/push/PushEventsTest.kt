package app.monokular.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the connector's callbacks mean: only a decrypted `{"wake":"square"}` is a ding (A8.35, A8.36). */
class PushEventsTest {
    private fun ding(text: String, decrypted: Boolean = true) = PushEvents.isDing(text.toByteArray(), decrypted)

    @Test
    fun `a ding is the decrypted object with wake square and nothing else`() {
        assertTrue(ding("""{"wake":"square"}"""))
        assertTrue(ding(""" { "wake" : "square" } """), "whitespace doesn't matter")
    }

    @Test
    fun `anything else is dropped`() {
        assertFalse(ding("""{"wake":"square"}""", decrypted = false), "not decrypted")
        assertFalse(ding(""), "empty")
        assertFalse(ding("wake square"), "not JSON")
        assertFalse(ding("""["wake","square"]"""), "not an object")
        assertFalse(ding(""""square""""), "a string")
        assertFalse(ding("""{"wake":"square","sale":"4500"}"""), "another key")
        assertFalse(ding("""{"wake":"clover"}"""), "another POS")
        assertFalse(ding("""{"wake":["square"]}"""), "not a string")
        assertFalse(ding("""{"wake":null}"""), "null")
        assertFalse(ding("""{}"""), "nothing")
        assertFalse(PushEvents.isDing(byteArrayOf(0xC3.toByte(), 0x28), decrypted = true), "not UTF-8")
    }

    @Test
    fun `an endpoint needs both keys to be a subscription`() {
        assertEquals(
            PushState.Subscribed(PushSubscription("https://ntfy.sh/up1", "BKey", "auth")),
            PushEvents.endpoint("https://ntfy.sh/up1", "BKey", "auth"),
        )
        assertEquals(PushState.Failed(PushFailure.NoKeys), PushEvents.endpoint("https://ntfy.sh/up1", null, null))
        assertEquals(PushState.Failed(PushFailure.NoKeys), PushEvents.endpoint("https://ntfy.sh/up1", "BKey", ""))
    }
}
