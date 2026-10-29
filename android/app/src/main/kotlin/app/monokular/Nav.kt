@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.monokular

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode
import androidx.compose.animation.SharedTransitionScope.SharedContentConfig
import androidx.compose.animation.SharedTransitionScope.SharedContentState
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import app.monokular.ui.Motion
import app.monokular.ui.motion

/** How one screen reaches another (`docs/motion.md` M7), and back the same way reversed. */
sealed interface Move {
    /** A shift row grows into its sale; the sale shrinks back into its row. */
    data object RowGrow : Move

    /** A sale that came up by itself rises a little out of the shift: no row was tapped. */
    data object Rise : Move

    /** The sale's order card grows into the order page. */
    data object OrderGrow : Move

    /** The sale's code card grows into the enlarged code. */
    data object CodeGrow : Move

    /** Settings is a different place: it fades through. */
    data object FadeThrough : Move

    /** The next onboarding step slides in from the end ([forward]), the last from the start. */
    data class Slide(val forward: Boolean) : Move

    /** Anything else, and every pair under [Motion.Still]. */
    data object Crossfade : Move
}

/** Which [Move] takes the till from one screen to the next. */
object Nav {
    /** How small a sale that came up by itself starts, rising to full size. */
    const val RISE_FROM = 0.94f

    /** How small Settings starts as it fades in. */
    const val SETTINGS_FROM = 0.96f

    /** How far, of the width, the next onboarding step slides in from. */
    const val STEP_IN = 0.3f

    /** How far, of the width, the last onboarding step slides out. */
    const val STEP_OUT = 0.1f

    fun move(from: Screen, to: Screen, cameUpByItself: Boolean, still: Boolean): Move {
        val same = from.orderIdOrNull() != null && from.orderIdOrNull() == to.orderIdOrNull()
        return when {
            still -> Move.Crossfade
            from == Screen.Settings || to == Screen.Settings -> Move.FadeThrough
            from == Screen.Till && to is Screen.Sale -> if (cameUpByItself) Move.Rise else Move.RowGrow
            from is Screen.Sale && to == Screen.Till -> Move.RowGrow
            // Another sale came up in this one's place: it rises, as one coming up from the shift does.
            from is Screen.Sale && to is Screen.Sale -> Move.Rise
            same && (from is Screen.Order || to is Screen.Order) -> Move.OrderGrow
            same && (from is Screen.Code || to is Screen.Code) -> Move.CodeGrow
            else -> {
                val a = step(from)
                val b = step(to)
                if (a != null && b != null && a != b) Move.Slide(forward = b > a) else Move.Crossfade
            }
        }
    }

    /** Whether going [from] to [to] is going back: up from a sale to the shift, from a code to its sale, through setup backwards. */
    fun isBack(from: Screen, to: Screen): Boolean {
        val a = depth(from)
        val b = depth(to)
        return if (a != null && b != null) b < a else (step(to) ?: 0) < (step(from) ?: 0)
    }

    /** Where a sale's screens sit: the shift under its sales, a sale under its code and order. */
    private fun depth(screen: Screen): Int? = when (screen) {
        Screen.Till -> 0
        is Screen.Sale -> 1
        is Screen.Code, is Screen.Order -> 2
        Screen.Settings -> 3
        else -> null
    }

    /** Onboarding's order, the shift last. */
    private fun step(screen: Screen): Int? = when (screen) {
        Screen.Welcome -> 0
        Screen.Scan -> 1
        is Screen.Confirm -> 2
        is Screen.Connected -> 3
        Screen.PointOfSale -> 4
        is Screen.Locations -> 5
        Screen.Alerts -> 6
        Screen.Float -> 7
        Screen.Ready -> 8
        Screen.Till -> 9
        else -> null
    }
}

/** [move] as the screens' enter and exit, with [motion]'s presets; going back puts the screen arriving underneath. */
fun AnimatedContentTransitionScope<Screen>.transform(move: Move, motion: Motion, back: Boolean): ContentTransform {
    val fade = motion.fade.fraction
    val quick = motion.fadeQuick.fraction
    val transform = when (move) {
        // The shared surface grows or shrinks between the two and does the one crossfade, inside its bounds. The
        // screens themselves don't fade: the one going stays as it is under the surface until the surface lands
        // (it covers it by then, or it is the one shown), so no frame composites a whole screen through an alpha layer.
        Move.RowGrow, Move.OrderGrow, Move.CodeGrow -> EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished
        Move.Rise -> (fadeIn(fade) + scaleIn(motion.surface.fraction, initialScale = Nav.RISE_FROM)) togetherWith fadeOut(quick)
        Move.FadeThrough -> (fadeIn(fade) + scaleIn(motion.surface.fraction, initialScale = Nav.SETTINGS_FROM)) togetherWith fadeOut(quick)
        is Move.Slide -> {
            val towards = if (move.forward) AnimatedContentTransitionScope.SlideDirection.Start else AnimatedContentTransitionScope.SlideDirection.End
            val slide = motion.surface.spec(IntOffset.VisibilityThreshold)
            (slideIntoContainer(towards, slide) { (it * Nav.STEP_IN).toInt() } + fadeIn(fade)) togetherWith
                (slideOutOfContainer(towards, slide) { (it * Nav.STEP_OUT).toInt() } + fadeOut(quick))
        }
        Move.Crossfade -> fadeIn(quick) togetherWith fadeOut(quick)
    }
    // Screens fill the window: no size to animate.
    return transform.apply { targetContentZIndex = if (back) -1f else 0f }.using(null)
}

/** The screen transition in progress and the scopes a shared surface needs, for the screens under [TillStage]. */
@Stable
class Stage internal constructor(
    val shared: SharedTransitionScope,
    val content: AnimatedVisibilityScope,
    private val transition: Transition<Screen>,
    private val cameUpByItself: () -> Boolean,
    private val still: Boolean,
) {
    /** The move under way between the two screens of the transition, read when a surface asks, not in composition. */
    fun moving(): Move = Nav.move(transition.currentState, transition.targetState, cameUpByItself(), still)
}

val LocalStage = staticCompositionLocalOf<Stage?> { null }

/**
 * The till's screens, one at a time, each reaching the next by its [Move]
 * (M7). [state] is seekable, so a back gesture can scrub the transition
 * before it commits. [cameUpByItself] says the screen was changed by the
 * till (a new sale coming up), not the cashier.
 */
@Composable
fun TillStage(state: SeekableTransitionState<Screen>, cameUpByItself: () -> Boolean, content: @Composable (Screen) -> Unit) {
    val m = motion
    val transition = rememberTransition(state, label = "screen")
    SharedTransitionLayout {
        val shared = this
        transition.AnimatedContent(
            transitionSpec = {
                transform(Nav.move(initialState, targetState, cameUpByItself(), m.still), m, Nav.isBack(initialState, targetState))
            },
        ) { screen ->
            val stage = remember(m) { Stage(shared, this, transition, cameUpByItself, m.still) }
            CompositionLocalProvider(LocalStage provides stage) { content(screen) }
        }
    }
}

/** Laid out once at the screen's size and scaled as one layer while the bounds move: nothing inside is measured per frame. */
private val ScaledToWidth = ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopCenter)

/**
 * This node is [key]'s surface on whichever screen shows it: while the
 * screens change by [during], its bounds move from one screen's node to
 * the other's with [Motion.surface] and its content, scaled as a layer,
 * crossfades. Any other move, or Still, leaves it alone, so a sale that
 * came up by itself doesn't grow out of a row the cashier didn't tap.
 */
@Composable
fun Modifier.sharedSurface(key: String, during: Move, clip: Shape = RectangleShape): Modifier {
    val stage = LocalStage.current ?: return this
    val m = motion
    if (m.still) return this
    val shared = stage.shared
    val config = remember(stage, during) {
        object : SharedContentConfig {
            override val SharedContentState.isEnabled: Boolean get() = stage.moving() == during
        }
    }
    val state = shared.rememberSharedContentState(key, config)
    val bounds = remember(m) { BoundsTransform { _, _ -> m.surface.spec(Rect.VisibilityThreshold) } }
    val overlayClip = remember(shared, clip) { shared.OverlayClip(clip) }
    return with(shared) {
        sharedBounds(
            state, stage.content,
            enter = fadeIn(m.fade.fraction), exit = fadeOut(m.fadeQuick.fraction),
            boundsTransform = bounds, resizeMode = ScaledToWidth, clipInOverlayDuringTransition = overlayClip,
        )
    }
}
