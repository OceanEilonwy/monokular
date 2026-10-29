@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.monokular

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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import kotlin.math.min
import kotlin.math.abs
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import app.monokular.ui.Motion
import app.monokular.ui.motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest

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
    /**
     * How far down, of the height, a sale that came up by itself starts, rising into place: a slide, not a scale, so
     * its code is never drawn smaller than at rest and its words never through a changing scale (decision 141).
     */
    const val RISE_BY = 0.04f

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

    /**
     * A screen's words at [toward] of a move (the stage's progress toward its target), fading through: [isArriving],
     * in over the last [arriving] of it; else out over the first [leaving]. With the two shares adding to less than
     * the whole move, the two screens' words are never both drawn (decision 131).
     */
    fun through(toward: Float, isArriving: Boolean, leaving: Float, arriving: Float): Float =
        if (isArriving) ((toward - 1f + arriving) / arriving).coerceIn(0f, 1f) else ((leaving - toward) / leaving).coerceIn(0f, 1f)

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
        Screen.Square -> 4
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
        // The grounds move between the two and each screen's words fade through over them, per draw. The screens
        // themselves don't fade: the one going stays as it is under the moving ground until it lands (it covers it by
        // then, or it is the one shown), so no frame composites a whole screen through an alpha layer (decisions 125, 128).
        // The order page draws no ground of its own while it grows: the sale's, the same colour, is under it throughout.
        Move.RowGrow, Move.CodeGrow, Move.OrderGrow -> EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished
        // The sale slides up whole and opaque, its code included (decision 148: a customer's camera may already be on
        // it); its ground and words fade in per draw on the same progress ([sharedGround]'s fadesIn, [words]).
        Move.Rise -> slideInVertically(motion.track.spec(IntOffset.VisibilityThreshold)) { (it * Nav.RISE_BY).toInt() } togetherWith fadeOut(quick)
        Move.FadeThrough -> (fadeIn(fade) + scaleIn(motion.track.fraction, initialScale = Nav.SETTINGS_FROM)) togetherWith fadeOut(quick)
        is Move.Slide -> {
            val towards = if (move.forward) AnimatedContentTransitionScope.SlideDirection.Start else AnimatedContentTransitionScope.SlideDirection.End
            val slide = motion.track.spec(IntOffset.VisibilityThreshold)
            (slideIntoContainer(towards, slide) { (it * Nav.STEP_IN).toInt() } + fadeIn(fade)) togetherWith
                (slideOutOfContainer(towards, slide) { (it * Nav.STEP_OUT).toInt() } + fadeOut(quick))
        }
        // Under Still a change to or from a screen that may show a payment code is a cut: its code and its white are
        // whole on the first frame, never two codes of two sizes through each other (decision 148).
        Move.Crossfade -> if (mayShowCode(initialState) || mayShowCode(targetState)) EnterTransition.None togetherWith ExitTransition.None else fadeIn(quick) togetherWith fadeOut(quick)
    }
    // Screens fill the window: no size to animate.
    return transform.apply { targetContentZIndex = if (back) -1f else 0f }.using(null)
}

/** A sale's screen (its code while it's live) or its enlarged code. */
private fun mayShowCode(screen: Screen) = screen is Screen.Sale || screen is Screen.Code

/** The screen transition in progress and the scopes a shared surface needs, for the screens under [TillStage]. */
@Stable
class Stage internal constructor(
    val shared: SharedTransitionScope,
    val content: AnimatedVisibilityScope,
    private val transition: Transition<Screen>,
    private val held: HeldMove,
    private val scrub: BackScrub,
    private val shownState: State<Float>,
    private val screen: Screen,
    private val progress: () -> Float,
    private val root: StageRoot,
) {
    /**
     * The move under way between the two screens of the transition, read when a surface asks, not in composition;
     * null at rest, so nothing done "during" a move outlives it (a code or order screen is the same order on both
     * sides of a still stage, which [Nav.move] would otherwise call its grow: decision 130).
     */
    fun moving(): Move? = held.during(transition.currentState, transition.targetState)

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

    /**
     * This screen's own words and parts while the screens change by
     * [move]: they fade through, never across (decision 131). Leaving,
     * they are gone by [Motion.fadeOutOnTrack] of the move; arriving, they
     * come in over its last [Motion.crossfadeOnTrack]; so two screens'
     * words are never drawn at once, and each is drawn at the size it was
     * laid out at, never scaled (decision 128). A function of the stage's
     * progress toward this screen, so a cancelled gesture fades back the
     * way it came; 1 at rest.
     */
    fun throughDuring(move: Move, leaving: Float, arriving: Float): Float =
        if (moving() != move) 1f else Nav.through(progress(), transition.targetState == screen, leaving, arriving)

    /**
     * Whether this screen has been left and the stage is at rest on another: the frame on which the change lands
     * still composes it (its exit holds until the transition finishes), and it would draw whole, over the screen
     * arriving underneath on the way back, for that one frame. Read in draw.
     */
    fun gone(): Boolean = transition.currentState == transition.targetState && transition.targetState != screen

    /** Whether this screen is the one the stage is going to. */
    fun arriving(): Boolean = transition.targetState == screen

    /** Whether [move] is the one under way: read in draw, where it's needed. */
    fun isMoving(move: Move): Boolean = moving() == move

    /** Where [coordinates] sit in the stage's overlay, where the grounds move: read in draw. */
    fun inOverlay(coordinates: LayoutCoordinates): Offset = root.coordinates?.localPositionOf(coordinates, Offset.Zero) ?: Offset.Zero
}

/**
 * The move of the screen change under way, decided once per change
 * (decision 149): the first to ask, the transform or a surface drawing,
 * reads [cameUpByItself] for the pair, and everything after gets the same
 * answer until the stage is at rest, so a flag the till sets mid-move
 * can't turn a row's grow into a rise half way through it.
 */
internal class HeldMove(private val cameUpByItself: () -> Boolean, private val still: Boolean) {
    private var from: Screen? = null
    private var to: Screen? = null
    private var move: Move = Move.Crossfade

    /** The move from [from] to [to]; null at rest ([from] is [to]), which also forgets the last change. */
    fun during(from: Screen, to: Screen): Move? {
        if (from == to) {
            this.from = null
            this.to = null
            return null
        }
        if (from != this.from || to != this.to) {
            this.from = from
            this.to = to
            move = Nav.move(from, to, cameUpByItself(), still)
        }
        return move
    }
}

/** The stage's own layout, where the moving grounds are drawn. */
class StageRoot {
    var coordinates: LayoutCoordinates? = null
        internal set
}

/** A back gesture over the stage, from its start until it has committed or sprung back. */
@Stable
class BackScrub {
    var active by mutableStateOf(false)
        internal set
}

/**
 * The one thing that moves [state] to the model's [screen]: every change
 * the till or a tap makes runs on [Motion.stage], and none while a back
 * gesture holds the stage. When a gesture lets go, committed or cancelled,
 * the stage goes to wherever the model is by then, so a sale coming up or
 * a payment landing under the finger is followed rather than lost
 * (decision 129): at rest the stage and the model always agree.
 */
@Composable
fun FollowScreen(state: SeekableTransitionState<Screen>, scrub: BackScrub, screen: State<Screen>) {
    val m = motion
    LaunchedEffect(state, scrub, m) {
        snapshotFlow { if (scrub.active) null else screen.value }.collectLatest { s ->
            if (s == null || (state.currentState == s && state.targetState == s)) return@collectLatest
            try {
                state.animateTo(s, m.stage)
            } catch (e: CancellationException) {
                // A gesture's seek took the stage (its mutex cancels this run, not this driver): the gesture's
                // end emits again and the stage is put back on the model's screen then.
                currentCoroutineContext().ensureActive()
            }
        }
    }
}

/**
 * Android's back over the stage. What it goes back from is the model's
 * screen as the gesture starts ([current]), and where to is asked of
 * [target] then too (the model as it is, not as the last composition saw
 * it); never the stage's own current state, which mid-move is still the
 * screen before. On a settled stage the gesture seeks [state] toward the
 * target, so each part of the move is as far along as the finger (decision
 * 112); during a move, or with no target, or under Still, there's no
 * preview. Released, [onCommit] is told the screen it left and the stage
 * follows the model from where the finger left it ([FollowScreen]);
 * cancelled, the stage springs back to the model's screen. [scrub] is held
 * from the gesture's start until it has committed or been cancelled.
 */
@Composable
fun StageBack(
    enabled: Boolean,
    state: SeekableTransitionState<Screen>,
    scrub: BackScrub,
    current: () -> Screen,
    target: () -> Screen?,
    onCommit: (from: Screen) -> Unit,
) {
    val m = motion
    PredictiveBackHandler(enabled) { progress ->
        val from = current()
        val to = target()
        val settled = state.currentState == from && state.targetState == from
        if (to == null || m.still || !settled) {
            // No preview: a move is running (Back retargets it, from wherever it is), or there's nowhere to scrub to.
            progress.collect {}
            onCommit(from)
            return@PredictiveBackHandler
        }
        scrub.active = true
        try {
            // The screen back goes to is composed and laid out at 0 before the finger moves anything, so every part's
            // track starts at the same 0 and a progress of p is p of the way for all of them.
            state.seekTo(0f, to)
            withFrameNanos {}
            withFrameNanos {}
            progress.collect { state.seekTo(it.progress, to) }
            onCommit(from)
        } finally {
            // Committed or cancelled, the stage is the driver's again: it goes on to the model's screen from here.
            scrub.active = false
        }
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
    val root = remember { StageRoot() }
    val held = remember(m) { HeldMove(cameUpByItself, m.still) }
    // At rest the held move is forgotten, so the next change between the same two screens is read afresh: no surface
    // need draw at rest for it to be (nothing recomposes for this; the flow runs off the frame).
    LaunchedEffect(transition, held) {
        snapshotFlow { transition.currentState == transition.targetState }.collect { if (it) held.during(transition.currentState, transition.currentState) }
    }
    SharedTransitionLayout(Modifier.onPlaced { root.coordinates = it }) {
        val shared = this
        transition.AnimatedContent(
            transitionSpec = {
                transform(held.during(initialState, targetState) ?: Move.Crossfade, m, Nav.isBack(initialState, targetState))
            },
        ) { screen ->
            val visibility = (this as AnimatedVisibilityScope).transition
            val shown = visibility.animateFloat({ m.track.fraction }, label = "shown") { if (it == EnterExitState.Visible) 1f else 0f }
            val stage = remember(m) { Stage(shared, this, transition, held, scrub, shown, screen, { state.fraction }, root) }
            // A screen left draws nothing on the frame its change lands: the one arriving is the picture by then.
            Box(Modifier.drawWithContent { if (!stage.gone()) drawContent() }) {
                CompositionLocalProvider(LocalStage provides stage) { content(screen) }
            }
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
 * crossfades. Only for what has no words in it (a badge's glyph, the
 * code's bitmap): a glyph drawn through a changing scale is rasterised
 * again at every scale (decision 128), so words ride a [sharedGround]
 * instead and fade through at their own size. Any other move, or Still, leaves it alone, so a sale that
 * came up by itself doesn't grow out of a row the cashier didn't tap.
 * Without [fade] both sides draw fully throughout: for the code, the same
 * bitmap on both, so every frame shows one solid, scannable code. A
 * surface with a higher [layer] draws over one with a lower while both
 * move: the code over its ground.
 */
@Composable
fun Modifier.sharedSurface(key: String, during: Move, clip: Shape = RectangleShape, fade: Boolean = true, layer: Float = 0f, track: GroundTrack? = null): Modifier {
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
    // With a [track] (a rectangle: its looks unset) the surface's bounds on each frame are where a device test reads them.
    val overlayClip = track ?: remember(shared, clip) { shared.OverlayClip(clip) }
    val surface = with(shared) {
        sharedBounds(
            // The face going stays drawn, opaque, for the whole move (an exit that holds alpha at 1, so no layer of its
            // own), and the crossfade below shows it fading (An exit that changes nothing is there to tie the leaving side's life in the
            // overlay to an animation of the transition, not to the screen's KeepUntilTransitionsFinished alone. On Compose
            // animation 1.12.1 under this stage, ExitTransition.None here changed no frame RunningMovesTest or
            // MotionFramesTest reads (decision 161); MotionFramesTest.theLeavingRowsGroundIsStillDrawnAtThreeQuartersOfItsGrow
            // fails if the leaving ground ever stops being drawn mid-grow, whichever way it is kept.)
            state, stage.content, enter = EnterTransition.None, exit = if (fade) fadeOut(m.track.fraction, targetAlpha = 1f) else ExitTransition.None,
            boundsTransform = bounds, resizeMode = ScaledToWidth, zIndexInOverlay = layer, clipInOverlayDuringTransition = overlayClip,
        )
    }
    // A face with no partner on this frame of its move (the match comes a frame late, and goes a frame early) is drawn
    // only by the screen that is the picture then: the enlarged code never shows full size over the sale for a frame,
    // nor the sale's small code over the white as the code screen leaves (decision 146).
    if (!fade) {
        return surface.drawWithContent {
            if (!stage.isMoving(during)) track?.bounds = null
            if (!stage.isMoving(during) || state.isMatchFound || stage.shown() >= HALF) drawContent()
        }
    }
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
 * A ground's shape on this frame of its move, and where it is, for the
 * words on it. The overlay asks it for the ground's clip as it draws, with
 * the bounds the ground has on that frame: the shape is drawn there and
 * then (a rounded rectangle whose corners go from the partner's to its
 * own), so the ground is where its bounds are on every frame, not where
 * its layout last put it, which lags the move by an update (decision
 * 132). Words fading in at their own size are clipped to the same bounds,
 * so none shows outside a ground still growing, over the screen it is
 * leaving.
 */
@Stable
class GroundTrack : SharedTransitionScope.OverlayClip {
    private val path = Path()
    private val radii = FloatArray(8)

    /** The ground's bounds in the stage's overlay on this frame of its move; null at rest, or with no shape this frame. */
    var bounds: Rect? = null
        internal set

    /** At rest: no shape, and no step to the next move's first. */
    internal fun rest() {
        bounds = null
        step = 0f
        lastLeft = Float.NaN
    }

    /** How far the ground's edges moved on the last frame, in the overlay's pixels; and its edges then. */
    private var step = 0f
    private var lastLeft = Float.NaN
    private var lastTop = 0f
    private var lastRight = 0f
    private var lastBottom = 0f

    /**
     * How far past its own [size] the ground's fill must reach, in its own pixels, to cover its shape on this frame
     * while its layout is an update behind: twice its last step, at the scale it is drawn; none before it has moved.
     */
    internal fun reach(size: androidx.compose.ui.geometry.Size): Float {
        val b = bounds ?: return 0f
        if (size.width <= 0f || size.height <= 0f || b.width <= 0f || b.height <= 0f) return 0f
        return 2f * step / min(b.width / size.width, b.height / size.height)
    }

    /** The ground's shared state: whether it has a partner to move to (a row off the shift has none). */
    internal var state: SharedContentState? = null

    /** What it is between, set as it composes. */
    internal var stage: Stage? = null
    internal var during: Move = Move.Crossfade
    internal var own: GroundLook? = null
    internal var other: GroundLook? = null

    /** Whether the ground is moving: it has a partner on the other screen. */
    internal val moving: Boolean get() = state?.isMatchFound == true

    override fun getClipPath(sharedContentState: SharedContentState, bounds: Rect, layoutDirection: LayoutDirection, density: Density): Path {
        this.bounds = bounds
        step = if (lastLeft.isNaN()) 0f else maxOf(abs(bounds.left - lastLeft), abs(bounds.top - lastTop), abs(bounds.right - lastRight), abs(bounds.bottom - lastBottom))
        lastLeft = bounds.left
        lastTop = bounds.top
        lastRight = bounds.right
        lastBottom = bounds.bottom
        val own = own
        val other = other ?: own
        val f = stage?.shownDuring(during) ?: 1f
        val top = if (own == null || other == null) 0f else with(density) { lerp(other.top.toPx(), own.top.toPx(), f) }
        val bottom = if (own == null || other == null) 0f else with(density) { lerp(other.bottom.toPx(), own.bottom.toPx(), f) }
        radii.fill(top, 0, 4)
        radii.fill(bottom, 4, 8)
        path.rewind()
        path.asAndroidPath().addRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, radii, android.graphics.Path.Direction.CW)
        return path
    }
}

/**
 * Where a screen's grounds get their tracks: one per ground, made once. A
 * device test provides its own maker, to read where each ground is drawn
 * on a frame of a running move.
 */
val LocalGroundTracks = staticCompositionLocalOf<(key: String) -> GroundTrack> { { GroundTrack() } }

/** [key]'s ground's track, for the ground and the words on it. */
@Composable
fun rememberGroundTrack(key: String): GroundTrack {
    val make = LocalGroundTracks.current
    return remember(key, make) { make(key) }
}

/** How a ground looks at rest on one side of a move: its colour, and its top and bottom corners. */
@Immutable
class GroundLook(val color: Color, val top: Dp, val bottom: Dp = top)

/**
 * [key]'s ground: an [own] coloured, rounded rectangle with nothing in it,
 * which moves between the two screens' nodes while they change by
 * [during] (on [Motion.track]), its colour and corners going from [other]
 * (its partner's) to its own on the same progress. While it moves, its
 * shape is its overlay clip ([GroundTrack]) filled with its colour, so it
 * is exactly at its bounds on every frame; what sits on it is not inside
 * it: its words fade through at their own size ([words]), the code is its
 * own surface over it (decision 113), so nothing on a ground is ever
 * drawn through a changing scale (decision 128). A side that can't know
 * its partner's look ([other] null: a sale doesn't know which of the
 * shift's rows it came from) draws nothing while the partner, which knows
 * both, draws the move.
 */
@Composable
fun Modifier.sharedGround(key: String, during: Move, own: GroundLook, other: GroundLook?, track: GroundTrack? = null, fadesIn: Move? = null): Modifier {
    val stage = LocalStage.current
    val m = motion
    val painter = remember { GroundPainter() }
    if (stage == null || m.still) return drawBehind { with(painter) { paint(own.color, own.top.toPx(), own.bottom.toPx()) } }
    val shared = stage.shared
    val config = remember(stage, during) {
        object : SharedContentConfig {
            override val SharedContentState.isEnabled: Boolean get() = stage.moving() == during
        }
    }
    val state = shared.rememberSharedContentState(key, config)
    val bounds = remember(m) { BoundsTransform { _, _ -> m.track.spec(Rect.VisibilityThreshold) } }
    val clip = track ?: remember { GroundTrack() }
    SideEffect {
        clip.state = state
        clip.stage = stage
        clip.during = during
        clip.own = own
        clip.other = other
    }
    return with(shared) {
        sharedBounds(
            // Each side stays in the overlay while it leaves (an exit that holds alpha at 1, so no layer), so its track
            // is told where it is on every frame of the move (An exit that changes nothing is there to tie the leaving side's life in the
            // overlay to an animation of the transition, not to the screen's KeepUntilTransitionsFinished alone. On Compose
            // animation 1.12.1 under this stage, ExitTransition.None here changed no frame RunningMovesTest or
            // MotionFramesTest reads (decision 161); MotionFramesTest.theLeavingRowsGroundIsStillDrawnAtThreeQuartersOfItsGrow
            // fails if the leaving ground ever stops being drawn mid-grow, whichever way it is kept.)
            state, stage.content, enter = EnterTransition.None, exit = fadeOut(m.track.fraction, targetAlpha = 1f),
            boundsTransform = bounds, resizeMode = ScaledToFill, zIndexInOverlay = Layers.GROUND,
            clipInOverlayDuringTransition = clip,
        )
    }.drawBehind {
        when {
            !stage.isMoving(during) -> {
                clip.rest()
                // Arriving by [fadesIn] (a sale rising by itself), it fades in on the stage's line; leaving by it, out.
                val seen = if (fadesIn != null && stage.isMoving(fadesIn)) stage.shown() else 1f
                with(painter) { paint(own.color.copy(alpha = own.color.alpha * seen), own.top.toPx(), own.bottom.toPx()) }
            }
            // Its colour everywhere the overlay's clip lets it show: the clip is the ground's shape on this frame, its
            // layout (the scale it is drawn at) an update behind. It reaches past its own size by twice the ground's
            // last step, never further (decision 160): a fill four sizes wide, drawn once by the overlay before its clip
            // was set, painted the whole window white before every enlarge (146).
            state.isMatchFound -> if (other != null) {
                val reach = clip.reach(size)
                drawRect(lerp(other.color, own.color, stage.shownDuring(during)), topLeft = Offset(-reach, -reach), size = Size(size.width + 2 * reach, size.height + 2 * reach))
            }
            // Its move is under way and the overlay has no shape for it on this frame (the match is found a frame after
            // the change starts, and lost a frame before it lands): the screen that is the picture on this frame draws
            // its ground whole and the other nothing, never the arriving one's at its layout size over the leaving one
            // (decision 146: a whole white window for a frame before every enlarge). A side that can't draw the move
            // ([other] null) keeps its ground: its partner, which would have drawn it, isn't there.
            other == null || stage.shown() >= HALF -> {
                clip.bounds = null
                with(painter) { paint(own.color, own.top.toPx(), own.bottom.toPx()) }
            }
            else -> clip.bounds = null
        }
    }
}

/** Half way through a screen change: before it the screen being left is the picture, after it the one arriving. */
private const val HALF = 0.5f


/** An empty ground stretched to its moving bounds as one layer: nothing inside to measure, nothing to rasterise again. */
private val ScaledToFill = ResizeMode.scaleToBounds(ContentScale.FillBounds, Alignment.Center)

/** Where a node was last placed, for a draw that needs it. */
private class Placed {
    var coordinates: LayoutCoordinates? = null
}

/** A rounded rectangle with its own top and bottom corners at rest; its path made once and reused. */
private class GroundPainter {
    private val path = Path()
    private val radii = FloatArray(8)

    fun DrawScope.paint(color: Color, top: Float, bottom: Float) {
        if (top == bottom) {
            drawRoundRect(color, cornerRadius = CornerRadius(top))
            return
        }
        radii.fill(top, 0, 4)
        radii.fill(bottom, 4, 8)
        path.rewind()
        path.asAndroidPath().addRoundRect(0f, 0f, size.width, size.height, radii, android.graphics.Path.Direction.CW)
        drawPath(path, color)
    }
}

/**
 * A screen's own words and parts (everything but its grounds, its code and
 * its badge, which are surfaces of their own) while the screens change:
 * faded through at their own size ([Stage.throughDuring]) while the move
 * is [over] or one of [through]; drawn over the moving grounds (they draw
 * over both screens) while it is [over] (clipped to [on], the ground they
 * sit on, where it is on that frame) or, arriving, one of [lift] (words
 * arriving where a ground still moves, so its edge never cuts them:
 * decision 147; leaving, they stay under the ground growing over them).
 * A move whose ground [on] has nowhere to go (a row the cashier didn't
 * tap) leaves them as they are. With [fades] false they are lifted and
 * clipped but not faded: a container whose words fade one by one around a
 * code that never fades (decision 148). Alpha is applied per draw, through
 * a layer made only while one of their moves is under way, never an
 * offscreen buffer and nothing at rest.
 */
@Composable
fun Modifier.words(
    over: Move? = null,
    through: Set<Move> = emptySet(),
    lift: Set<Move> = emptySet(),
    on: GroundTrack? = null,
    fades: Boolean = true,
    clear: GroundTrack? = null,
): Modifier {
    val stage = LocalStage.current ?: return this
    val m = motion
    if (m.still) return this
    val lifted = if (over == null && lift.isEmpty()) this else with(stage.shared) {
        renderInSharedTransitionScopeOverlay(Layers.OVER_GROUND) {
            if (!isTransitionActive) return@renderInSharedTransitionScopeOverlay false
            val move = stage.moving()
            (move != null && move == over && (on == null || on.moving)) || (move in lift && stage.arriving())
        }
    }
    val clipped = if (over == null || on == null) lifted else {
        val placed = remember { Placed() }
        // Clipped to the ground they sit on, where it is on this frame: nothing shows outside it, over the other screen.
        lifted.onPlaced { placed.coordinates = it }.drawWithContent {
            val ground = on.bounds
            val mine = placed.coordinates
            if (ground == null || mine == null || !stage.isMoving(over)) {
                drawContent()
            } else {
                val at = stage.inOverlay(mine)
                clipRect(ground.left - at.x, ground.top - at.y, ground.right - at.x, ground.bottom - at.y) { this@drawWithContent.drawContent() }
            }
        }
    }
    if (!fades || (over == null && through.isEmpty())) return clipped
    return clipped.then(FadeThroughElement(stage, over, through, on, clear, m.fadeOutOnTrack, m.crossfadeOnTrack))
}

private class FadeThroughElement(
    val stage: Stage,
    val over: Move?,
    val through: Set<Move>,
    val on: GroundTrack?,
    val clear: GroundTrack?,
    val leaving: Float,
    val arriving: Float,
) : ModifierNodeElement<FadeThroughNode>() {
    override fun create() = FadeThroughNode(stage, over, through, on, clear, leaving, arriving)

    override fun update(node: FadeThroughNode) {
        node.stage = stage
        node.over = over
        node.through = through
        node.on = on
        node.clear = clear
        node.leaving = leaving
        node.arriving = arriving
    }

    override fun equals(other: Any?): Boolean = other is FadeThroughElement && other.stage === stage && other.over == over &&
        other.through == through && other.on === on && other.clear === clear && other.leaving == leaving && other.arriving == arriving

    override fun hashCode(): Int = ((stage.hashCode() * 31 + over.hashCode()) * 31 + through.hashCode()) * 31 + on.hashCode()
}

/**
 * [Modifier.words]' fade: placed with a layer only while one of its moves
 * is under way (the stage's states are read in placement, so this places
 * again as a move starts and lands, and nothing per frame), its alpha read
 * in that layer's block per frame. At rest a shift's rows carry no layer
 * each (R3-O5).
 */
private class FadeThroughNode(
    var stage: Stage,
    var over: Move?,
    var through: Set<Move>,
    var on: GroundTrack?,
    var clear: GroundTrack?,
    var leaving: Float,
    var arriving: Float,
) : Modifier.Node(), LayoutModifierNode, LayoutAwareModifierNode {
    private var placed: LayoutCoordinates? = null

    override fun onPlaced(coordinates: LayoutCoordinates) {
        placed = coordinates
    }

    private val layer: GraphicsLayerScope.() -> Unit = {
        alpha = alpha()
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }

    /** One of its moves: one its ground has somewhere to go in, if it has a ground. */
    private fun fades(move: Move?): Boolean {
        if (move == null || (move != over && move !in through)) return false
        val ground = on ?: return true
        return ground.during != move || ground.moving
    }

    private fun alpha(): Float {
        val move = stage.moving()
        if (!fades(move)) return 1f
        val a = stage.throughDuring(move!!, leaving, arriving)
        // Arriving where [clear]'s ground still is, they wait until it has passed: no edge sweeps through them.
        return if (a > 0f && stage.arriving() && meets(clear?.bounds)) 0f else a
    }

    private fun meets(ground: Rect?): Boolean {
        val mine = placed ?: return false
        if (ground == null || !mine.isAttached) return false
        val at = stage.inOverlay(mine)
        val w = mine.size.width
        val h = mine.size.height
        return at.x < ground.right && at.x + w > ground.left && at.y < ground.bottom && at.y + h > ground.top
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            if (fades(stage.moving())) placeable.placeWithLayer(0, 0, layerBlock = layer) else placeable.place(0, 0)
        }
    }
}

/**
 * A screen's [color] ground, except while the screens change by [unless]:
 * the order page over the sale, whose ground is the same colour and stays
 * under it, so the page's words can fade in over the sale's going without
 * a whole page fading (decision 128).
 */
@Composable
fun Modifier.groundExcept(unless: Move, color: Color): Modifier {
    val stage = LocalStage.current ?: return drawBehind { drawRect(color) }
    return drawBehind { if (!stage.isMoving(unless)) drawRect(color) }
}

/** A sale's face (its live code, or its page) for the surfaces that morph between the two (M10). */
val LocalFace = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * [key]'s surface between a sale's two faces ([LocalFace]): while one
 * turns into the other in place, its bounds move between the two faces'
 * nodes on [Motion.surface], its content (a code or a glyph, no words)
 * scaled as a layer and crossfading per draw (the going face out under
 * [Motion.fadeQuick], the coming one in with [Motion.fade]), each face
 * clipped in its own coordinates to a rounded rectangle whose corners go
 * from [other] (its partner's, as a share of the side: a circle is 0.5)
 * to [corner] on the same spring: the code card closing into the hero's
 * circle. The clip is the content's own, so it is where the content is on
 * every frame (decision 139).
 */
@Composable
fun Modifier.faceSurface(key: String, corner: Dp, other: Float): Modifier {
    val stage = LocalStage.current ?: return this
    val face = LocalFace.current ?: return this
    val m = motion
    if (m.still) return this
    val shared = stage.shared
    val state = shared.rememberSharedContentState(key)
    val bounds = remember(m) { BoundsTransform { _, _ -> m.surface.spec(Rect.VisibilityThreshold) } }
    val shown = face.transition.animateFloat({ m.surface.fraction }, label = "face") { if (it == EnterExitState.Visible) 1f else 0f }
    val seen = face.transition.animateFloat(
        { if (targetState == EnterExitState.Visible) m.fade.fraction else m.fadeQuick.fraction }, label = "face seen",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val clip = remember { RoundClip() }
    return with(shared) {
        // Over every clip for as long as its face is changing: a page's hero sits in a scroll, whose clip would cut
        // the circle on the frames the shared bounds hand drawing back before the change has landed.
        renderInSharedTransitionScopeOverlay(Layers.GROUND) { face.transition.isRunning }.sharedBounds(
            // The going face stays drawn for the whole change (an exit holding alpha at 1, so no layer of its own);
            // the crossfade is the per-draw alpha below (An exit that changes nothing is there to tie the leaving side's life in the
            // overlay to an animation of the transition, not to the screen's KeepUntilTransitionsFinished alone. On Compose
            // animation 1.12.1 under this stage, ExitTransition.None here changed no frame RunningMovesTest or
            // MotionFramesTest reads (decision 161); MotionFramesTest.theLeavingRowsGroundIsStillDrawnAtThreeQuartersOfItsGrow
            // fails if the leaving ground ever stops being drawn mid-grow, whichever way it is kept.)
            state, face, enter = EnterTransition.None, exit = fadeOut(m.surface.fraction, targetAlpha = 1f),
            boundsTransform = bounds, resizeMode = ScaledToFit, zIndexInOverlay = Layers.GROUND,
        )
    }.graphicsLayer {
        alpha = seen.value
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }.drawWithContent {
        if (!face.transition.isRunning) {
            drawContent()
        } else {
            val side = size.minDimension
            val r = lerp(other * side, corner.toPx(), shown.value).coerceAtMost(side / 2f)
            clipPath(clip.around(size, r)) { this@drawWithContent.drawContent() }
        }
    }
}

/** Scaled as one layer to fit the bounds, centred: a square card into a circle. */
private val ScaledToFit = ResizeMode.scaleToBounds(ContentScale.Fit, Alignment.Center)

/** A rounded rectangle the size of what it clips, one path rewound each frame, so a frame allocates nothing. */
private class RoundClip {
    private val path = Path()

    fun around(size: androidx.compose.ui.geometry.Size, r: Float): Path {
        path.rewind()
        path.asAndroidPath().addRoundRect(0f, 0f, size.width, size.height, r, r, android.graphics.Path.Direction.CW)
        return path
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
