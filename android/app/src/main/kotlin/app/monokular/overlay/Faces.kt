package app.monokular.overlay

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.monokular.BubbleLook
import app.monokular.SaleText
import app.monokular.core.SalePhase
import app.monokular.sales.TillSale
import app.monokular.ui.Fixed
import app.monokular.ui.LoadingMark
import app.monokular.ui.Logo
import app.monokular.ui.MkIcons
import app.monokular.ui.OutlineButton
import app.monokular.ui.PaidTick
import app.monokular.ui.PrimaryButton
import app.monokular.ui.QrCode
import app.monokular.ui.RoundIconButton
import app.monokular.ui.display
import app.monokular.ui.mk
import app.monokular.ui.reducedMotion
import kotlinx.coroutines.delay

/**
 * The bubble (the design's Bubble part), in each [look]: faded and grey
 * with nothing to do, white with a pulse and the amount beside it for a
 * sale, green with a tick once it's paid. Its colours are the design's
 * whatever the theme: it sits over another app.
 */
@Composable
fun BubbleFace(look: BubbleLook, amount: String?, pillOnLeft: Boolean, pulse: Boolean = true) = Box(
    Modifier.padding(12.dp).clearAndSetSemantics {},
    contentAlignment = Alignment.Center,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (look == BubbleLook.Lit && amount != null && pillOnLeft) Pill(amount, Modifier.zIndex(0f), toward = 8)
        Circle(look, pulse, Modifier.zIndex(1f))
        if (look == BubbleLook.Lit && amount != null && !pillOnLeft) Pill(amount, Modifier.zIndex(0f), toward = -8)
    }
}

/** The amount beside the lit bubble, tucked [toward] dp under it. */
@Composable
private fun Pill(amount: String, modifier: Modifier, toward: Int) = Box(
    modifier
        .offset(x = toward.dp)
        .height(24.dp)
        .clip(CircleShape)
        .background(Fixed.Ink)
        .padding(start = if (toward > 0) 10.dp else 17.dp, end = if (toward > 0) 17.dp else 9.dp),
    contentAlignment = Alignment.Center,
) { Text(amount, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }

@Composable
private fun Circle(look: BubbleLook, pulse: Boolean, modifier: Modifier) {
    when (look) {
        BubbleLook.Idle -> Box(
            modifier.alpha(0.55f).shadow(4.dp, CircleShape).size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)),
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
                modifier.size(56.dp).drawBehind {
                    val p = ring?.value ?: return@drawBehind
                    drawCircle(Fixed.Accent.copy(alpha = 0.55f * (1f - p)), radius = size.minDimension / 2 + 16.dp.toPx() * p)
                }.shadow(6.dp, CircleShape).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) { LoadingMark(40.dp, label = "New sale", ring = Fixed.Ink) }
        }
        BubbleLook.Paid -> PaidTickBubble(modifier)
    }
}

@Composable
private fun PaidTickBubble(modifier: Modifier) = Box(modifier.shadow(6.dp, CircleShape)) { PaidTick(56.dp) }

/**
 * The code over another app (AndPanel on a phone, AndTabFloat on a
 * tablet): the sale's amount, its code and status, with Minimise, Copy
 * address and Full screen. Paid, it says so and closes itself.
 */
@Composable
fun SalePanel(
    sale: TillSale,
    place: String,
    wide: Boolean,
    onMinimise: () -> Unit,
    onCopy: () -> Unit,
    onFullScreen: () -> Unit,
) {
    if (sale.phase == SalePhase.Paid) LaunchedEffect(sale.orderId) { delay(5_000); onMinimise() }
    Column(
        Modifier.then(if (wide) Modifier.width(360.dp) else Modifier.fillMaxWidth())
            .shadow(16.dp, RoundedCornerShape(32.dp))
            .clip(RoundedCornerShape(32.dp))
            .background(mk.ground)
            .semantics { paneTitle = "Monokular payment code" }
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = if (wide) 10.dp else 20.dp, top = if (wide) 14.dp else 20.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val waiting = !sale.phase.isFinal
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (wide && waiting) LoadingMark(32.dp, label = SaleText.status(sale))
            Column(Modifier.weight(1f)) {
                Text(SaleText.title(sale), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(place, fontSize = if (wide) 12.sp else 13.sp, color = mk.muted)
            }
            RoundIconButton(
                if (wide) MkIcons.Minus else MkIcons.ChevronDown,
                if (wide) "Collapse to bubble" else "Minimise",
                onMinimise,
                filled = true,
                iconSize = 16.dp,
            )
        }
        Column(Modifier.padding(end = if (wide) 10.dp else 0.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 14.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(SaleText.amount(sale), style = display(44, -0.8))
                Text(SaleText.xmr(sale), fontSize = 15.sp, color = mk.muted)
            }
            when (sale.phase) {
                SalePhase.Paid -> {
                    PaidTick(88.dp)
                    Text("Paid", style = display(28), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text("${SaleText.xmr(sale)} received", fontSize = 15.sp, color = mk.muted)
                }
                SalePhase.Expired, SalePhase.Cancelled, SalePhase.DoubleSpend -> {
                    Box(Modifier.size(88.dp).clip(CircleShape).background(mk.warnGround), contentAlignment = Alignment.Center) {
                        Icon(MkIcons.Clock, contentDescription = null, tint = mk.warn, modifier = Modifier.size(44.dp))
                    }
                    Text(SaleText.status(sale), style = display(28), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text("Open it full screen to refund the Square sale.", fontSize = 15.sp, color = mk.muted)
                }
                else -> {
                    QrCode(
                        sale.code.uri,
                        "Payment code for ${SaleText.amount(sale)}",
                        Modifier.widthIn(max = if (wide) 280.dp else 268.dp).fillMaxWidth(),
                        padding = if (wide) 16.dp else 14.dp,
                        radius = if (wide) 30.dp else 28.dp,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    ) {
                        if (!wide) LoadingMark(28.dp, label = "", modifier = Modifier.clearAndSetSemantics {})
                        Text(SaleText.status(sale), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sale.phase == SalePhase.Paid) {
                    PrimaryButton("Done", onMinimise, Modifier.weight(1f), height = 48.dp, size = 15)
                } else {
                    if (waiting) OutlineButton("Copy address", onCopy, Modifier.weight(1f), size = 15)
                    PrimaryButton("Full screen", onFullScreen, Modifier.weight(1f), height = 48.dp, size = 15)
                }
            }
        }
    }
}

/**
 * The panel's window on a phone: Square dimmed behind it (a tap there
 * minimises it), the lit bubble docked above the panel.
 */
@Composable
fun PhonePanel(
    sale: TillSale,
    place: String,
    look: BubbleLook,
    onMinimise: () -> Unit,
    onCopy: () -> Unit,
    onFullScreen: () -> Unit,
) = Box(
    Modifier.fillMaxSize().background(Fixed.Scrim)
        .clickable(onClickLabel = "Minimise", indication = null, interactionSource = null, onClick = onMinimise),
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp), horizontalAlignment = Alignment.End) {
        Spacer(Modifier.height(4.dp))
        Box(Modifier.clickable(onClickLabel = "Minimise", onClick = onMinimise).semantics { contentDescription = "Minimise" }) {
            BubbleFace(look, SaleText.amount(sale), pillOnLeft = true)
        }
        Box(Modifier.clickable(indication = null, interactionSource = null) { }) {
            SalePanel(sale, place, wide = false, onMinimise, onCopy, onFullScreen)
        }
    }
}
