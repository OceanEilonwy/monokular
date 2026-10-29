package app.monokular.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import app.monokular.BubbleLook
import app.monokular.PaidBuzz
import app.monokular.SaleText
import app.monokular.core.SalePhase
import app.monokular.sales.Standing
import app.monokular.sales.TillSale
import app.monokular.sales.live
import app.monokular.sales.standing
import app.monokular.ui.CodeText
import app.monokular.ui.confirm
import app.monokular.ui.Fixed
import app.monokular.ui.LightTokens
import app.monokular.ui.Logo
import app.monokular.ui.MkIcons
import app.monokular.ui.Motion
import app.monokular.ui.OrderContent
import app.monokular.ui.OutlineButton
import app.monokular.ui.PhaseRing
import app.monokular.ui.PrimaryButton
import app.monokular.ui.QrCode
import app.monokular.ui.display
import app.monokular.ui.mk
import app.monokular.ui.motion
import app.monokular.ui.Countdown
import app.monokular.ui.rememberNowSeconds
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Which of the panel's two faces is up: the code, or the whole order (the design's PanelE). */
sealed interface PanelPage {
    data object Code : PanelPage
    data object Order : PanelPage
}

/** What the panel's middle shows: the code, the paid tick, or where an ended sale's code was. */
enum class PanelFace { Code, Paid, Ended }

/** The panel's words and its grabber's rule: pure, so they're tested on the JVM. */
object Panel {
    /** How fast a downward fling on the grabber has to be to lower the panel, in dp a second. */
    const val FLING_DP_PER_S = 800f

    /** The smallest screen width, in dp, from which the panel floats beside Square instead of filling the screen. */
    const val WIDE_DP = 600

    /**
     * Whether the panel floats (a tablet) rather than filling the screen
     * with the bubble docked above it (a phone): judged by the screen's
     * smallest width, so a phone on its side is still a phone and never
     * gets the floating card, which has no room for its code.
     */
    fun wide(smallestWidthDp: Int): Boolean = smallestWidthDp >= WIDE_DP

    /**
     * Whether letting go of the grabber lowers the panel: dragged down past
     * a quarter of its [height], or flung down at [fling] or faster
     * ([velocity] in the same units a second); else it springs back.
     */
    fun lowers(drag: Float, height: Float, velocity: Float, fling: Float): Boolean =
        velocity >= fling || (height > 0f && drag > height / 4f)

    /**
     * Whether the sale the panel showed, [shown], live at the last look
     * ([wasLive]), has just been paid: the panel then shows it paid for
     * [Motion.Dwell.PAID_MS] before moving on to the next sale
     * waiting, so the cashier sees the payment land where they're looking.
     */
    fun landed(sales: List<TillSale>, shown: String?, wasLive: Boolean): Boolean =
        wasLive && sales.firstOrNull { it.orderId == shown }?.phase == SalePhase.Paid

    /**
     * Whether the bubble pulses: lit for a code still waiting for its money
     * (Waiting, or part paid), and only the free bubble with the panel down;
     * the pulse calls the cashier to raise the panel, so once it's up, or
     * the payment is seen, there's nothing to call for.
     */
    fun pulses(look: BubbleLook, phase: SalePhase, panelUp: Boolean): Boolean =
        look == BubbleLook.Lit && !panelUp && (phase == SalePhase.Waiting || phase == SalePhase.Partial)

    /**
     * How fast the panel's collapse starts when the grabber lets it go: the
     * finger's [velocity] down (px a second) as progress a second over the
     * card's [height], negative because the panel's progress falls toward 0.
     */
    fun collapseVelocity(velocity: Float, height: Float): Float = if (height > 0f) 0f - velocity / height else 0f

    /**
     * How opaque a growing panel is at [raise]: all there by a third of the
     * way (`docs/motion.md` M1), so it reads as the bubble opening rather
     * than a card fading. Its shadow takes the same share, so the shadow
     * never outlives the card (decision 134).
     */
    fun shown(raise: Float): Float = (raise * 3f).coerceIn(0f, 1f)

    /**
     * The point a growing panel scales about, as fractions of its [card]
     * (both on screen): [from] itself on a phone, the docked bubble above
     * the card; on a tablet, whose window ends at the card, the card's
     * side nearest the bubble at [from], at the bubble's height held to
     * the card: the panel sits beside the bubble (decision 118), so it
     * grows out of the bubble's side of it (`docs/motion.md` §5).
     */
    fun origin(from: Offset, card: Rect, corner: Boolean): TransformOrigin {
        if (card.width <= 0f || card.height <= 0f) return TransformOrigin.Center
        val x = if (!corner) from.x else if (from.x < card.center.x) card.left else card.right
        val y = if (!corner) from.y else from.y.coerceIn(card.top, card.bottom)
        return TransformOrigin((x - card.left) / card.width, (y - card.top) / card.height)
    }

    /**
     * How far in from the screen's end edge a tablet's floating panel window
     * sits, in dp: beside the bubble when the bubble is on that edge, so the
     * card's corner nearest the bubble is next to it and never over it
     * (the window draws its card [FLOATING_SHADOW_DP] in from its edge, room
     * for the shadow); else just off the edge.
     */
    fun floatingInsetDp(bubbleOnEnd: Boolean): Int =
        if (bubbleOnEnd) BUBBLE_WINDOW_DP - FLOATING_SHADOW_DP + FLOATING_GAP_DP else FLOATING_EDGE_DP

    /** The free bubble's window: its 56 dp circle and the pulse's 16 dp each side. */
    const val BUBBLE_WINDOW_DP = 88

    /** How far in from its window's edge the floating card is drawn. */
    const val FLOATING_SHADOW_DP = 24

    /** Between the free bubble's window and the floating card beside it. */
    private const val FLOATING_GAP_DP = 8

    /** The floating window off the end edge when the bubble isn't there. */
    private const val FLOATING_EDGE_DP = 4

    /** What the panel shows for [sale]: its code while live, the tick once paid, else where the code was. */
    fun face(sale: TillSale): PanelFace = when (sale.standing) {
        is Standing.Live -> PanelFace.Code
        is Standing.Paid -> PanelFace.Paid
        else -> PanelFace.Ended
    }

    /** The header's second line before "Open the shift": the till's location for its own sale, else who took it. */
    fun where(sale: TillSale, location: String?): String = when {
        sale.found != null -> location ?: "This till"
        else -> SaleText.place(sale, location)
    }

    /** The heading of a sale whose code came down while the panel was up (acceptance P15). */
    fun endedHeading(sale: TillSale): String = when {
        !sale.refunded && sale.phase == SalePhase.DoubleSpend -> "Payment reversed · don't hand it over"
        else -> SaleText.status(sale)
    }

    /** What the cashier does about it: refund it from the shift, or on the till that took it. */
    fun endedLine(sale: TillSale): String = when {
        sale.refunded -> "Nothing more to do."
        sale.found == null -> "Refund it in Square on ${sale.deviceName ?: "the till that took it"}."
        else -> "Open the shift to refund it in Square."
    }
}

/**
 * What the panel can do: each lowers it or opens something, the overlay
 * decides. Made once per panel window and handed the sale it's about, so
 * a tick or a poll of the runner gives the panel the same actions and
 * Compose skips everything they reach.
 */
@Immutable
class PanelActions(
    val onLower: () -> Unit,
    /** The grabber flung or dragged past its rule: lowers the panel at that speed, in progress a second. */
    val onFlung: (Float) -> Unit,
    val onOpenShift: () -> Unit,
    val onOrder: () -> Unit,
    val onBackToCode: () -> Unit,
    val onCopy: (TillSale) -> Unit,
    val onCopyXmr: (TillSale) -> Unit,
    val onFullScreen: (TillSale) -> Unit,
)

/**
 * The bubble (the design's Bubble part), in each [look]: faded and grey
 * with nothing to do, white with the [phase]'s ring (and a pulse) for a
 * sale, the ring closed in green round the tick on its pale ground once
 * it's paid. Its colours are the design's whatever the theme: it sits over
 * another app. [confirms] is whether a payment landing on it buzzes:
 * only while Monokular is away ([PaidBuzz], decision 119). [pulse] is false for the bubble docked above the panel,
 * which has nothing left to call for; [panelUp] is the free bubble's word
 * that a panel is up beside it (a tablet's), which stops its pulse too.
 * [presence] is the free bubble's window appearing and going, which the
 * circle's shadow follows (decision 134).
 */
@Composable
fun BubbleFace(
    look: BubbleLook,
    pulse: Boolean = true,
    phase: SalePhase = SalePhase.Waiting,
    panelUp: Boolean = false,
    confirms: Boolean = true,
    presence: Presence? = null,
) = Box(
    Modifier.padding(if (pulse) BubblePadding else DockedPadding).clearAndSetSemantics {},
    contentAlignment = Alignment.Center,
) { Circle(look, pulse, phase, panelUp, confirms, presence) }

/** Room round the bubble's circle in its window: the pulse's halo reaches 16 dp out and isn't clipped. */
val BubblePadding = 16.dp

/** Round the docked bubble, which doesn't pulse: the design's spacing above the panel. */
private val DockedPadding = 12.dp

/** The bubble's circle: its window, with [BubblePadding] each side, is [Panel.BUBBLE_WINDOW_DP]. */
private val BubbleSize = 56.dp

private val BubbleElevation = 6.dp

/**
 * One circle whatever the look (`docs/motion.md` M4): its shadow and place
 * stay; its alpha and ground fade (read in the layer and in its draw, so
 * a change recomposes nothing per frame), the ring sweeps closed on paid,
 * and only the middle crossfades: the grey logo, the pupil, the tick.
 */
@Composable
private fun Circle(look: BubbleLook, pulse: Boolean, phase: SalePhase, panelUp: Boolean, confirms: Boolean, presence: Presence?) {
    val m = motion
    val alpha by animateFloatAsState(if (look == BubbleLook.Idle) 0.55f else 1f, m.fade.fraction, label = "bubble")
    val ground by animateColorAsState(
        when (look) {
            BubbleLook.Idle -> Fixed.White.copy(alpha = 0.9f)
            BubbleLook.Lit -> Fixed.White
            BubbleLook.Paid -> LightTokens.paidGround
        },
        m.fade.spec(),
        label = "ground",
    )
    val ringShown by animateFloatAsState(if (look == BubbleLook.Idle) 0f else 1f, m.fade.fraction, label = "ring")
    val turn = m.pulse
    val halo = if (turn != null && Panel.pulses(look, phase, panelUp = !pulse || panelUp)) {
        rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, turn, label = "halo")
    } else {
        null
    }
    if (confirms) Confirm(look)
    Box(
        Modifier.size(BubbleSize)
            .drawBehind {
                val p = halo?.value ?: return@drawBehind
                drawCircle(Fixed.Accent.copy(alpha = 0.55f * (1f - p)), radius = size.minDimension / 2 + BubblePadding.toPx() * p)
            }
            .graphicsLayer {
                this.alpha = alpha
                // The window's appear and go fade the circle per draw, which its own shadow doesn't see: it follows here.
                val shown = presence?.value ?: 1f
                shadowElevation = BubbleElevation.toPx() * shown
                ambientShadowColor = DefaultShadowColor.copy(alpha = shown)
                spotShadowColor = ambientShadowColor
                shape = CircleShape
                clip = true
            }
            .drawBehind { drawCircle(ground) },
        contentAlignment = Alignment.Center,
    ) {
        PhaseRing(
            if (look == BubbleLook.Paid) SalePhase.Paid else phase, 40.dp,
            Modifier.graphicsLayer { this.alpha = ringShown },
            track = Fixed.Track, fill = Fixed.Accent, paid = LightTokens.paid,
        )
        AnimatedContent(
            look,
            transitionSpec = { (fadeIn(m.fade.fraction) togetherWith fadeOut(m.fadeQuick.fraction)).using(null) },
            contentAlignment = Alignment.Center,
            label = "face",
        ) { shown ->
            when (shown) {
                BubbleLook.Idle -> Logo(38.dp, ring = Color(0xFF565656), grey = true)
                BubbleLook.Lit -> Box(Modifier.size(12.dp).clip(CircleShape).background(Fixed.Ink))
                BubbleLook.Paid -> Icon(MkIcons.Tick, contentDescription = null, tint = LightTokens.paid, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/**
 * One confirming buzz when a payment lands on the bubble the cashier can
 * see (`docs/motion.md` §6): not when a bubble comes up already paid (the
 * free one taking over from the docked one), and never from the panel.
 */
@Composable
private fun Confirm(look: BubbleLook) {
    val view = LocalView.current
    val last = remember { arrayOf(look) }
    LaunchedEffect(look) {
        if (look == BubbleLook.Paid && last[0] != BubbleLook.Paid) view.confirm()
        last[0] = look
    }
}

/**
 * The code over another app (the design's Panel over Square on a phone,
 * and the floating panel on a tablet): a grabber to lower it, the header
 * that opens the shift, the amount, what was bought, the code at a size
 * that never changes, its status, Copy address and Full screen. Paid, it
 * says so and lowers itself; ended unpaid, it sends the cashier to the
 * shift.
 * How long it shows paid is the overlay's ([Overlay.paid]).
 */
@Composable
fun SalePanel(
    sale: TillSale,
    where: String,
    wide: Boolean,
    actions: PanelActions,
    raise: Presence,
    sideBySide: Boolean = false,
    /** How far the words are in, read in their layers: the card, its ground and the code are there whatever it says. */
    words: State<Float> = Arrived,
) {
    // No Surface gives the overlay's window a text colour: the panel sets its own.
    CompositionLocalProvider(LocalContentColor provides mk.ink) { PanelCard(sale, where, wide, sideBySide, actions, raise, words) }
}

/** Words that are simply there. */
private val Arrived: State<Float> = object : State<Float> {
    override val value = 1f
}

@Composable
private fun PanelCard(sale: TillSale, where: String, wide: Boolean, sideBySide: Boolean, actions: PanelActions, raise: Presence, words: State<Float>) {
    // Everything but the code, which is never faded in (§10 rule 6).
    val wordsLayer = Modifier.graphicsLayer { alpha = words.value; compositingStrategy = CompositingStrategy.ModulateAlpha }
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    var height by remember { mutableFloatStateOf(0f) }
    val fling = with(LocalDensity.current) { Panel.FLING_DP_PER_S.dp.toPx() }
    val springBack = motion.flick.px
    // The panel's one clock (amendment 10), read only by the countdown.
    val now = rememberNowSeconds()
    // One transition for everything the face changes (M5): the fold, the words and the buttons start on one frame,
    // and each side keeps the sale it showed, so the code's words never say "Paid" on their way out.
    val faces = updateTransition(FaceShown(Panel.face(sale), sale), label = "face")
    Column(
        Modifier.then(if (wide) Modifier.width(360.dp) else Modifier.fillMaxWidth())
            .onSizeChanged { height = it.height.toFloat() }
            // On a phone the panel follows the finger down; the tablet's floating panel fades instead.
            .then(
                if (wide) {
                    Modifier.graphicsLayer { alpha = 1f - (drag.value / height.coerceAtLeast(1f)).coerceIn(0f, 0.8f) }
                } else {
                    // Let go past the rule, the drag shrinks with the panel into the dock.
                    Modifier.offset { IntOffset(0, (drag.value * raise.value).roundToInt()) }
                },
            )
            .card(raise)
            .background(mk.ground)
            .testTag(CARD_TAG)
            .semantics { paneTitle = "Monokular payment code" }
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val grabber = @Composable {
            Grabber(
                wordsLayer,
                onDrag = { delta -> scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) } },
                onRelease = { velocity ->
                    scope.launch {
                        if (Panel.lowers(drag.value, height, velocity, fling)) {
                            actions.onFlung(Panel.collapseVelocity(velocity, height))
                        } else {
                            drag.animateTo(0f, springBack, initialVelocity = velocity)
                        }
                    }
                },
                onTap = actions.onLower,
            )
        }
        if (sideBySide) {
            // A phone on its side has no height to spare: the code and its status on the right, at their full size,
            // from the panel's top; the grabber heads the rest beside them, the order on one line, a little closer.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    grabber()
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Details(sale, where, actions, wordsLayer, oneLine = true)
                        Buttons(faces, actions, wordsLayer)
                    }
                }
                Column(
                    Modifier.padding(top = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Face(faces, wide, now, wordsLayer)
                }
            }
        } else {
            grabber()
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Details(sale, where, actions, wordsLayer)
                Face(faces, wide, now, wordsLayer)
                Buttons(faces, actions, wordsLayer)
            }
        }
    }
}

/** The header, the amount and what was bought. */
@Composable
private fun Details(sale: TillSale, where: String, actions: PanelActions, words: Modifier, oneLine: Boolean = false) {
    Header(sale, where, actions.onOpenShift, words)
    Column(words.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(SaleText.amount(sale), style = display(44, -0.8))
        Text(
            SaleText.xmr(sale), fontSize = 15.sp, color = mk.muted,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Copy the XMR amount") { actions.onCopyXmr(sale) }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
    val receipt = sale.receipt
    if (receipt != null && receipt.lineItems.isNotEmpty()) OrderLine(SaleText.items(receipt), SaleText.firstItems(receipt), oneLine, actions.onOrder, words)
}

/** A face of the panel and the sale it was shown for: the side of a change that's leaving keeps its own. */
@Immutable
private data class FaceShown(val face: PanelFace, val sale: TillSale)

/**
 * The code and its status while live; the tick once paid; else where the
 * code was. One place for all three (`docs/motion.md` M5): paid or ended,
 * the white card scales down into the 88 dp circle at the same centre and
 * fades under it, the slot's height following; the next sale's code grows
 * back out of it. The code is laid out once at its full size and only its
 * layer scales, so it is never re-laid out; at rest it is exactly its
 * designed size. Everything that moves is read in layout or draw, and all
 * of it follows [faces], so the fold and the words start on one frame.
 */
@Composable
private fun Face(faces: Transition<FaceShown>, wide: Boolean, now: LongState, words: Modifier) {
    val m = motion
    val shown = faces.targetState
    val sale = shown.sale
    val live = shown.face == PanelFace.Code
    val codeSize = if (wide) 280.dp else 264.dp
    val fold = faces.animateFloat({ m.surface.fraction }, label = "fold") { if (it.face == PanelFace.Code) 0f else 1f }
    // The code only fades out, into a paid or ended hero, where nothing scans; the next sale's grows out of the hero
    // opaque from its first frame (§10 rule 6), so a camera already on the panel never sees it faint.
    val codeAlpha = faces.animateFloat({ m.fadeQuick.fraction }, label = "code") { if (it.face == PanelFace.Code) 1f else 0f }
    val heroAlpha = faces.animateFloat(
        { (if (targetState.face == PanelFace.Code) m.fadeQuick else m.fade).fraction }, label = "hero",
    ) { if (it.face == PanelFace.Code) 0f else 1f }
    // The last face the code folded into, kept while the next code grows out of it and let go once it has.
    val folded = remember { arrayOfNulls<TillSale>(1) }
    if (!live) folded[0] = sale else if (!faces.isRunning) folded[0] = null
    Box(
        Modifier.testTag(CODE_SLOT_TAG).layout { measurable, constraints ->
            val full = codeSize.roundToPx()
            val placeable = measurable.measure(Constraints.fixed(full, full))
            val height = lerp(full.toFloat(), HeroSize.toPx(), fold.value).roundToInt()
            layout(constraints.constrainWidth(full), height) { placeable.place((constraints.constrainWidth(full) - full) / 2, (height - full) / 2) }
        },
        contentAlignment = Alignment.Center,
    ) {
        folded[0]?.let { last ->
            Box(Modifier.graphicsLayer { alpha = heroAlpha.value }, contentAlignment = Alignment.Center) {
                if (Panel.face(last) == PanelFace.Paid) PaidHero(HeroSize) else EndedHero(last)
            }
        }
        QrCode(
            sale.code.uri,
            "Payment code for ${SaleText.amount(sale)}",
            Modifier.size(codeSize)
                .graphicsLayer {
                    val scale = lerp(1f, HeroSize / codeSize, fold.value)
                    scaleX = scale
                    scaleY = scale
                    alpha = if (faces.targetState.face == PanelFace.Code) 1f else codeAlpha.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .testTag(CODE_TAG)
                .then(if (live) Modifier else Modifier.clearAndSetSemantics {}),
            padding = 16.dp,
            radius = if (wide) 30.dp else 28.dp,
        )
    }
    faces.AnimatedContent(
        words,
        transitionSpec = {
            // The new words wait for the old to go, and are never clipped to the old words' size while it settles.
            fadeIn(m.fadeThrough.fraction) togetherWith fadeOut(m.fadeQuick.fraction) using
                SizeTransform(clip = false) { _, _ -> m.settle.spec(IntSize.VisibilityThreshold) }
        },
        contentAlignment = Alignment.TopCenter,
        contentKey = { it.face },
    ) { side ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val its = side.sale
            when (side.face) {
                PanelFace.Code -> StatusRow(its, now)
                PanelFace.Paid -> {
                    Text(
                        SaleText.status(its), style = display(28), textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                    )
                    Text("${SaleText.xmr(its)} received", fontSize = 15.sp, color = mk.muted)
                }
                PanelFace.Ended -> {
                    Text(
                        Panel.endedHeading(its), style = display(26), textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                    )
                    Text(Panel.endedLine(its), fontSize = 15.sp, color = mk.muted, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** The panel's code, for the tests that hold it to its size. */
const val CODE_TAG = "panel-code"

/** The slot the code folds in, whose height follows the fold. */
const val CODE_SLOT_TAG = "panel-code-slot"

/** The panel's card, for the tests that look at its shadow. */
const val CARD_TAG = "panel-card"

/** The status row under the live code. */
const val STATUS_TAG = "panel-status"

/** The paid tick's and the ended circle's size, which the code folds into. */
private val HeroSize = 88.dp

/**
 * Two buttons while the code is up, one once it's paid or ended: the row
 * fades through on [faces] (the old labels gone before the new arrive), its
 * height the same.
 */
@Composable
private fun Buttons(faces: Transition<FaceShown>, actions: PanelActions, words: Modifier) {
    val m = motion
    faces.AnimatedContent(
        words.fillMaxWidth().padding(top = 4.dp),
        transitionSpec = { (fadeIn(m.fadeThrough.fraction) togetherWith fadeOut(m.fadeQuick.fraction)).using(null) },
        contentKey = { it.face },
    ) { side ->
        val sale = side.sale
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (side.face) {
                PanelFace.Paid -> PrimaryButton("Done", actions.onLower, Modifier.weight(1f), height = 48.dp, size = 15)
                PanelFace.Code -> {
                    OutlineButton("Copy address", { actions.onCopy(sale) }, Modifier.weight(1f), size = 15)
                    PrimaryButton("Full screen", { actions.onFullScreen(sale) }, Modifier.weight(1f), height = 48.dp, size = 15)
                }
                PanelFace.Ended -> PrimaryButton("Open the shift", actions.onOpenShift, Modifier.weight(1f), height = 48.dp, size = 15)
            }
        }
    }
}

/**
 * The pill at the panel's top: drag it down to lower the panel, or tap it.
 * It measures 48 dp tall itself, the panel's top padding included, so a
 * finger just under the pill lowers the panel rather than reaching the
 * header below, which opens the shift.
 */
@Composable
private fun Grabber(modifier: Modifier, onDrag: (Float) -> Unit, onRelease: (Float) -> Unit, onTap: () -> Unit) = Box(
    modifier.size(80.dp, 48.dp)
        .draggable(rememberDraggableState(onDrag), Orientation.Vertical, onDragStopped = { velocity -> onRelease(velocity) })
        .clickable(role = Role.Button, onClickLabel = "Lower to the bubble", onClick = onTap)
        .semantics { contentDescription = "Lower to the bubble" },
    contentAlignment = Alignment.Center,
) { Box(Modifier.size(40.dp, 5.dp).clip(CircleShape).background(mk.edge)) }

/** "Sale #k4Rt" and when it was rung up, over "Front Counter · Open the shift ›": one target, which opens the shift. */
@Composable
private fun Header(sale: TillSale, where: String, onOpenShift: () -> Unit, modifier: Modifier) = Column(
    modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
        .clickable(onClickLabel = "Open the shift", onClick = onOpenShift)
        .padding(vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            SaleText.title(sale), fontSize = 16.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (sale.createdAt > 0) Text(SaleText.time(sale.createdAt), fontSize = 13.sp, color = mk.muted)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$where · ", fontSize = 13.sp, color = mk.muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Text("Open the shift", fontSize = 13.sp, color = mk.link, fontWeight = FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Icon(MkIcons.Chevron, contentDescription = null, tint = mk.link, modifier = Modifier.size(14.dp))
    }
}

/** "15 items" over the first few (or beside them, [oneLine]), on a card: opens the whole order. */
@Composable
private fun OrderLine(count: String, first: String, oneLine: Boolean, onOrder: () -> Unit, modifier: Modifier) = Row(
    modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(mk.card)
        .clickable(onClickLabel = "See the whole order", onClick = onOrder)
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    Icon(MkIcons.Bag, contentDescription = null, tint = mk.muted, modifier = Modifier.size(18.dp))
    val countText = @Composable { Text(count, fontSize = 14.sp, fontWeight = FontWeight.Bold, softWrap = false) }
    val firstText = @Composable { Text(first, fontSize = 13.sp, color = mk.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (oneLine) {
        // "15 items · Flat white, Oat latte, +3 more", the first items giving way first.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            countText()
            Text("·", fontSize = 13.sp, color = mk.muted)
            firstText()
        }
    } else {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            countText()
            firstText()
        }
    }
    Icon(MkIcons.Chevron, contentDescription = null, tint = mk.muted, modifier = Modifier.size(16.dp))
}

/**
 * The phase's ring, "Waiting for payment" and the time left at [now]. The
 * words crossfade and the row settles to their width (M12); only the words
 * announce: the countdown ticks silently, in tabular figures.
 */
@Composable
private fun StatusRow(sale: TillSale, now: LongState) = Row(
    Modifier.testTag(STATUS_TAG).animateContentSize(motion.settle.spec(IntSize.VisibilityThreshold)),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
) {
    val m = motion
    PhaseRing(sale.phase, 26.dp)
    AnimatedContent(
        SaleText.status(sale),
        transitionSpec = { (fadeIn(m.fade.fraction) togetherWith fadeOut(m.fadeQuick.fraction)).using(null) },
        label = "status words",
    ) { words ->
        Text(words, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (sale.phase.countsDown) {
        Countdown(now, sale.expiresAt, fontSize = 14.sp, color = mk.muted) { "· ${CodeText.left(sale, it)}" }
    }
}

/**
 * Paid as the detail pages show it: the paid tick on the paid ground. The
 * panel takes the theme's; the bubble, over another app, the design's.
 */
@Composable
private fun PaidHero(size: Dp, ground: Color = mk.paidGround, tint: Color = mk.paid) =
    Box(Modifier.size(size).clip(CircleShape).background(ground), contentAlignment = Alignment.Center) {
        Icon(MkIcons.Tick, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.53f))
    }

/** Where the code was, for a sale that stopped being live while the panel was up. */
@Composable
private fun EndedHero(sale: TillSale) {
    val (ground, tint, icon) = when {
        sale.refunded -> Triple(mk.chip, mk.ink, MkIcons.Refund)
        sale.phase == SalePhase.DoubleSpend -> Triple(mk.dangerGround, mk.danger, MkIcons.Warning)
        else -> Triple(mk.warnGround, mk.warn, MkIcons.Clock)
    }
    Box(Modifier.size(HeroSize).clip(CircleShape).background(ground), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(44.dp))
    }
}

/**
 * The panel's window on a phone: Square left as it is behind it (a tap
 * there lowers the panel), the lit bubble docked above it, or beside it
 * with the phone on its side (a tap there lowers it too). The whole
 * order, when it's up, fills the window: the card is its container
 * (`docs/motion.md` M5), growing from the card's bounds to the window's
 * with its corners going from 32 dp to square, the code and its docked
 * bubble fading out under it and the order fading in inside it; Back to
 * the code is the same, reversed. The order is laid out once at the
 * window's size, and the container is only a clip read in its draw.
 */
@Composable
fun PhonePanel(
    sale: TillSale,
    where: String,
    look: BubbleLook,
    page: PanelPage,
    actions: PanelActions,
    motion: PanelMotion,
    confirms: Boolean = true,
) {
    val m = app.monokular.ui.motion
    val pages = updateTransition(page, label = "page")
    val opened = pages.animateFloat({ m.surface.fraction }, label = "container") { if (it == PanelPage.Order) 1f else 0f }
    // The code's page only fades out, under the order's ground; coming back, the code is whole from the first frame
    // (§10 rule 6) and only its words fade through the order's, which matters under Still, where the ground snaps.
    val codeAlpha = pages.animateFloat({ m.fadeQuick.fraction }, label = "code") { if (it == PanelPage.Code) 1f else 0f }
    val codeWords = pages.animateFloat(
        { (if (targetState == PanelPage.Code) m.fadeThrough else m.fadeQuick).fraction }, label = "code words",
    ) { if (it == PanelPage.Code) 1f else 0f }
    val orderAlpha = pages.animateFloat(
        { (if (targetState == PanelPage.Order) m.fade else m.fadeQuick).fraction }, label = "order",
    ) { if (it == PanelPage.Order) 1f else 0f }
    val codeUp = pages.currentState == PanelPage.Code || pages.targetState == PanelPage.Code
    val stillLeaving = m.still && pages.targetState == PanelPage.Code
    val orderUp = pages.currentState == PanelPage.Order || pages.targetState == PanelPage.Order
    // With the order filling the window there is no docked bubble for the free one to hand over to.
    SideEffect { motion.undocked = !codeUp }
    Box(Modifier.fillMaxSize()) {
        if (codeUp) {
            Box(
                Modifier.graphicsLayer {
                    alpha = if (pages.targetState == PanelPage.Code) 1f else codeAlpha.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            ) {
                PhoneCode(sale, where, look, actions, motion, confirms, codeWords)
            }
        }
        if (orderUp) {
            CompositionLocalProvider(LocalContentColor provides mk.ink) {
                OrderContent(
                    sale, "Back to the code", actions.onBackToCode, actions.onBackToCode,
                    Modifier.fillMaxSize().testTag(ORDER_TAG).grows(motion, wide = false, order = true)
                        // Under Still nothing grows: the order's ground covers the code at once on the way in, and on the
                        // way back goes with its words, a crossfade off the code rather than an empty ground over it.
                        .container(motion, opened, if (stillLeaving) Color.Transparent else mk.ground)
                        .graphicsLayer { alpha = orderAlpha.value; compositingStrategy = CompositingStrategy.ModulateAlpha }
                        .then(if (stillLeaving) Modifier.background(mk.ground) else Modifier)
                        .safeDrawingPadding()
                        .semantics { paneTitle = "The order" },
                )
            }
        }
    }
}

/** The order filling a phone's panel window. */
const val ORDER_TAG = "panel-order"

/** The phone panel card's corners, which the order's container squares off as it fills the window. */
private val CardCorner = 32.dp

/**
 * The phone's order inside its container: the ground and the content
 * clipped to a rounded rectangle from the code's card ([PanelMotion.card])
 * at [opened] 0 to the whole window at 1, its corners from [CardCorner] to
 * square. Read in the draw only; the one path is made once per size and
 * rewound each frame, so a frame allocates nothing.
 */
private fun Modifier.container(motion: PanelMotion, opened: State<Float>, ground: Color): Modifier = drawWithCache {
    val path = Path()
    val corner = CardCorner.toPx()
    onDrawWithContent {
        val p = opened.value
        val from = motion.card
        val window = motion.window
        if (from == null || window == null || p >= 1f) {
            drawRect(ground)
            drawContent()
            return@onDrawWithContent
        }
        val radius = lerp(corner, 0f, p)
        path.rewind()
        path.asAndroidPath().addRoundRect(
            lerp(from.left - window.left, 0f, p),
            lerp(from.top - window.top, 0f, p),
            lerp(from.right - window.left, size.width, p),
            lerp(from.bottom - window.top, size.height, p),
            radius, radius, android.graphics.Path.Direction.CW,
        )
        clipPath(path) {
            drawRect(ground)
            this@onDrawWithContent.drawContent()
        }
    }
}

@Composable
private fun PhoneCode(
    sale: TillSale,
    where: String,
    look: BubbleLook,
    actions: PanelActions,
    motion: PanelMotion,
    confirms: Boolean,
    words: State<Float>,
) {
    val lower by rememberUpdatedState(actions.onLower)
    val bubble = @Composable {
        Box(
            Modifier
                .onGloballyPositioned { motion.dock = it.positionOnScreen() + Offset(it.size.width / 2f, it.size.height / 2f) }
                // The docked bubble starts where the free one was and rides the raise to its slot, and back.
                .offset {
                    val dock = motion.dock
                    val from = motion.bubbleAt
                    if (dock == null || from == null) {
                        IntOffset.Zero
                    } else {
                        val away = 1f - motion.raise.value
                        IntOffset(((from.x - dock.x) * away).roundToInt(), ((from.y - dock.y) * away).roundToInt())
                    }
                }
                // Over the card, which grows out of it and collapses into it.
                .zIndex(1f)
                // Not drawn until it knows where the free bubble is, so its first frame isn't a second bubble elsewhere.
                .graphicsLayer { alpha = if (motion.dock == null) 0f else 1f }
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Lower to the bubble", onClick = actions.onLower)
                .semantics { contentDescription = "Lower to the bubble" },
        ) { BubbleFace(look, pulse = false, phase = sale.phase, confirms = confirms) }
    }
    // Taps on the panel itself stay there.
    val panel = @Composable { sideBySide: Boolean ->
        Box(Modifier.grows(motion, wide = false).pointerInput(Unit) { detectTapGestures { } }) {
            SalePanel(sale, where, wide = false, actions, motion.raise, sideBySide, words)
        }
    }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { lower() } }) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
            if (maxWidth > maxHeight) {
                // On its side the phone has no height to spare: the bubble docks beside the panel, not above it.
                Row(Modifier.fillMaxSize().padding(top = 4.dp)) {
                    Box(Modifier.weight(1f)) { panel(true) }
                    bubble()
                }
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.End) {
                    Spacer(Modifier.height(4.dp))
                    bubble()
                    panel(false)
                }
            }
        }
    }
}

/** The floating panel's window on a tablet: the panel, or the whole order in its place. */
@Composable
fun FloatingPanel(sale: TillSale, where: String, page: PanelPage, actions: PanelActions, motion: PanelMotion) = Box(
    Modifier.padding(Panel.FLOATING_SHADOW_DP.dp).grows(motion, wide = true),
) {
    // The order takes the card's place (M5) and the card's height settles to the new one. The order's words fade
    // through the panel's; back to the code, the code is under the order at once, opaque (§10 rule 6), while the
    // order fades off it and the panel's words fade through after.
    val m = app.monokular.ui.motion
    AnimatedContent(
        page,
        transitionSpec = {
            val size = SizeTransform(clip = false) { _, _ -> m.surface.spec(IntSize.VisibilityThreshold) }
            if (targetState == PanelPage.Code) {
                (EnterTransition.None togetherWith fadeOut(m.fadeQuick.fraction)).apply { targetContentZIndex = -1f } using size
            } else {
                fadeIn(m.fadeThrough.fraction) togetherWith fadeOut(m.fadeQuick.fraction) using size
            }
        },
        label = "page",
    ) { shown ->
        if (shown == PanelPage.Order) {
            CompositionLocalProvider(LocalContentColor provides mk.ink) {
                OrderContent(
                    sale, "Back to the code", actions.onBackToCode, actions.onBackToCode,
                    Modifier.width(360.dp).heightIn(max = 640.dp)
                        .card(motion.raise).background(mk.ground)
                        .semantics { paneTitle = "The order" },
                )
            }
        } else {
            val words = transition.animateFloat(
                { (if (targetState == EnterExitState.Visible) m.fadeThrough else m.fadeQuick).fraction }, label = "words",
            ) { if (it == EnterExitState.Visible) 1f else 0f }
            SalePanel(sale, where, wide = true, actions, motion.raise, words = words)
        }
    }
}

/**
 * The panel growing out of the bubble and collapsing back into it
 * (`docs/motion.md` M1): scaled from 0.3 and faded in over the first third
 * of [PanelMotion.raise], about the docked bubble on a phone and the
 * card's corner nearest the bubble on a tablet. Everything is read in the
 * layer, so a frame of the grow recomposes and relays out nothing.
 */
private fun Modifier.grows(motion: PanelMotion, wide: Boolean, order: Boolean = false): Modifier = this
    .onGloballyPositioned {
        val placed = Rect(it.positionOnScreen(), it.size.toSize())
        if (order) motion.window = placed else motion.card = placed
    }
    .graphicsLayer {
        val raise = motion.raise.value
        val card = if (order) motion.window else motion.card
        val from = if (wide) motion.bubbleAt else motion.dock ?: motion.bubbleAt
        if (card != null && from != null) transformOrigin = Panel.origin(from, card, corner = wide)
        scaleX = 0.3f + 0.7f * raise
        scaleY = scaleX
        alpha = Panel.shown(raise)
        // Faded per draw, not through a window-sized offscreen buffer: the fade is a third of the grow.
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }

/** The panel card's shape and the height its shadow is cast from at rest. */
private val CardShape = RoundedCornerShape(32.dp)
private val CardElevation = 16.dp

/**
 * The panel's card: clipped to its corners, its shadow cast from a height
 * and in a darkness that follow [raise] as the card's fade does
 * ([Panel.shown]). A parent's alpha drawn per draw (the grow's
 * `ModulateAlpha`) never reaches a child's shadow, so without this the
 * shadow stayed whole while the card faded out of it: a grey box left over
 * Square after the panel was gone (decision 134). Read in the layer, so a
 * frame recomposes nothing; the colour is a value, so it allocates nothing.
 */
private fun Modifier.card(raise: Presence): Modifier = graphicsLayer {
    val shown = Panel.shown(raise.value)
    shadowElevation = CardElevation.toPx() * shown
    ambientShadowColor = DefaultShadowColor.copy(alpha = shown)
    spotShadowColor = ambientShadowColor
    shape = CardShape
    clip = true
}
