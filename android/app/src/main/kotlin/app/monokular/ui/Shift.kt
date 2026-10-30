package app.monokular.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monokular.Ground
import app.monokular.GroundLook
import app.monokular.Layers
import app.monokular.Move
import app.monokular.RowEnds
import app.monokular.RowOpens
import app.monokular.ShiftFilter
import app.monokular.PosText
import app.monokular.ShiftList
import app.monokular.StateIcon
import app.monokular.rememberGroundTrack
import app.monokular.sales.TillSale
import app.monokular.sales.live
import app.monokular.sharedGround
import app.monokular.sharedSurface
import app.monokular.words

/**
 * The shift (the design's ShiftList; decisions 60, 64, 72): what this
 * till is doing, this shift's summary, the filter chips and one row per
 * sale, newest first. Rows have no buttons: a live sale's row opens its
 * code, any other its page. [now] is the screen's clock: only the live
 * rows read it.
 */
@Composable
fun ShiftScreen(status: TillStatus, sales: List<TillSale>, actions: TillActions, now: LongState = rememberNowSeconds()) {
    var filter by rememberSaveable { mutableStateOf(ShiftFilter.All) }
    val rows = ShiftList.rows(sales, filter)
    val arrive = motion.fade.fraction
    val leave = motion.fadeQuick.fraction
    val placement = motion.settle.spec(IntOffset.VisibilityThreshold)
    Surface(Modifier.fillMaxSize(), color = mk.ground, contentColor = mk.ink) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            // The shift's own words, its chrome as much as its rows, fade through a row's grow and shrink at their own
            // size, so they are never read together with the sale's (decision 221).
            val chrome = Modifier.words(through = ShiftThrough)
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                item(key = "top") { Box(chrome) { TopRow(status, actions) } }
                if (status.disconnected) {
                    item(key = "disconnected") {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Heading("Disconnected")
                            Lead("The store disconnected this device. Connect it again with a new code from the store's settings.")
                            PrimaryButton("Connect again", actions.onForget, Modifier.fillMaxWidth())
                        }
                    }
                    return@LazyColumn
                }
                item(key = "problem") {
                    Notice(status.problem, chrome.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                    if (status.signInAgain) {
                        PrimaryButton(PosText.signInAgain(LocalPointOfSale.current), actions.onSignIn, chrome.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth())
                    }
                }
                item(key = "summary") {
                    Column(chrome.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("This shift", style = display(28, -0.4), modifier = Modifier.semantics { heading() })
                        Text(
                            if (status.running) ShiftList.summary(sales, status.startedAt) else "Not watching",
                            fontSize = 14.sp, color = mk.muted,
                        )
                    }
                }
                if (!status.running || sales.isEmpty()) item(key = "idle") { Idle(status.running, actions) }
                if (sales.isNotEmpty()) {
                    item(key = "chips") { Box(chrome) { Chips(filter, ShiftList.needsAction(sales)) { filter = it } } }
                    if (rows.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                ShiftList.empty(filter), fontSize = 15.sp, color = mk.muted, textAlign = TextAlign.Center,
                                modifier = chrome.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                            )
                        }
                    }
                    itemsIndexed(rows, key = { _, sale -> sale.orderId }) { i, sale ->
                        // A row arriving fades in, the rows it displaces slide to their place, a row leaving fades (M11);
                        // its corners, which follow its index, snap.
                        // Tapped, the row's card grows into its sale (M7) and the sale shrinks back into it.
                        // Only the ground moves; the row's words fade out at their own size as the sale's fade in (decision 185).
                        val ends = ShiftList.ends(i, rows.lastIndex)
                        val shape = rowShape(ends)
                        Box(
                            Modifier.animateItem(fadeInSpec = arrive, placementSpec = placement, fadeOutSpec = leave)
                                .padding(horizontal = 16.dp).fillMaxWidth(),
                        ) {
                            val ground = rememberGroundTrack("sale/${sale.orderId}")
                            Box(Modifier.matchParentSize().sharedGround("sale/${sale.orderId}", Move.RowGrow, rowLook(ends, mk.card), ground))
                            Column(Modifier.fillMaxWidth().words(over = Move.RowGrow, on = ground).clip(shape)) {
                                if (i > 0) Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(1.dp).background(mk.divider))
                                SaleRow(sale, now) { actions.onOpen(sale.orderId) }
                            }
                        }
                    }
                    item(key = "end") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/** The moves the shift's chrome fades through: a row growing into its sale, and back. */
private val ShiftThrough = setOf(Move.RowGrow)

/** The rows' card's corner. */
private val RowCorner = 20.dp
private val OnlyRow = RoundedCornerShape(RowCorner)
private val FirstRow = RoundedCornerShape(topStart = RowCorner, topEnd = RowCorner)
private val LastRow = RoundedCornerShape(bottomStart = RowCorner, bottomEnd = RowCorner)

/** A row's ground: the rows' card's corners at its ends. */
private fun rowLook(ends: RowEnds, card: Color) = when (ends) {
    RowEnds.Only -> GroundLook(card, RowCorner)
    RowEnds.First -> GroundLook(card, RowCorner, 0.dp)
    RowEnds.Middle -> GroundLook(card, 0.dp)
    RowEnds.Last -> GroundLook(card, 0.dp, RowCorner)
}

/** A row's part of the card, made once rather than per row per composition. */
private fun rowShape(ends: RowEnds): Shape = when (ends) {
    RowEnds.Only -> OnlyRow
    RowEnds.First -> FirstRow
    RowEnds.Middle -> RectangleShape
    RowEnds.Last -> LastRow
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
    // A Clover merchant is often named as the store is: said once.
    val place = listOfNotNull(status.store, status.location).distinct()
    Row(
        Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = (place + state).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        // The state word is what the cashier reads this row for: the store and location give way first at large font sizes.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (place.isNotEmpty()) Text(
                place.joinToString(" · "), fontSize = 13.sp, color = mk.muted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Text(if (place.isEmpty()) state else " · $state", fontSize = 13.sp, color = mk.muted, maxLines = 1, softWrap = false)
        }
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
        if (running) PosText.waitingHint(LocalPointOfSale.current) else PosText.startHint(LocalPointOfSale.current),
        fontSize = 15.sp, lineHeight = 21.sp, color = mk.muted, textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 280.dp),
    )
    if (!running) PrimaryButton("Start watching", actions.onStartShift)
}

/** All, Needs action with its count, Paid: one selected, as tabs to TalkBack. */
@Composable
private fun Chips(filter: ShiftFilter, needsAction: Int, onFilter: (ShiftFilter) -> Unit) = Row(
    // A large font can make the chips wider than the screen: they scroll rather than wrap a label into a blob.
    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 6.dp).selectableGroup(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    for (chip in ShiftFilter.entries) {
        val selected = chip == filter
        val label = when (chip) {
            ShiftFilter.All -> "All"
            ShiftFilter.NeedsAction -> "Needs action"
            ShiftFilter.Paid -> "Paid"
        }
        FilterChip(label, needsAction.takeIf { chip == ShiftFilter.NeedsAction && it > 0 }, selected) { onFilter(chip) }
    }
}

/** A filter chip's height at rest, and its label's inset from its ends. */
private val ChipHeight = 36.dp
private val ChipInset = 14.dp

/** The count badge's height at rest, and the inset that keeps it concentric with the chip's round end. */
private val BadgeHeight = 20.dp
private val BadgeInset = (ChipHeight - BadgeHeight) / 2

/**
 * One filter chip: a pill holding its [label] and, when there is one, its
 * [count] badge. The badge is a pill too, inset from the chip's end by as
 * much as from its top and bottom, so its curve follows the chip's at any
 * count and any font scale: it fills the chip's height inside that inset.
 */
@Composable
internal fun FilterChip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.minimumInteractiveComponentSize().testTag(ChipTag)
            .heightIn(min = ChipHeight).height(IntrinsicSize.Min).clip(shape)
            .then(if (selected) Modifier.background(mk.ink) else Modifier.border(BorderStroke(1.5.dp, mk.edge), shape))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = listOfNotNull(label, count?.toString()).joinToString(", ") }
            .then(if (count == null) Modifier.padding(horizontal = ChipInset) else Modifier.padding(start = ChipInset, end = BadgeInset, top = BadgeInset, bottom = BadgeInset)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) mk.ground else mk.ink, maxLines = 1, softWrap = false)
        if (count != null) {
            Box(
                // The tag sits where the ground is drawn, so a test measures the pill a cashier sees.
                Modifier.fillMaxHeight().heightIn(min = BadgeHeight).atLeastAsWideAsTall().testTag(BadgeTag)
                    .clip(shape).background(mk.warn).padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) { Text("$count", style = tabular, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = mk.ground, maxLines = 1, softWrap = false) }
        }
    }
}

internal const val ChipTag = "filter chip"
internal const val BadgeTag = "filter chip count"

/**
 * Holds what follows (the badge's ground included) at least as wide as the
 * height it is given, so a one-digit badge is a circle and a longer one a pill.
 */
private fun Modifier.atLeastAsWideAsTall() = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = maxOf(constraints.minWidth, constraints.minHeight).coerceAtMost(constraints.maxWidth)))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** The ground of a row's badge, by its glyph. */
private fun badgeGround(icon: StateIcon): Ground = when (icon) {
    StateIcon.Clock -> Ground.Warn
    StateIcon.Warning -> Ground.Danger
    StateIcon.Tick -> Ground.Paid
    StateIcon.Ring, StateIcon.Refund, StateIcon.Close -> Ground.Chip
}

/** One sale: its state's icon, "Sale #m2Qe · $8.40", how it stands, and where a tap goes. */
@Composable
private fun SaleRow(sale: TillSale, now: LongState, onOpen: () -> Unit) {
    // Only a live row reads the clock, and only its own second: the shift above it doesn't tick.
    val seconds by remember(sale) { derivedStateOf { if (sale.live) secondsLeft(sale.expiresAt, now.longValue) else 0L } }
    val pos = posOf(sale)
    val row = remember(sale, pos, seconds) { ShiftList.row(sale, pos, seconds) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(onClickLabel = if (row.opens == RowOpens.Code) "Show the code" else "Open the details", role = Role.Button, onClick = onOpen)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowBadge(sale, row.icon)
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

/**
 * A row's badge: ring to tick, ring to clock, one face fading into the
 * next (the ring's own arc sweeps, M6). Its own skippable scope, so the
 * row's countdown ticking doesn't call into its transition: it runs only
 * when the glyph changes. Opening a sale that isn't live, the badge grows
 * into its page's hero; a paid sale stepping back, the hero shrinks into it.
 */
@Composable
private fun RowBadge(sale: TillSale, icon: StateIcon) = Box(Modifier.clearAndSetSemantics {}) {
    val fade = motion.fade.fraction
    AnimatedContent(targetState = icon, transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)).using(null) }, label = "row badge") { face ->
        Box(Modifier.sharedSurface(badgeKey(sale.orderId), Move.RowGrow, CircleShape, layer = Layers.OVER_GROUND)) {
            StateBadge(sale, face, badgeGround(face), RingRowSize, 18.dp, quiet = true)
        }
    }
}
