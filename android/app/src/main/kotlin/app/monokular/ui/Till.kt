package app.monokular.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import app.monokular.CloseChoice
import app.monokular.Closing
import app.monokular.Ground
import app.monokular.PosText
import app.monokular.SaleHeader
import app.monokular.Move
import app.monokular.SaleText
import app.monokular.core.PointOfSale
import app.monokular.StateIcon
import app.monokular.Tone
import app.monokular.receipt.Receipt
import app.monokular.sales.TillSale
import app.monokular.sales.live
import app.monokular.LocalFace
import app.monokular.faceSurface
import app.monokular.wholeOn
import app.monokular.GroundLook
import app.monokular.GroundTrack
import app.monokular.sharedGround
import app.monokular.words
import app.monokular.rememberGroundTrack
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * The till's point of sale, for every word on its screens that names it
 * ([PosText]): provided where the app's and the bubble's screens are set, null
 * for a till that runs without one.
 */
val LocalPointOfSale = compositionLocalOf<PointOfSale?> { null }

/** The point of sale that names [sale]'s words: the one that took it, else the till's ([PosText.of]). */
@Composable
fun posOf(sale: TillSale): PointOfSale? = PosText.of(sale, LocalPointOfSale.current)

/** Seconds from [now] until [expiresAt], Unix seconds both; 0 once it's passed. */
fun secondsLeft(expiresAt: Long, now: Long): Long = (expiresAt - now).coerceAtLeast(0)

/**
 * The time now, Unix seconds, ticking on each second's boundary: one clock
 * for a whole screen (`docs/motion.md` amendment 10), made at its root and
 * handed down, and read only by the [Countdown]s that show it, so a tick
 * recomposes those texts and nothing above them. A [LongState], so reading
 * it doesn't box.
 */
@Composable
fun rememberNowSeconds(): LongState {
    val now = remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(now) {
        while (true) {
            val ms = System.currentTimeMillis()
            now.longValue = ms / 1000
            delay(1000 - ms % 1000)
        }
    }
    return now
}

/**
 * A line with a countdown in it: [words] for the seconds left until
 * [expiresAt] at [now], in [tabular] figures so the digits tick without
 * the words moving. The clock is read here, so a tick recomposes this text
 * alone.
 */
@Composable
fun Countdown(
    now: LongState,
    expiresAt: Long,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    words: (secondsLeft: Long) -> String,
) = Text(
    words(secondsLeft(expiresAt, now.longValue)), modifier, color = color, fontSize = fontSize,
    overflow = overflow, maxLines = maxLines, style = LocalTextStyle.current.merge(tabular),
)

/** What the till's screens need to know about the shift. */
data class TillStatus(
    /** The store's name as last read; null until the first read answers. */
    val store: String?,
    val location: String?,
    val running: Boolean,
    val live: Boolean,
    val disconnected: Boolean,
    val problem: String?,
    val signInAgain: Boolean,
    /** When this shift began, Unix seconds, if it's known. */
    val startedAt: Long?,
)

/**
 * What the till's screens can do: events up to the view model. Made once
 * per window mode and never changed, so a screen handed it can skip.
 */
@Immutable
class TillActions(
    val onSettings: () -> Unit,
    /** A sale from the shift: its code, or its page. */
    val onOpen: (String) -> Unit,
    /** Back to the shift: the sale stays live, on the bubble and in the shift. */
    val onBack: (String) -> Unit,
    val onEnlarge: (String) -> Unit,
    val onShowOrder: (String) -> Unit,
    val onClose: (String, CloseChoice) -> Unit,
    val onRefund: (String) -> Unit,
    val onReopen: (String) -> Unit,
    val onCopy: (TillSale) -> Unit,
    val onCopyXmr: (TillSale) -> Unit,
    /**
     * Back to the point-of-sale app on this phone: Square's only, null for Clover, whose
     * point of sale is a device of its own (decision 135), and when Square is already beside
     * Monokular (split screen).
     */
    val onBackToPos: (() -> Unit)?,
    /** The refund sheet's "I've refunded it" (decision 130): ask the point of sale now. */
    val onRefundDone: (String) -> Unit,
    /** The refund sheet went away: "Not yet", Back or the scrim. */
    val onRefundSheetDismissed: () -> Unit,
    val onSignIn: () -> Unit,
    val onForget: () -> Unit,
    val onStartShift: () -> Unit,
    /** The point of sale the till's source watches ([RunnerState.source]), for the close sheet's "No" ([Closing.refundsThere]). */
    val source: PointOfSale = PointOfSale.Square,
)

/**
 * One sale (decision 70): its code for the customer while it's live
 * (SaleA), else the page its standing calls for (row 2b), so a sale that's
 * paid or ends while on screen turns into its page in place. [now] is
 * the screen's clock, read only by its countdowns.
 */
@Composable
fun SaleScreen(
    sale: TillSale,
    location: String?,
    refunding: Boolean,
    refundProblem: String?,
    refundSheet: RefundSheet?,
    actions: TillActions,
    now: LongState = rememberNowSeconds(),
) {
    // The sale is its row opened: tapped on the shift, the row's card grows into this ground (M7), which the row draws
    // since only it knows its own corners; the sale's words fade in over it at their own size.
    Box(Modifier.fillMaxSize()) {
        val ground = rememberGroundTrack("sale/${sale.orderId}")
        Box(Modifier.matchParentSize().sharedGround("sale/${sale.orderId}", Move.RowGrow, GroundLook(mk.ground, 0.dp), ground, fadesIn = Move.Rise))
        // Paid or ended on screen, the live sale turns into its page in place (M10): the code card closes into the
        // page's hero circle, and the live sale's words fade out before the page's fade in, never two receipts at once
        // (decision 131); the way back the same reversed ("Show the code again"). Per draw, never an offscreen layer.
        val m = motion
        AnimatedContent(
            targetState = sale.live,
            // Over the row's ground as it opens, clipped to it; the words inside fade one by one around the code, which
            // never fades (decision 148): opening, it is drawn whole wherever the ground has reached.
            modifier = Modifier.words(over = Move.RowGrow, on = ground, fades = false),
            transitionSpec = { (EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished).using(null) },
            label = "sale face",
        ) { live ->
            val seen = transition.animateFloat(
                { if (targetState == EnterExitState.Visible) m.fadeThrough.fraction else m.fadeQuick.fraction }, label = "sale face seen",
            ) { if (it == EnterExitState.Visible) 1f else 0f }
            CompositionLocalProvider(LocalFace provides this) {
                Box(
                    Modifier.graphicsLayer {
                        alpha = seen.value
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                ) {
                    if (live) {
                        LiveSale(sale, location, now, actions, ground)
                    } else {
                        SaleDetail(sale, location, refunding, refundProblem, refundSheet, actions, Modifier.words(through = SaleThrough, lift = SaleLift, on = ground))
                    }
                }
            }
        }
    }
}

/** The moves a sale's words fade through: its row opening, its code or order growing, the sale rising by itself. */
private val SaleThrough = setOf(Move.RowGrow, Move.CodeGrow, Move.OrderGrow, Move.Rise)

/** The moves whose ground passes over a sale's words as they arrive: they are drawn over it (decision 147). */
private val SaleLift = setOf(Move.CodeGrow, Move.OrderGrow)

/** The order card's: not its own grow, whose ground it is (its lines fade on it). */
private val OrderCardThrough = setOf(Move.RowGrow, Move.CodeGrow, Move.Rise)
private val OrderCardLift = setOf(Move.CodeGrow)

/** A circle's corners, as a share of its side. */
private const val Circle = 0.5f

/** A live sale full screen, for the customer to scan (SaleA); split in two on a wide window (decision 45). */
@Composable
private fun LiveSale(sale: TillSale, location: String?, now: LongState, actions: TillActions, ground: GroundTrack) {
    // The code card's white: words it still passes over as it shrinks back into the card wait for it to pass (decision 147).
    val card = rememberGroundTrack(codeGround(sale.orderId))
    val words = Modifier.words(through = SaleThrough, lift = SaleLift, on = ground, clear = card)
    val orderWords = Modifier.words(through = OrderCardThrough, lift = OrderCardLift, on = ground, clear = card)
    var asking by rememberSaveable(sale.orderId) { mutableStateOf(false) }
    val header = SaleHeader.of(sale, LocalPointOfSale.current, location)
    // A receipt reaches the till about a second after its sale: its card fades in then, rather than popping (N3).
    val late = remember(sale.orderId) { sale.receipt == null }
    KeepScreenOn()
    // Its ground is the screen's (SaleScreen), so the page it turns into fades over one ground, not a second.
    Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = mk.ink) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 840.dp
            Column(Modifier.fillMaxSize()) {
                Box(words) {
                    SaleBar(
                        sale, header.place, actions,
                        onClose = { asking = true }.takeIf { Closing.choices(sale).isNotEmpty() },
                    )
                }
                if (wide) {
                    Row(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Code(sale, min(this@BoxWithConstraints.maxHeight - 120.dp, 440.dp), card, ground) { actions.onEnlarge(sale.orderId) }
                        Column(
                            // Centred beside the code (which is not in it), it settles to the late card's height rather than jumping.
                            Modifier.widthIn(max = 420.dp).animateContentSize(motion.settle.spec(IntSize.VisibilityThreshold))
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Box(words) { Amount(sale, actions.onCopyXmr, start = true) }
                            Box(words) { StatusPill(sale, now) }
                            sale.receipt?.let { Box(orderWords) { Late(late) { OrderCard(it, header.pos, withTime = true, onOpen = { actions.onShowOrder(sale.orderId) }, shared = "order/${sale.orderId}") } } }
                            Box(words) { Buttons(sale, header, actions) }
                        }
                    }
                } else {
                    // As wide as the detail pages at most, so on a 600-839 dp window
                    // the pill, order card and buttons don't stretch edge to edge.
                    Column(
                        Modifier.weight(1f).align(Alignment.CenterHorizontally).widthIn(max = PhoneMaxWidth).fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(words) { Amount(sale, actions.onCopyXmr) }
                        Code(sale, min(this@BoxWithConstraints.maxWidth - 40.dp, CodeSide), card, ground) { actions.onEnlarge(sale.orderId) }
                        Box(words) { StatusPill(sale, now) }
                        sale.receipt?.let { Box(orderWords) { Late(late) { OrderCard(it, header.pos, withTime = true, onOpen = { actions.onShowOrder(sale.orderId) }, shared = "order/${sale.orderId}") } } }
                    }
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).widthIn(max = PhoneMaxWidth)
                            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp).then(words),
                    ) { Buttons(sale, header, actions) }
                }
            }
        }
    }
    if (asking) {
        CloseSheet(sale, actions.source, now, onChoice = { actions.onClose(sale.orderId, it) }, onDismiss = { asking = false })
    }
}

/**
 * [content], faded in with [Motion.fade] as it first shows when it is
 * [late] (it wasn't there when the screen came up), at once when it was:
 * the alpha read per draw, so the fade recomposes nothing and makes no
 * layer.
 */
@Composable
private fun Late(late: Boolean, content: @Composable () -> Unit) {
    val m = motion
    val seen = remember { Animatable(if (late) 0f else 1f) }
    LaunchedEffect(seen) { seen.animateTo(1f, m.fade.fraction) }
    Box(
        Modifier.graphicsLayer {
            alpha = seen.value
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    ) { content() }
}

/** A card's corners: the order card, the order page's list. */
internal val CardCorner = 20.dp
internal val CardShape = RoundedCornerShape(CardCorner)

/** The sale's code on a phone: the design's 264 dp, whatever the order beside it. */
private val CodeSide = 264.dp

/** The code card's corners as a share of its side: what the hero's circle opens from, and closes back into. */
internal val CodeCornerShare: Float get() = CodeCorner / CodeSide

/** The phone layout's widest, the detail pages' (SaleDetail). */
private val PhoneMaxWidth = 560.dp

/**
 * A sale's top bar: Back to the shift, the sale and [subtitle], Settings,
 * and on a live sale the tinted X that asks whether the customer is paying.
 */
@Composable
fun SaleBar(sale: TillSale, subtitle: String, actions: TillActions, onClose: (() -> Unit)? = null) = Row(
    Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
) {
    RoundIconButton(MkIcons.Back, "Back to the shift", { actions.onBack(sale.orderId) }, iconSize = 22.dp)
    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
        Text(SaleText.title(sale), fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // The till's own words for where the sale is from replace Square's once its receipt is in: they fade through.
        val through = motion.fadeThrough.fraction
        val quick = motion.fadeQuick.fraction
        AnimatedContent(subtitle, transitionSpec = { (fadeIn(through) togetherWith fadeOut(quick)).using(null) }, label = "sale from") { words ->
            Text(words, fontSize = 13.sp, color = mk.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    CircleButton(MkIcons.Settings, "Settings", actions.onSettings, ground = mk.chip, tint = mk.ink, iconSize = 20.dp)
    if (onClose != null) {
        Spacer(Modifier.width(4.dp))
        CircleButton(MkIcons.Close, "Close sale", onClose, ground = mk.dangerGround, tint = mk.danger, iconSize = 16.dp)
    }
}

/** The amount, and the XMR to pay under it, which copies itself when tapped ([onCopyXmr]; acceptance D7). */
@Composable
private fun Amount(sale: TillSale, onCopyXmr: (TillSale) -> Unit, start: Boolean = false) = Column(
    horizontalAlignment = if (start) Alignment.Start else Alignment.CenterHorizontally,
) {
    val align = if (start) TextAlign.Start else TextAlign.Center
    Text(SaleText.amount(sale), style = display(52, -1.0), textAlign = align)
    Text(
        SaleText.xmr(sale), fontSize = 17.sp, color = mk.muted, textAlign = align,
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Copy the XMR amount") { onCopyXmr(sale) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** The code, which enlarges when tapped (CodeB), with the expand badge on its corner. */
@Composable
private fun Code(sale: TillSale, side: Dp, card: GroundTrack, ground: GroundTrack, onEnlarge: () -> Unit) = Box(
    // Opening from its row or shrinking back into it, the code and its white are drawn whole or not at all: only on
    // the frames the row's ground holds all of the card (decision 177). Paid or ended, the card closes into the page's
    // hero (M10).
    Modifier.wholeOn(ground, Move.RowGrow).faceSurface(heroKey(sale.orderId), CodeCorner, other = Circle, code = true)
        .clip(RoundedCornerShape(CodeCorner)).clickable(onClickLabel = "Enlarge the payment code", role = Role.Button, onClick = onEnlarge),
) {
    QrCode(
        sale.code.uri,
        "Payment code for ${SaleText.amount(sale)}" + if (sale.code.hasReceipt) ", with the receipt in it" else "",
        Modifier.width(side),
        padding = 18.dp,
        radius = CodeCorner,
        shared = sale.orderId,
        ground = card,
    )
    Box(
        Modifier.align(Alignment.BottomEnd).padding(10.dp).size(32.dp).clip(CircleShape).background(Fixed.Ink),
        contentAlignment = Alignment.Center,
    ) { Icon(MkIcons.Expand, contentDescription = null, tint = Fixed.White, modifier = Modifier.size(16.dp)) }
}

/**
 * "Waiting for payment" with the phase ring, on its pill; the words, not
 * the countdown, are announced. New words crossfade over the old and the
 * pill settles to their width (M12): a two-text subtree, never anything
 * with the code in it.
 */
@Composable
internal fun StatusPill(sale: TillSale, now: LongState) = Row(
    Modifier.clip(RoundedCornerShape(28.dp)).background(mk.card)
        .animateContentSize(motion.settle.spec(IntSize.VisibilityThreshold))
        .padding(start = 12.dp, end = 18.dp, top = 10.dp, bottom = 10.dp)
        .semantics(mergeDescendants = true) {},
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    PhaseRing(sale.phase, 30.dp)
    Column {
        val through = motion.fadeThrough.fraction
        val fadeQuick = motion.fadeQuick.fraction
        AnimatedContent(
            targetState = SaleText.status(sale, posOf(sale)),
            // The pill's own size animates; the words' box takes the wider of the two while they change. The new words
            // fade through, in once the old are out, never over them ("Paid" over "Waiting for payment": decision 168).
            transitionSpec = { (fadeIn(through) togetherWith fadeOut(fadeQuick)).using(null) },
            label = "status words",
        ) { words ->
            Text(
                words, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Countdown(now, sale.expiresAt, fontSize = 13.sp, color = mk.muted) { SaleText.hint(sale, it) }
    }
}

@Composable
private fun Buttons(sale: TillSale, header: SaleHeader, actions: TillActions) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    OutlineButton("Copy address", { actions.onCopy(sale) }, Modifier.weight(1f), height = 56.dp)
    actions.onBackToPos?.takeIf { header.backToPos }?.let { back ->
        PrimaryButton(PosText.backTo(header.pos), back, Modifier.weight(1f), height = 56.dp, size = 16)
    }
}

/**
 * What was bought, from this till's own receipt (decision 63): each line
 * and its price, the tax and the total. On the sale ([onOpen] set) a long
 * order shows three lines and "+N more", and the card opens the whole order
 * (decision 66); a detail page lists every line. [pos] names the receipt
 * ("Clover order …"). On the sale, [shared] is the key it grows into the
 * order page by (M7).
 */
@Composable
fun OrderCard(receipt: Receipt, pos: PointOfSale?, withTime: Boolean, onOpen: (() -> Unit)? = null, shared: String? = null) {
    val (lines, more) = if (onOpen != null) SaleText.shownLines(receipt) else receipt.lineItems to 0
    val open = onOpen.takeIf { more > 0 }
    // On the sale, the card's ground grows into the order page's list (M7) while its three lines fade out and the
    // page's whole list fades in, each at its own size (decisions 128, 131).
    Box(Modifier.fillMaxWidth()) {
        if (shared != null) {
            val ground = rememberGroundTrack(shared)
            Box(Modifier.matchParentSize().sharedGround(shared, Move.OrderGrow, GroundLook(mk.card, CardCorner), ground))
            OrderLines(receipt, pos, withTime, lines, more, open, Modifier.words(over = Move.OrderGrow, on = ground))
        } else {
            Box(Modifier.matchParentSize().clip(CardShape).background(mk.card))
            OrderLines(receipt, pos, withTime, lines, more, open, Modifier)
        }
    }
}

@Composable
private fun OrderLines(
    receipt: Receipt,
    pos: PointOfSale?,
    withTime: Boolean,
    lines: List<Receipt.LineItem>,
    more: Int,
    open: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(
        Modifier.fillMaxWidth().then(modifier)
            .clip(CardShape)
            .then(if (open != null) Modifier.clickable(onClickLabel = "See the whole order", onClick = open) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ORDER", fontSize = 12.sp, letterSpacing = 0.6.sp, color = mk.muted, modifier = Modifier.semantics { heading() })
            Text(
                SaleText.receiptLabel(receipt, pos, withTime).uppercase(), fontSize = 12.sp, letterSpacing = 0.6.sp, color = mk.muted,
                textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        for (line in lines) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(SaleText.line(line), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(SaleText.plain(line.lineTotal, receipt.currency), fontSize = 14.sp, color = mk.muted, softWrap = false)
            }
        }
        if (more > 0) Text("+$more more", fontSize = 14.sp, color = mk.link)
        Box(Modifier.fillMaxWidth().height(1.dp).background(mk.divider))
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(SaleText.tax(receipt) ?: "Total", fontSize = 14.sp, color = mk.muted, modifier = Modifier.weight(1f))
            Text(SaleText.total(receipt), fontSize = 14.sp, fontWeight = FontWeight.Bold, softWrap = false)
        }
    }
}

/** The key of the surface a sale's code card closes into: its page's hero. */
fun heroKey(orderId: String) = "hero/$orderId"

/** The key of a sale's state glyph between its page's hero and its shift row's badge. */
fun badgeKey(orderId: String) = "badge/$orderId"

/** A page's hero: the state's glyph at the top of a sale's page. */
val HeroSize = 72.dp

/** A state's glyph on its ground: a shift row's 36 dp circle, a page's [HeroSize] hero. */
@Composable
fun StateBadge(sale: TillSale, icon: StateIcon, ground: Ground, size: Dp, glyph: Dp, quiet: Boolean = false) {
    if (icon == StateIcon.Ring) {
        PhaseRing(sale.phase, size)
        return
    }
    val (vector, tint) = when (icon) {
        StateIcon.Clock -> MkIcons.Clock to mk.warn
        StateIcon.Warning -> MkIcons.Warning to mk.danger
        StateIcon.Tick -> MkIcons.Tick to mk.paid
        StateIcon.Refund -> MkIcons.Refund to if (quiet) mk.muted else mk.ink
        StateIcon.Close, StateIcon.Ring -> MkIcons.Close to if (quiet) mk.muted else mk.ink
    }
    Box(Modifier.size(size).clip(CircleShape).background(groundColor(ground)), contentAlignment = Alignment.Center) {
        Icon(vector, contentDescription = null, tint = tint, modifier = Modifier.size(glyph))
    }
}

/** The theme's colour for a [Ground] role. */
@Composable
fun groundColor(ground: Ground): Color = when (ground) {
    Ground.Paid -> mk.paidGround
    Ground.Warn -> mk.warnGround
    Ground.Danger -> mk.dangerGround
    Ground.Chip -> mk.chip
    Ground.Card -> mk.card
}

/** The theme's colour for a [Tone] role. */
@Composable
fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Link -> mk.link
    Tone.Warn -> mk.warn
    Tone.Danger -> mk.danger
    Tone.Paid -> mk.paid
    Tone.Muted -> mk.muted
}
