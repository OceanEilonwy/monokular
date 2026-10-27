package app.monokular.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monokular.Ground
import app.monokular.RowOpens
import app.monokular.ShiftFilter
import app.monokular.ShiftList
import app.monokular.StateIcon
import app.monokular.sales.TillSale
import app.monokular.sales.live

/**
 * The shift (the design's ShiftList; decisions 60, 64, 72): what this
 * till is doing, this shift's summary, the filter chips and one row per
 * sale, newest first. Rows have no buttons: a live sale's row opens its
 * code, any other its page.
 */
@Composable
fun ShiftScreen(status: TillStatus, sales: List<TillSale>, actions: TillActions) {
    var filter by rememberSaveable { mutableStateOf(ShiftFilter.All) }
    val rows = ShiftList.rows(sales, filter)
    Surface(Modifier.fillMaxSize(), color = mk.ground, contentColor = mk.ink) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                item { TopRow(status, actions) }
                if (status.disconnected) {
                    item {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Heading("Disconnected")
                            Lead("The store disconnected this device. Connect it again with a new code from the store's settings.")
                            PrimaryButton("Connect again", actions.onForget, Modifier.fillMaxWidth())
                        }
                    }
                    return@LazyColumn
                }
                item {
                    Notice(status.problem, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                    if (status.signInAgain) {
                        PrimaryButton("Sign in to Square again", actions.onSignIn, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth())
                    }
                }
                item {
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("This shift", style = display(28, -0.4), modifier = Modifier.semantics { heading() })
                        Text(
                            if (status.running) ShiftList.summary(sales, status.startedAt) else "Not watching",
                            fontSize = 14.sp, color = mk.muted,
                        )
                    }
                }
                if (!status.running || sales.isEmpty()) item { Idle(status.running, actions) }
                if (sales.isNotEmpty()) {
                    item { Chips(filter, ShiftList.needsAction(sales)) { filter = it } }
                    if (rows.isEmpty()) {
                        item {
                            Text(
                                ShiftList.empty(filter), fontSize = 15.sp, color = mk.muted, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                            )
                        }
                    }
                    itemsIndexed(rows, key = { _, sale -> sale.orderId }) { i, sale ->
                        val shape = RoundedCornerShape(
                            topStart = if (i == 0) 20.dp else 0.dp, topEnd = if (i == 0) 20.dp else 0.dp,
                            bottomStart = if (i == rows.lastIndex) 20.dp else 0.dp, bottomEnd = if (i == rows.lastIndex) 20.dp else 0.dp,
                        )
                        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(mk.card)) {
                            if (i > 0) Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(1.dp).background(mk.divider))
                            SaleRow(sale) { actions.onOpen(sale.orderId) }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/** The dot, the store, this till and whether it's watching; Settings. */
@Composable
private fun TopRow(status: TillStatus, actions: TillActions) = Row(
    Modifier.fillMaxWidth().height(64.dp).padding(start = 24.dp, end = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    val (dot, state) = when {
        status.disconnected -> mk.danger to "Disconnected"
        !status.running -> mk.outline to "Not watching"
        status.live -> mk.paid to "Watching"
        else -> mk.outline to "Reconnecting…"
    }
    val place = listOfNotNull(status.store, status.location)
    Row(
        Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = (place + state).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text((place + state).joinToString(" · "), fontSize = 13.sp, color = mk.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    CircleButton(MkIcons.Settings, "Settings", actions.onSettings, ground = mk.chip, tint = mk.ink, iconSize = 20.dp)
}

/** No sale yet, or not watching: the faded mark and what happens next. */
@Composable
private fun Idle(running: Boolean, actions: TillActions) = Column(
    Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
) {
    Logo(140.dp, Modifier.alpha(0.35f), grey = true)
    Text(
        if (running) "No sale right now" else "Not watching for sales",
        style = display(22, 0.0), color = mk.muted, textAlign = TextAlign.Center,
    )
    Text(
        if (running) {
            "The code appears here, and the bubble over Square lights up, when you tap Monero in Square."
        } else {
            "Start watching and Monokular checks Square for Monero sales until you stop it."
        },
        fontSize = 15.sp, lineHeight = 21.sp, color = mk.muted, textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 280.dp),
    )
    if (!running) PrimaryButton("Start watching", actions.onStartShift)
}

/** All, Needs action with its count, Paid: one selected, as tabs to TalkBack. */
@Composable
private fun Chips(filter: ShiftFilter, needsAction: Int, onFilter: (ShiftFilter) -> Unit) = Row(
    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp).selectableGroup(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    for (chip in ShiftFilter.entries) {
        val selected = chip == filter
        val label = when (chip) {
            ShiftFilter.All -> "All"
            ShiftFilter.NeedsAction -> "Needs action"
            ShiftFilter.Paid -> "Paid"
        }
        val count = needsAction.takeIf { chip == ShiftFilter.NeedsAction && it > 0 }
        Row(
            Modifier.minimumInteractiveComponentSize().height(36.dp).clip(RoundedCornerShape(18.dp))
                .then(if (selected) Modifier.background(mk.ink) else Modifier.border(BorderStroke(1.5.dp, mk.edge), RoundedCornerShape(18.dp)))
                .selectable(selected = selected, role = Role.Tab) { onFilter(chip) }
                .semantics { contentDescription = listOfNotNull(label, count?.toString()).joinToString(", ") }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) mk.ground else mk.ink)
            if (count != null) {
                Box(
                    Modifier.heightIn(min = 20.dp).widthIn(min = 20.dp).clip(RoundedCornerShape(10.dp)).background(mk.warn).padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("$count", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = mk.ground) }
            }
        }
    }
}

/** One sale: its state's icon, "Sale #m2Qe · $8.40", how it stands, and where a tap goes. */
@Composable
private fun SaleRow(sale: TillSale, onOpen: () -> Unit) {
    val row = ShiftList.row(sale, if (sale.live) secondsLeft(sale.expiresAt) else 0)
    val ground = when (row.icon) {
        StateIcon.Clock -> Ground.Warn
        StateIcon.Warning -> Ground.Danger
        StateIcon.Tick -> Ground.Paid
        StateIcon.Ring, StateIcon.Refund, StateIcon.Close -> Ground.Chip
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(onClickLabel = if (row.opens == RowOpens.Code) "Show the code" else "Open the details", role = Role.Button, onClick = onOpen)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.clearAndSetSemantics {}) { StateBadge(sale, row.icon, ground, RingRowSize, 18.dp, quiet = true) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(row.title, fontSize = 16.sp, color = if (row.quiet) mk.muted else mk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.sub, fontSize = 13.sp, color = toneColor(row.tone), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (row.opens == RowOpens.Code) {
            Icon(MkIcons.Code, contentDescription = null, tint = mk.muted, modifier = Modifier.size(22.dp))
        } else {
            Icon(MkIcons.Chevron, contentDescription = null, tint = mk.muted, modifier = Modifier.size(16.dp))
        }
    }
}
