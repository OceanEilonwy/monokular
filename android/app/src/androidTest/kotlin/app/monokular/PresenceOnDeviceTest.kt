package app.monokular

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import app.monokular.overlay.Presence
import app.monokular.ui.LocalMotion
import app.monokular.ui.Motion
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An overlay window's progress on the test clock (M1, M2): it grows, reverses from where it is, and lands once. */
class PresenceOnDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private fun attached(motion: Motion, initial: Float = 0f): Presence {
        val presence = Presence(initial)
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalMotion provides motion) { presence.Attach() } }
        compose.mainClock.advanceTimeByFrame()
        return presence
    }

    @Test
    fun aCallBeforeTheWindowComposedWaitsForIt() {
        val presence = Presence()
        presence.enter()
        compose.mainClock.autoAdvance = false
        compose.setContent { presence.Attach() }
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(1f, presence.value)
    }

    @Test
    fun aGrowReversesFromWhereItIsAndOnlyTheLastExitLands() {
        val presence = attached(Motion.Full)
        presence.enter()
        compose.mainClock.advanceTimeBy(120)
        val mid = presence.value
        assertTrue(mid > 0.1f && mid < 0.95f, "part way up: $mid")
        var landed = 0
        presence.exit { landed++ }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertTrue(presence.value in 0.05f..1f, "the exit starts from where the grow got to: ${presence.value}")
        presence.enter()
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(1f, presence.value, "a tap mid-collapse grows it again")
        assertEquals(0, landed, "the interrupted exit never lands")
        presence.exit { landed++ }
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(0f, presence.value)
        assertEquals(1, landed)
    }

    @Test
    fun aFlingCollapsesSoonerThanALetGo() {
        val letGo = Presence(1f)
        val flung = Presence(1f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides Motion.Full) {
                letGo.Attach()
                flung.Attach()
            }
        }
        compose.mainClock.advanceTimeByFrame()
        var letGoAt = 0
        var flungAt = 0
        var frame = 0
        letGo.exit { letGoAt = frame }
        flung.exit(-6f) { flungAt = frame }
        while ((letGoAt == 0 || flungAt == 0) && frame < 200) {
            frame++
            compose.mainClock.advanceTimeByFrame()
        }
        assertTrue(flungAt in 1 until letGoAt, "flung down it's gone at frame $flungAt, let go at $letGoAt")
    }

    @Test
    fun underStillMotionItJumpsButStillLands() {
        val presence = attached(Motion.Still, initial = 1f)
        var landed = false
        presence.exit { landed = true }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(0f, presence.value)
        assertTrue(landed, "the window still goes, in the same order")
    }
}
