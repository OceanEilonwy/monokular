package app.monokular.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import app.monokular.SaleText
import app.monokular.TillFocus
import app.monokular.core.SalePhase
import app.monokular.sales.TillSale
import kotlinx.coroutines.delay

/** Seconds left until [expiresAt], ticking. */
@Composable
fun secondsLeft(expiresAt: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(expiresAt) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1000)
        }
    }
    return (expiresAt - now).coerceAtLeast(0)
}

/** What the till screen needs to know about the shift. */
data class TillStatus(
    /** The store's name as last read; null until the first read answers. */
    val store: String?,
    val location: String?,
    val running: Boolean,
    val live: Boolean,
    val disconnected: Boolean,
    val problem: String?,
    val signInAgain: Boolean,
)

/** What the till screen can do. */
class TillActions(
    val onShowing: (String?) -> Unit,
    val onSettings: () -> Unit,
    val onOpen: (String) -> Unit,
    /** Leaves a sale's full screen: it stays live, on the bubble and in the shift's list. */
    val onBack: (String) -> Unit,
    /** Takes a sale off this till altogether, whatever happens to its order. */
    val onCloseSale: (String) -> Unit,
    val onRefund: (String) -> Unit,
    val onCopy: (TillSale) -> Unit,
    val onCopyXmr: (TillSale) -> Unit,
    /** Null when Square is already beside Monokular (split screen). */
    val onBackToSquare: (() -> Unit)?,
    val onSignIn: () -> Unit,
    val onForget: () -> Unit,
    val onStartShift: () -> Unit,
)

/**
 * The till: the current sale full screen (AndFull, PhonePaid, PhoneUnpaid),
 * or, with none, the idle screen (AndTabSplit's Monokular pane) with this
 * shift's sales.
 */
@Composable
fun TillScreen(status: TillStatus, sales: List<TillSale>, closed: Set<String>, focus: String?, actions: TillActions) {
    // A paid sale closes itself after a few seconds; one that ended unpaid waits for Done.
    val current = TillFocus.pick(sales, closed, focus)
    androidx.compose.runtime.SideEffect { actions.onShowing(current?.orderId) }
    if (current != null && !status.disconnected) {
        SaleScreen(current, status.location, actions)
    } else {
        IdleScreen(status, sales, actions)
    }
}

@Composable
private fun IdleScreen(status: TillStatus, sales: List<TillSale>, actions: TillActions) = Page(maxWidth = 640.dp) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (dot, line) = when {
            status.disconnected -> mk.danger to "Disconnected"
            !status.running -> mk.outline to "Not watching"
            status.live -> mk.paid to "Watching"
            else -> mk.outline to "Reconnecting…"
        }
        Row(
            Modifier.weight(1f).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(
                listOfNotNull(status.store, status.location).joinToString(" · "),
                fontSize = 13.sp, color = mk.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = "${listOfNotNull(status.store, status.location).joinToString(", ")}: $line" },
            )
        }
        RoundIconButton(MkIcons.Settings, "Settings", actions.onSettings, filled = true, iconSize = 20.dp)
    }
    if (status.disconnected) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Heading("Disconnected")
            Lead("The store disconnected this device. Connect it again with a new code from the store's settings.")
            PrimaryButton("Connect again", actions.onForget, Modifier.fillMaxWidth())
        }
        return@Page
    }
    Notice(status.problem, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
    if (status.signInAgain) {
        PrimaryButton("Sign in to Square again", actions.onSignIn, Modifier.padding(16.dp).fillMaxWidth())
    }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 360.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Logo(140.dp, Modifier.alpha(0.35f), grey = true)
        Text(
            if (status.running) "No sale right now" else "Not watching for sales",
            style = display(22, 0.0), color = mk.muted, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            if (status.running) {
                "The code appears here, and the bubble over Square lights up, when you tap Monero in Square."
            } else {
                "Start watching and Monokular checks Square for Monero sales until you stop it."
            },
            fontSize = 15.sp, lineHeight = 21.sp, color = mk.muted, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        if (!status.running) PrimaryButton("Start watching", actions.onStartShift)
    }
    if (sales.isNotEmpty()) {
        SectionLabel("This shift")
        Group(
            rows = sales.take(20).map { sale ->
                {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable(onClickLabel = "Show ${SaleText.title(sale)}") { actions.onOpen(sale.orderId) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(SaleText.title(sale), fontSize = 17.sp)
                            Muted(SaleText.status(sale))
                        }
                        Text(SaleText.amount(sale), fontSize = 17.sp, color = mk.muted)
                    }
                }
            },
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** The sale's top app bar: Back to the till, the sale and where it's from, and Settings. */
@Composable
private fun SaleBar(sale: TillSale, location: String?, actions: TillActions) = Row(
    Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
) {
    RoundIconButton(MkIcons.Back, "Back to the till", { actions.onBack(sale.orderId) }, iconSize = 22.dp)
    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
        Text(SaleText.title(sale), fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(SaleText.place(sale, location), fontSize = 13.sp, color = mk.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    RoundIconButton(MkIcons.Settings, "Settings", actions.onSettings, filled = true, iconSize = 20.dp)
}

/** The amount, and the XMR to pay under it, which copies itself when tapped ([onCopyXmr]). */
@Composable
private fun Amount(sale: TillSale, onCopyXmr: (TillSale) -> Unit, size: Int = 56, start: Boolean = false) = Column(
    horizontalAlignment = if (start) Alignment.Start else Alignment.CenterHorizontally,
) {
    val align = if (start) TextAlign.Start else TextAlign.Center
    Text(SaleText.amount(sale), style = display(size, -1.0), textAlign = align)
    Text(
        SaleText.xmr(sale), fontSize = 18.sp, color = mk.muted, textAlign = align,
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Copy the XMR amount") { onCopyXmr(sale) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** "Waiting for payment" with the loading mark, on its pill. */
@Composable
private fun StatusPill(sale: TillSale) {
    val sub = when (sale.phase) {
        SalePhase.Waiting -> "Scan with any Monero wallet · ${SaleText.clock(secondsLeft(sale.expiresAt))} left"
        SalePhase.Partial -> "Ask the customer to pay the rest to the same code"
        else -> "The customer can go: it's being confirmed"
    }
    Row(
        Modifier.clip(RoundedCornerShape(28.dp)).background(mk.inset).padding(start = 12.dp, end = 18.dp, top = 10.dp, bottom = 10.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LoadingMark(32.dp, label = "", modifier = Modifier.clearAndSetSemantics {})
        Column {
            Text(SaleText.status(sale), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 13.sp, color = mk.muted)
        }
    }
}

/** A sale full screen, for the customer to scan (AndFull); paid (PhonePaid) or not (PhoneUnpaid) when it ends. */
@Composable
private fun SaleScreen(sale: TillSale, location: String?, actions: TillActions) {
    if (sale.phase == SalePhase.Paid && !sale.refunded) {
        // Back to watching after a few seconds, as the design's paid panel does.
        LaunchedEffect(sale.orderId) {
            delay(5_000)
            actions.onBack(sale.orderId)
        }
    }
    Surface(Modifier.fillMaxSize(), color = mk.ground, contentColor = mk.ink) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 840.dp && sale.phase.isFinal.not()
            Column(Modifier.fillMaxSize()) {
                SaleBar(sale, location, actions)
                when {
                    sale.phase == SalePhase.Paid -> Ended(sale, actions, paid = true)
                    sale.phase.isFinal -> Ended(sale, actions, paid = false)
                    wide -> Row(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Code(sale, min(this@BoxWithConstraints.maxHeight - 120.dp, 440.dp))
                        Column(
                            Modifier.widthIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Amount(sale, actions.onCopyXmr, start = true)
                            StatusPill(sale)
                            ProgressSegments(SaleText.step(sale.phase), Modifier.fillMaxWidth())
                            Buttons(sale, actions)
                        }
                    }
                    else -> {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Amount(sale, actions.onCopyXmr)
                            Code(sale, min(min(this@BoxWithConstraints.maxWidth - 40.dp, 320.dp), maxOf(this@BoxWithConstraints.maxHeight - 360.dp, 200.dp)))
                            StatusPill(sale)
                        }
                        Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) { Buttons(sale, actions) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Code(sale: TillSale, side: Dp) = QrCode(
    sale.code.uri,
    "Payment code for ${SaleText.amount(sale)}" + if (sale.code.hasReceipt) ", with the receipt in it" else "",
    Modifier.width(side),
    padding = if (side < 260.dp) 14.dp else 20.dp,
    radius = if (side < 260.dp) 28.dp else 36.dp,
)

@Composable
private fun Buttons(sale: TillSale, actions: TillActions) = Column(
    Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlineButton("Copy address", { actions.onCopy(sale) }, Modifier.weight(1f), height = 56.dp)
        actions.onBackToSquare?.let { PrimaryButton("Back to Square", it, Modifier.weight(1f), height = 56.dp, size = 16) }
    }
    CloseSale(sale, actions)
}

/** "Close sale", after asking: the sale leaves this till, though its order carries on in Monokulo. */
@Composable
private fun CloseSale(sale: TillSale, actions: TillActions) {
    var asking by remember { mutableStateOf(false) }
    LinkButton("Close sale", { asking = true }, size = 16, color = mk.muted)
    if (!asking) return
    AlertDialog(
        onDismissRequest = { asking = false },
        containerColor = mk.card,
        titleContentColor = mk.ink,
        textContentColor = mk.muted,
        title = { Text("Close this sale?") },
        text = {
            Text(
                "Monokular stops showing it and takes it off this shift. If the customer pays anyway, Monokulo still records the payment." +
                    if (sale.found != null && !sale.refunded) " Square has already recorded this sale as Monero: refund it there if the customer isn't paying." else "",
            )
        },
        confirmButton = { LinkButton("Close sale", { asking = false; actions.onCloseSale(sale.orderId) }, color = mk.danger) },
        dismissButton = { LinkButton("Keep it", { asking = false }) },
    )
}

/** Paid, or ended without the money. */
@Composable
private fun ColumnScope.Ended(sale: TillSale, actions: TillActions, paid: Boolean) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (paid) {
            PaidTick(88.dp)
        } else {
            Box(Modifier.size(88.dp).clip(CircleShape).background(mk.warnGround), contentAlignment = Alignment.Center) {
                Icon(MkIcons.Clock, contentDescription = null, tint = mk.warn, modifier = Modifier.size(44.dp))
            }
        }
        Text(
            SaleText.status(sale), style = display(28), textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
        )
        Text("${SaleText.amount(sale)} · ${SaleText.xmr(sale)}", style = display(22, 0.0), textAlign = TextAlign.Center)
        val text = when {
            paid && sale.refunded -> "${SaleText.xmr(sale)} arrived after the Square sale was refunded. Ring the sale up in Square again, or return the payment."
            paid -> "${SaleText.xmr(sale)} received. Back to watching in a moment."
            sale.refunded -> "The Square sale is refunded."
            sale.found == null -> "Another till took this sale: refund it in Square there if the customer isn't paying."
            sale.phase == SalePhase.DoubleSpend -> "The payment was reversed on the network. Square has already recorded this sale as Monero, so refund it there."
            sale.phase == SalePhase.Cancelled -> "The order was cancelled in Monokulo. Square has already recorded this sale as Monero, so refund it there."
            else -> "The code expired. Square has already recorded this sale as Monero, so refund it there if the customer isn't paying."
        }
        Text(text, fontSize = 16.sp, lineHeight = 22.sp, color = mk.muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
    }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp).widthIn(max = 560.dp).fillMaxWidth().align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!paid && sale.found != null && !sale.refunded) {
            PrimaryButton("Refund the Square sale", { actions.onRefund(sale.orderId) }, Modifier.fillMaxWidth())
            OutlineButton("Done", { actions.onBack(sale.orderId) }, Modifier.fillMaxWidth(), height = 52.dp, size = 17)
        } else {
            PrimaryButton("Done", { actions.onBack(sale.orderId) }, Modifier.fillMaxWidth())
        }
    }
}
