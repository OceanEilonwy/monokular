package app.monokular

import app.monokular.push.BellAddress
import app.monokular.push.WakeUpStatus
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Settings › Wake-ups' words, against acceptance A1.6–A1.8 exactly (D15). */
class WakeUpsTextTest {
    private val ding = Instant.parse("2026-10-28T15:58:00Z").epochSecond
    private val us = Locale.US
    private val utc = ZoneId.of("UTC")

    /** One of every status. */
    private val every = listOf(
        WakeUpStatus.Off,
        WakeUpStatus.NoSquare,
        WakeUpStatus.NoDistributor,
        WakeUpStatus.ChooseDistributor(listOf("io.heckel.ntfy", "org.unifiedpush.distributor.sunup")),
        WakeUpStatus.Connecting,
        WakeUpStatus.Connected(lastDingAt = null),
        WakeUpStatus.MissingWakeups(lastDingAt = ding),
        WakeUpStatus.Unreachable("no connection"),
        WakeUpStatus.Refused("this location already has 32 tills registered"),
        WakeUpStatus.SignInNeeded("sandbox-sq0idb-other"),
    )

    @Test
    fun `the Bell row says each status in a word or two`() {
        assertEquals(
            listOf(
                "Off", "Sign in needed", "Needs a UnifiedPush app", "Choose a UnifiedPush app", "Connecting…", "Connected",
                "Wake-ups not arriving", "Not connected", "Not connected", "Sign in needed",
            ),
            every.map(WakeUpsText::word),
        )
    }

    @Test
    fun `Checking Square says 30 s while connected and 2 s otherwise, the unit on the number's line`() {
        val two = "Every 2 s"
        assertEquals(listOf(two, two, two, two, two, "Every 30 s", two, two, two, two), every.map(WakeUpsText::pace))
    }

    @Test
    fun `Last wake-up shows only with a ding clock, as the shift writes times, or none yet`() {
        assertEquals("None yet", WakeUpsText.lastWakeUp(WakeUpStatus.Connected(null), us, utc))
        assertEquals("3:58 PM", WakeUpsText.lastWakeUp(WakeUpStatus.Connected(ding), us, utc))
        assertEquals("3:58 PM", WakeUpsText.lastWakeUp(WakeUpStatus.MissingWakeups(ding), us, utc))
        assertEquals(SaleText.time(ding, us, utc), WakeUpsText.lastWakeUp(WakeUpStatus.Connected(ding), us, utc))
        every.filterNot { it is WakeUpStatus.Connected || it is WakeUpStatus.MissingWakeups }
            .forEach { assertNull(WakeUpsText.lastWakeUp(it, us, utc), "$it has no Last wake-up row") }
    }

    @Test
    fun `every status has its footnote, word for word`() {
        assertEquals(
            listOf(
                "Monokular checks Square every 2 seconds during a shift. A Bell lets Square wake the till within a second or two of a sale, " +
                    "so it can check every 30 seconds instead.",
                "Sign in to Square to use the Bell. Until then Monokular shows other tills' sales only.",
                "Wake-ups need a UnifiedPush app such as ntfy to reach this till. Install one, then come back here. " +
                    "Until then Monokular checks every 2 seconds.",
                "Deliver wake-ups through one of the UnifiedPush apps above. Until you choose, Monokular checks every 2 seconds.",
                "Connecting to the Bell…",
                "Square wakes this till through the Bell within a second or two of a sale. Monokular still checks every 30 seconds " +
                    "in case a wake-up is lost.",
                "A sale came in without a wake-up from the Bell. Monokular is checking Square every 2 seconds until the Bell's " +
                    "wake-ups come back.",
                "Monokular couldn't reach the Bell (no connection). It's checking Square every 2 seconds and will try the Bell again.",
                "The Bell refused this till: this location already has 32 tills registered. Monokular is checking Square every 2 seconds.",
                "This Bell uses a different Square application. Sign in to Square through it to use the Bell.",
            ),
            every.map(WakeUpsText::footnote),
        )
    }

    @Test
    fun `every reason reads in its sentence, and one that is a sentence keeps one full stop`() {
        assertEquals(
            listOf(
                "Monokular couldn't reach the Bell (it didn't answer). It's checking Square every 2 seconds and will try the Bell again.",
                "Monokular couldn't reach the Bell (it answered 503). It's checking Square every 2 seconds and will try the Bell again.",
                "Monokular couldn't reach the Bell (it isn't a Bell). It's checking Square every 2 seconds and will try the Bell again.",
            ),
            listOf("it didn't answer", "it answered 503", "it isn't a Bell").map { WakeUpsText.footnote(WakeUpStatus.Unreachable(it)) },
        )
        assertEquals(
            "The Bell refused this till: Square didn't confirm this location. Sign in to Square again. " +
                "Monokular is checking Square every 2 seconds.",
            WakeUpsText.footnote(WakeUpStatus.Refused("Square didn't confirm this location. Sign in to Square again.")),
        )
        assertEquals(
            "The Bell refused this till: too many registrations from this network: trying again in about a minute. " +
                "Monokular is checking Square every 2 seconds.",
            WakeUpsText.footnote(WakeUpStatus.Refused("too many registrations from this network: trying again in about a minute", retryAt = 1)),
        )
        assertEquals(
            "Monokular couldn't reach the Bell (the UnifiedPush app didn't answer: open it once). " +
                "It's checking Square every 2 seconds and will try the Bell again.",
            WakeUpsText.footnote(WakeUpStatus.Unreachable("the UnifiedPush app didn't answer: open it once")),
        )
    }

    @Test
    fun `the Shift footnote follows the pace`() {
        val timer = "While it's on, Monokular checks Square every 2 seconds and keeps a notification. Turn it off at the end of the day."
        val bell = "While it's on, the Bell wakes Monokular when a sale comes in and it checks Square every 30 seconds between wake-ups. " +
            "Turn it off at the end of the day."
        assertEquals(every.map { if (it is WakeUpStatus.Connected) bell else timer }, every.map(WakeUpsText::shift))
    }

    @Test
    fun `the choice rows' details and the privacy footnote`() {
        assertEquals("Checks Square every 2 seconds", WakeUpsText.TIMER_DETAIL)
        assertEquals("Enter its address", WakeUpsText.ownDetail(null))
        assertEquals(
            "Enter the address of the Bell you run, then press Use this Bell. Until then Wake-ups stay as they were.",
            WakeUpsText.NO_OWN_BELL_YET,
        )
        assertEquals("bell.thebeanroom.coffee", WakeUpsText.ownDetail(BellAddress.parse("bell.thebeanroom.coffee")))
        assertEquals("bell.example.org/bell", WakeUpsText.ownDetail(BellAddress.parse("https://bell.example.org/bell/")))
        // A debug build's http Bell shows its scheme, so it isn't mistaken for the https one of the same host (decision 124).
        assertEquals("http://10.0.2.2:8445", WakeUpsText.ownDetail(BellAddress.parse("http://10.0.2.2:8445", allowHttp = true)))
        assertEquals("http://10.0.2.2:8445", WakeUpsText.bell(BellAddress.parse("http://10.0.2.2:8445", allowHttp = true)!!))
        assertEquals("bell.monokulo.com", WakeUpsText.bell(BellAddress.parse("https://bell.monokulo.com")!!))
        assertEquals(
            "The Bell learns which Square location this till is at and when it takes payments, not what was sold. " +
                "Your Square sign-in is shown to it once, to prove the location, and never kept.",
            WakeUpsText.PRIVACY,
        )
    }

    @Test
    fun `the address field starts with the saved address, its scheme only when it isn't https`() {
        assertEquals("", WakeUpsText.ownField(null))
        val https = BellAddress.parse("bell.example.org/bell")
        assertEquals("bell.example.org/bell", WakeUpsText.ownField(https))
        assertEquals(https, BellAddress.parse(WakeUpsText.ownField(https)), "reads back as the same address")
        val http = BellAddress.parse("http://10.0.2.2:8445", allowHttp = true)
        assertEquals("http://10.0.2.2:8445", WakeUpsText.ownField(http))
        assertEquals(http, BellAddress.parse(WakeUpsText.ownField(http), allowHttp = true))
    }
}
