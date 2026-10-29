@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.monokular

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import app.monokular.ui.Motion
import app.monokular.ui.motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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

/**
 * [move] as the screens' enter and exit, with [motion]'s presets; going back puts the screen arriving underneath.
 * Everything spatial is on [Motion.track], a straight line: the stage's progress is what springs (decision 112).
 */
fun AnimatedContentTransitionScope<Screen>.transform(move: Move, motion: Motion, back: Boolean): ContentTransform {
    val fade = motion.fade.fraction
    val quick = motion.fadeQuick.fraction
    val transform = when (move) {
        // The shared surface grows or shrinks between the two and does the one crossfade, inside its bounds. The
        // screens themselves don't fade: the one going stays as it is under the surface until the surface lands
        // (it covers it by then, or it is the one shown), so no frame composites a whole screen through an alpha layer.
        Move.RowGrow, Move.CodeGrow -> EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished
        // Only the order's list is the surface: the page's ground, bar, heading and foot fade in over the sale as the
        // card grows (the sale staying under them), and out first on the way back, so the card and the page are one move.
        Move.OrderGrow ->
            if (back) EnterTransition.None togetherWith fadeOut(quick) else fadeIn(fade) togetherWith ExitTransition.KeepUntilTransitionsFinished
        Move.Rise -> (fadeIn(fade) + scaleIn(motion.track.fraction, initialScale = Nav.RISE_FROM)) togetherWith fadeOut(quick)
        Move.FadeThrough -> (fadeIn(fade) + scaleIn(motion.track.fraction, initialScale = Nav.SETTINGS_FROM)) togetherWith fadeOut(quick)
        is Move.Slide -> {
            val towards = if (move.forward) AnimatedContentTransitionScope.SlideDirection.Start else AnimatedContentTransitionScope.SlideDirection.End
            val slide = motion.track.spec(IntOffset.VisibilityThreshold)
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
    private val scrub: BackScrub,
    private val shownState: State<Float>,
    private val screen: Screen,
    private val progress: () -> Float,
) {
    /** The move under way between the two screens of the transition, read when a surface asks, not in composition. */
    fun moving(): Move = Nav.move(transition.currentState, transition.targetState, cameUpByItself(), still)

    /** Whether a back gesture holds the stage: it may yet spring back, so the screen it would leave is still the one up. */
    val scrubbing: Boolean get() = scrub.active

    /**
     * How far this screen is through arriving (0 to 1) or leaving (1 to 0),
     * on [Motion.track]: the line the surfaces' bounds move on, so a part
     * driven by it stays in step with them. Read in draw or layout.
     */
    fun shown(): Float = shownState.value

    /** [shown] while the screens change by [move]; 1 otherwise, as at rest. */
    fun shownDuring(move: Move): Float = if (moving() == move) shownState.value else 1f

    /**
     * This screen's face of a surface while the screens change by [move]:
     * the arriving face fades in over the first [Motion.crossfadeOnTrack]
     * of the move, the leaving one out over the last, so the surface always
     * shows one or the other. A function of the stage's progress toward
     * this screen, so a cancelled gesture fades back the way it came; 1 at
     * rest.
     */
    fun faceDuring(move: Move, share: Float): Float {
        if (moving() != move) return 1f
        // The stage's own progress, toward its target: the same number for both screens, whichever way it runs.
        val toward = progress()
        val mine = if (transition.targetState == screen) toward else 1f - toward
        return (mine / share).coerceIn(0f, 1f)
    }
}

/** A back gesture over the stage, from its start until it has committed or sprung back. */
@Stable
class BackScrub {
    var active by mutableStateOf(false)
        internal set
}

/**
 * Android's back over the stage. Where it goes is asked of [target] as the
 * gesture starts (the model as it is then, not as the last composition
 * saw it); the gesture seeks [state] toward it, so each part of the move
 * is as far along as the finger (decision 112), and the release runs the
 * rest on [Motion.stage]: committed, [onLanded] is told which screen was
 * left once the move has landed; cancelled, the screen springs back. With
 * no target, or under Still, there's no preview and [onBack] runs on
 * release. [scrub] is held from the gesture's start until it has
 * committed or sprung back.
 */
@Composable
fun StageBack(
    enabled: Boolean,
    state: SeekableTransitionState<Screen>,
    scrub: BackScrub,
    target: () -> Screen?,
    onBack: () -> Unit,
    onLanded: (from: Screen) -> Unit,
) {
    val m = motion
    if (m.still) {
        BackHandler(enabled, onBack)
        return
    }
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled) { progress ->
        val to = target()
        if (to == null) {
            progress.collect {}
            onBack()
            return@PredictiveBackHandler
        }
        val from = state.currentState
        scrub.active = true
        try {
            // The screen back goes to is composed and laid out at 0 before the finger moves anything, so every part's
            // track starts at the same 0 and a progress of p is p of the way for all of them.
            state.seekTo(0f, to)
            withFrameNanos {}
            withFrameNanos {}
            progress.collect { state.seekTo(it.progress, to) }
        } catch (e: CancellationException) {
            // Cancelled: the screen that was up springs back from wherever the finger left it, still held.
            scope.launch {
                try {
                    state.animateTo(from, m.stage)
                } finally {
                    scrub.active = false
                }
            }
            throw e
        }
        scrub.active = false
        state.animateTo(to, m.stage)
        onLanded(from)
    }
}

val LocalStage = staticCompositionLocalOf<Stage?> { null }

/**
 * The till's screens, one at a time, each reaching the next by its [Move]
 * (M7). [state] is seekable, so a back gesture can scrub the transition
 * before it commits; run it with [Motion.stage] (`state.animateTo(screen,
 * motion.stage)`), so a seek and a spring move every part along the same
 * line. [cameUpByItself] says the screen was changed by the
 * till (a new sale coming up), not the cashier.
 */
@Composable
fun TillStage(
    state: SeekableTransitionState<Screen>,
    cameUpByItself: () -> Boolean,
    scrub: BackScrub = remember { BackScrub() },
    content: @Composable (Screen) -> Unit,
) {
    val m = motion
    val transition = rememberTransition(state, label = "screen")
    SharedTransitionLayout {
        val shared = this
        transition.AnimatedContent(
            transitionSpec = {
                transform(Nav.move(initialState, targetState, cameUpByItself(), m.still), m, Nav.isBack(initialState, targetState))
            },
        ) { screen ->
            val visibility = (this as AnimatedVisibilityScope).transition
            val shown = visibility.animateFloat({ m.track.fraction }, label = "shown") { if (it == EnterExitState.Visible) 1f else 0f }
            val stage = remember(m) { Stage(shared, this, transition, cameUpByItself, m.still, scrub, shown, screen) { state.fraction } }
            CompositionLocalProvider(LocalStage provides stage) { content(screen) }
        }
    }
}

/** Laid out once at the screen's size and scaled as one layer while the bounds move: nothing inside is measured per frame. */
private val ScaledToWidth = ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopCenter)

/**
 * This node is [key]'s surface on whichever screen shows it: while the
 * screens change by [during], its bounds move from one screen's node to
 * the other's on [Motion.track], in step with the stage's progress (which
 * springs, or follows a back gesture), and its content, scaled as a layer,
 * crossfades. Any other move, or Still, leaves it alone, so a sale that
 * came up by itself doesn't grow out of a row the cashier didn't tap.
 * Without [fade] both sides draw fully throughout: for the code, the same
 * bitmap on both, so every frame shows one solid, scannable code. A
 * surface with a higher [layer] draws over one with a lower while both
 * move: the code over its ground.
 */
@Composable
fun Modifier.sharedSurface(key: String, during: Move, clip: Shape = RectangleShape, fade: Boolean = true, layer: Float = 0f): Modifier {
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
    val bounds = remember(m) { BoundsTransform { _, _ -> m.track.spec(Rect.VisibilityThreshold) } }
    val overlayClip = remember(shared, clip) { shared.OverlayClip(clip) }
    val surface = with(shared) {
        sharedBounds(
            // The face going stays drawn, opaque, for the whole move (an exit that holds alpha at 1, so no layer of its
            // own), and the crossfade below shows it fading.
            state, stage.content, enter = EnterTransition.None, exit = if (fade) fadeOut(m.track.fraction, targetAlpha = 1f) else ExitTransition.None,
            boundsTransform = bounds, resizeMode = ScaledToWidth, zIndexInOverlay = layer, clipInOverlayDuringTransition = overlayClip,
        )
    }
    if (!fade) return surface
    // The crossfade inside the bounds, per draw (ModulateAlpha): an enter or exit fade would composite each face through
    // an offscreen buffer of its own, a full-screen texture a frame for a sale, which the GPU's cache then keeps.
    val share = m.crossfadeOnTrack
    return surface.graphicsLayer {
        alpha = stage.faceDuring(during, share)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/** Where a surface's parts draw while they move: grounds under what's on them, the code over everything. */
object Layers {
    const val GROUND = 0f
    const val OVER_GROUND = 1f
    const val CODE = 2f
}

/**
 * [key]'s ground: a [color] rounded rectangle with nothing in it, which
 * moves between the two screens' nodes while they change by [during]
 * (on [Motion.track], with the surfaces on it), remeasured to its bounds
 * on each frame (it has nothing inside to measure) rather than scaled, and
 * with its corners going from [other] (its partner's) to [corner] (its
 * own) on the same progress, so both sides draw the same shape on every
 * frame and one ground is seen. It never holds what is on it: the code
 * is its own surface over it (decision 113).
 */
@Composable
fun Modifier.sharedGround(key: String, during: Move, color: Color, corner: Dp, other: Dp): Modifier {
    val stage = LocalStage.current
    val m = motion
    if (stage == null || m.still) return drawBehind { drawRoundRect(color, cornerRadius = CornerRadius(corner.toPx())) }
    val shared = stage.shared
    val config = remember(stage, during) {
        object : SharedContentConfig {
            override val SharedContentState.isEnabled: Boolean get() = stage.moving() == during
        }
    }
    val state = shared.rememberSharedContentState(key, config)
    val bounds = remember(m) { BoundsTransform { _, _ -> m.track.spec(Rect.VisibilityThreshold) } }
    return with(shared) {
        sharedBounds(
            state, stage.content, enter = EnterTransition.None, exit = ExitTransition.None,
            boundsTransform = bounds, resizeMode = ResizeMode.RemeasureToBounds, zIndexInOverlay = Layers.GROUND,
        )
    }.drawBehind {
        val r = lerp(other.toPx(), corner.toPx(), stage.shownDuring(during))
        drawRoundRect(color, cornerRadius = CornerRadius(r))
    }
}

/** A sale's face (its live code, or its page) for the surfaces that morph between the two (M10). */
val LocalFace = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * [key]'s surface between a sale's two faces ([LocalFace]): while one
 * turns into the other in place, its bounds move between the two faces'
 * nodes on [Motion.surface], its content scaled as a layer and
 * crossfading (the going face's under [Motion.fadeQuick], the coming
 * one's with [Motion.fade]), clipped to a rounded rectangle whose
 * corners go from [other] (its partner's) to [corner] (its own) on the
 * same spring: the code card closing into the hero's circle.
 */
@Composable
fun Modifier.faceSurface(key: String, corner: Dp, other: Dp): Modifier {
    val stage = LocalStage.current ?: return this
    val face = LocalFace.current ?: return this
    val m = motion
    if (m.still) return this
    val shared = stage.shared
    val state = shared.rememberSharedContentState(key)
    val bounds = remember(m) { BoundsTransform { _, _ -> m.surface.spec(Rect.VisibilityThreshold) } }
    val shown = face.transition.animateFloat({ m.surface.fraction }, label = "face") { if (it == EnterExitState.Visible) 1f else 0f }
    val clip = remember(corner, other, shown) { RoundClip(corner, other, shown) }
    return with(shared) {
        sharedBounds(
            state, face, enter = fadeIn(m.fade.fraction), exit = fadeOut(m.fadeQuick.fraction),
            boundsTransform = bounds, resizeMode = ScaledToFit, zIndexInOverlay = Layers.GROUND, clipInOverlayDuringTransition = clip,
        )
    }
}

/** Scaled as one layer to fit the bounds, centred: a square card into a circle. */
private val ScaledToFit = ResizeMode.scaleToBounds(ContentScale.Fit, Alignment.Center)

/**
 * A rounded rectangle whose corners go from [other] to [corner] as
 * [shown] goes from 0 to 1, read per frame; one path, reused, so a frame
 * allocates nothing.
 */
private class RoundClip(private val corner: Dp, private val other: Dp, private val shown: State<Float>) : SharedTransitionScope.OverlayClip {
    private val path = Path()

    override fun getClipPath(sharedContentState: SharedContentState, bounds: Rect, layoutDirection: LayoutDirection, density: Density): Path {
        val r = with(density) { lerp(other.toPx(), corner.toPx(), shown.value) }.coerceAtMost(bounds.minDimension / 2f)
        path.rewind()
        path.asAndroidPath().addRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, r, r, android.graphics.Path.Direction.CW)
        return path
    }
}

/**
 * Drawn over the moving surfaces while the screens change by [during]
 * (they draw over both screens), and faded with its screen's progress:
 * in over the second half of its arrival, once the ground has grown past
 * it, and out over the first half of its leaving. Alpha is applied per
 * draw, never through an offscreen buffer.
 */
@Composable
fun Modifier.overSurfaces(during: Move): Modifier {
    val stage = LocalStage.current ?: return this
    if (motion.still) return this
    return with(stage.shared) {
        renderInSharedTransitionScopeOverlay(Layers.OVER_GROUND) { isTransitionActive && stage.moving() == during }
    }.graphicsLayer {
        alpha = ((stage.shownDuring(during) - 0.5f) * 2f).coerceIn(0f, 1f)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/**
 * Whether the screen this is on is the one up: true at once with no stage,
 * else once its arrival has landed, and through a back gesture that may yet
 * spring back to it; false from the moment it is being left for good (the
 * gesture committed, or the till moved on). Read where it's needed, so only
 * that recomposes, at those moments.
 */
@Composable
fun up(): Boolean {
    val stage = LocalStage.current ?: return true
    val transition = stage.content.transition
    return remember(transition) {
        derivedStateOf {
            transition.currentState == EnterExitState.Visible && (transition.targetState == EnterExitState.Visible || stage.scrubbing)
        }
    }.value
}
