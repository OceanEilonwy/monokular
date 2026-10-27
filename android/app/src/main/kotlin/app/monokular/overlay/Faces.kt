package app.monokular.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monokular.BubbleLook
import app.monokular.SaleText
import app.monokular.core.SalePhase
import app.monokular.sales.Standing
import app.monokular.sales.TillSale
import app.monokular.sales.live
import app.monokular.sales.standing
import app.monokular.ui.CodeText
import app.monokular.ui.Fixed
import app.monokular.ui.Logo
import app.monokular.ui.MkIcons
import app.monokular.ui.OrderContent
import app.monokular.ui.OutlineButton
import app.monokular.ui.PaidTick
import app.monokular.ui.PhaseRing
import app.monokular.ui.PrimaryButton
import app.monokular.ui.QrCode
import app.monokular.ui.display
import app.monokular.ui.mk
import app.monokular.ui.reducedMotion
import app.monokular.ui.secondsLeft
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Which of the panel's two faces is up: the code, or the whole order (the design's PanelE). */
sealed interface PanelPage {
    data object Code : PanelPage
    data object Order : PanelPage
}

/** The panel's words and its grabber's rule: pure, so they're tested on the JVM. */
object Panel {
    /** How fast a downward fling on the grabber has to be to lower the panel, in dp a second. */
    const val FLING_DP_PER_S = 800f

    /**
     * Whether letting go of the grabber lowers the panel: dragged down past
     * a quarter of its [height], or flung down at [fling] or faster
     * ([velocity] in the same units a second); else it springs back.
     */
    fun lowers(drag: Float, height: Float, velocity: Float, fling: Float): Boolean =
        velocity >= fling || (height > 0f && drag > height / 4f)

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

/** What the panel can do: each lowers it or opens something, the overlay decides. */
class PanelActions(
    val onLower: () -> Unit,
    val onOpenShift: () -> Unit,
    val onOrder: () -> Unit,
    val onBackToCode: () -> Unit,
    val onCopy: () -> Unit,
    val onCopyXmr: () -> Unit,
    val onFullScreen: () -> Unit,
)

/**
 * The bubble (the design's Bubble part), in each [look]: faded and grey
 * with nothing to do, white with the [phase]'s ring (and a pulse) for a
 * sale, green with a tick once it's paid. Its colours are the design's
 * whatever the theme: it sits over another app.
 */
@Composable
fun BubbleFace(look: BubbleLook, pulse: Boolean = true, phase: SalePhase = SalePhase.Waiting) = Box(
    Modifier.padding(12.dp).clearAndSetSemantics {},
    contentAlignment = Alignment.Center,
) { Circle(look, pulse, phase) }

@Composable
private fun Circle(look: BubbleLook, pulse: Boolean, phase: SalePhase) {
    when (look) {
        BubbleLook.Idle -> Box(
            Modifier.alpha(0.55f).shadow(4.dp, CircleShape).size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) { Logo(38.dp, ring = Color(0xFF565656), grey = true) }
        BubbleLook.Lit -> {
            val ring = if (pulse && !reducedMotion()) {
                rememberInfiniteTransition(label = "pulse").animateFloat(
                    0f, 1f, infiniteRepeatable(tween(1600, easing = LinearOutSlowInEasing), RepeatMode.Restart), label = "ring",
                )
            } else {
                null
            }
            Box(
                Modifier.size(56.dp).drawBehind {
                    val p = ring?.value ?: return@drawBehind
                    drawCircle(Fixed.Accent.copy(alpha = 0.55f * (1f - p)), radius = size.minDimension / 2 + 16.dp.toPx() * p)
                }.shadow(6.dp, CircleShape).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                PhaseRing(phase, 40.dp, track = Fixed.Track, fill = Fixed.Accent)
                Box(Modifier.size(12.dp).clip(CircleShape).background(Fixed.Ink))
            }
        }
        BubbleLook.Paid -> Box(Modifier.shadow(6.dp, CircleShape)) { PaidTick(56.dp) }
    }
}

/**
 * The code over another app (the design's Panel over Square on a phone,
 * and the floating panel on a tablet): a grabber to lower it, the header
 * that opens the shift, the amount, what was bought, the code at a size
 * that never changes, its status, Copy address and Full screen. Paid, it
 * says so and lowers itself; ended unpaid, it sends the cashier to the
 * shift.
 */
@Composable
fun SalePanel(sale: TillSale, where: String, wide: Boolean, actions: PanelActions) {
    if (sale.phase == SalePhase.Paid) LaunchedEffect(sale.orderId) { delay(5_000); actions.onLower() }
    // No Surface gives the overlay's window a text colour: the panel sets its own.
    CompositionLocalProvider(LocalContentColor provides mk.ink) { PanelCard(sale, where, wide, actions) }
}

@Composable
private fun PanelCard(sale: TillSale, where: String, wide: Boolean, actions: PanelActions) {
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    var height by remember { mutableFloatStateOf(0f) }
    val fling = with(LocalDensity.current) { Panel.FLING_DP_PER_S.dp.toPx() }
    val still = reducedMotion()
    Column(
        Modifier.then(if (wide) Modifier.width(360.dp) else Modifier.fillMaxWidth())
            .onSizeChanged { height = it.height.toFloat() }
            // On a phone the panel follows the finger down; the tablet's floating panel fades instead.
            .then(
                if (wide) {
                    Modifier.graphicsLayer { alpha = 1f - (drag.value / height.coerceAtLeast(1f)).coerceIn(0f, 0.8f) }
                } else {
                    Modifier.offset { IntOffset(0, drag.value.roundToInt()) }
                },
            )
            .shadow(16.dp, RoundedCornerShape(32.dp))
            .clip(RoundedCornerShape(32.dp))
            .background(mk.ground)
            .semantics { paneTitle = "Monokular payment code" }
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Grabber(
            onDrag = { delta -> scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) } },
            onRelease = { velocity ->
                scope.launch {
                    if (Panel.lowers(drag.value, height, velocity, fling)) {
                        actions.onLower()
                    } else {
                        drag.animateTo(0f, if (still) snap() else spring())
                    }
                }
            },
            onTap = actions.onLower,
        )
        Column(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(sale, where, actions.onOpenShift)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(SaleText.amount(sale), style = display(44, -0.8))
                Text(
                    SaleText.xmr(sale), fontSize = 15.sp, color = mk.muted,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "Copy the XMR amount", onClick = actions.onCopyXmr)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            val receipt = sale.receipt
            if (receipt != null && receipt.lineItems.isNotEmpty()) OrderLine(SaleText.items(receipt), SaleText.firstItems(receipt), actions.onOrder)
            when (sale.standing) {
                is Standing.Live -> {
                    // The same size whatever else the panel shows: the cashier's eye and the customer's camera find it where it was.
                    QrCode(
                        sale.code.uri,
                        "Payment code for ${SaleText.amount(sale)}",
                        Modifier.size(if (wide) 280.dp else 264.dp),
                        padding = 16.dp,
                        radius = if (wide) 30.dp else 28.dp,
                    )
                    StatusRow(sale)
                }
                is Standing.Paid -> {
                    PaidTick(88.dp)
                    Text(
                        SaleText.status(sale), style = display(28), textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                    )
                    Text("${SaleText.xmr(sale)} received", fontSize = 15.sp, color = mk.muted)
                }
                else -> Ended(sale)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    sale.phase == SalePhase.Paid -> PrimaryButton("Done", actions.onLower, Modifier.weight(1f), height = 48.dp, size = 15)
                    sale.live -> {
                        OutlineButton("Copy address", actions.onCopy, Modifier.weight(1f), size = 15)
                        PrimaryButton("Full screen", actions.onFullScreen, Modifier.weight(1f), height = 48.dp, size = 15)
                    }
                    else -> PrimaryButton("Open the shift", actions.onOpenShift, Modifier.weight(1f), height = 48.dp, size = 15)
                }
            }
        }
    }
}

/**
 * The pill at the panel's top: drag it down to lower the panel, or tap it.
 * Drawn 22 dp tall; Compose widens its touch to 48 dp.
 */
@Composable
private fun Grabber(onDrag: (Float) -> Unit, onRelease: (Float) -> Unit, onTap: () -> Unit) = Box(
    Modifier.size(80.dp, 22.dp)
        .draggable(rememberDraggableState(onDrag), Orientation.Vertical, onDragStopped = { velocity -> onRelease(velocity) })
        .clickable(role = Role.Button, onClickLabel = "Lower to the bubble", onClick = onTap)
        .semantics { contentDescription = "Lower to the bubble" },
    contentAlignment = Alignment.Center,
) { Box(Modifier.size(40.dp, 5.dp).clip(CircleShape).background(mk.edge)) }

/** "Sale #k4Rt" and when it was rung up, over "Front Counter · Open the shift ›": one target, which opens the shift. */
@Composable
private fun Header(sale: TillSale, where: String, onOpenShift: () -> Unit) = Column(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
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

/** "15 items" over the first few, on a card: opens the whole order. */
@Composable
private fun OrderLine(count: String, first: String, onOrder: () -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(mk.card)
        .clickable(onClickLabel = "See the whole order", onClick = onOrder)
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    Icon(MkIcons.Bag, contentDescription = null, tint = mk.muted, modifier = Modifier.size(18.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(count, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(first, fontSize = 13.sp, color = mk.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Icon(MkIcons.Chevron, contentDescription = null, tint = mk.muted, modifier = Modifier.size(16.dp))
}

/** The phase's ring, "Waiting for payment" and the time left. Only the words announce: the countdown ticks silently. */
@Composable
private fun StatusRow(sale: TillSale) = Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
) {
    PhaseRing(sale.phase, 26.dp)
    Text(
        SaleText.status(sale), fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    CodeText.left(sale, secondsLeft(sale.expiresAt))?.let { Text("· $it", fontSize = 14.sp, color = mk.muted) }
}

/** Where the code was, for a sale that stopped being live while the panel was up. */
@Composable
private fun Ended(sale: TillSale) {
    val (ground, tint, icon) = when {
        sale.refunded -> Triple(mk.chip, mk.ink, MkIcons.Refund)
        sale.phase == SalePhase.DoubleSpend -> Triple(mk.dangerGround, mk.danger, MkIcons.Warning)
        else -> Triple(mk.warnGround, mk.warn, MkIcons.Clock)
    }
    Box(Modifier.size(88.dp).clip(CircleShape).background(ground), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(44.dp))
    }
    Text(
        Panel.endedHeading(sale), style = display(26), textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
    )
    Text(Panel.endedLine(sale), fontSize = 15.sp, color = mk.muted, textAlign = TextAlign.Center)
}

/**
 * The panel's window on a phone: Square left as it is behind it (a tap
 * there lowers the panel), the lit bubble docked above it (a tap there
 * lowers it too). The whole order, when it's up, fills the window.
 */
@Composable
fun PhonePanel(sale: TillSale, where: String, look: BubbleLook, page: PanelPage, actions: PanelActions) {
    if (page == PanelPage.Order) {
        CompositionLocalProvider(LocalContentColor provides mk.ink) {
            OrderContent(
                sale, "Back to the code", actions.onBackToCode, actions.onBackToCode,
                Modifier.fillMaxSize().background(mk.ground).safeDrawingPadding().semantics { paneTitle = "The order" },
            )
        }
        return
    }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { actions.onLower() } }) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp), horizontalAlignment = Alignment.End) {
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Lower to the bubble", onClick = actions.onLower)
                    .semantics { contentDescription = "Lower to the bubble" },
            ) { BubbleFace(look, phase = sale.phase) }
            // Taps on the panel itself stay there.
            Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) {
                SalePanel(sale, where, wide = false, actions)
            }
        }
    }
}

/** The floating panel's window on a tablet: the panel, or the whole order in its place. */
@Composable
fun FloatingPanel(sale: TillSale, where: String, page: PanelPage, actions: PanelActions) = Box(Modifier.padding(24.dp)) {
    if (page == PanelPage.Order) {
        CompositionLocalProvider(LocalContentColor provides mk.ink) {
            OrderContent(
                sale, "Back to the code", actions.onBackToCode, actions.onBackToCode,
                Modifier.width(360.dp).heightIn(max = 640.dp)
                    .shadow(16.dp, RoundedCornerShape(32.dp)).clip(RoundedCornerShape(32.dp)).background(mk.ground)
                    .semantics { paneTitle = "The order" },
            )
        }
    } else {
        SalePanel(sale, where, wide = true, actions)
    }
}
