package app.monokular.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monokular.Ground
import app.monokular.GroundLook
import app.monokular.Layers
import app.monokular.LocalStage
import app.monokular.Move
import app.monokular.RowEnds
import app.monokular.RowOpens
import app.monokular.ShiftFilter
import app.monokular.ShiftItem
import app.monokular.PosText
import app.monokular.ShiftList
import app.monokular.StateIcon
import app.monokular.rememberGroundTrack
import app.monokular.sales.TillSale
import app.monokular.sales.live
import app.monokular.sharedGround
import app.monokular.sharedSurface
import app.monokular.words
import kotlinx.coroutines.flow.first

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
    // The tray's fold, for the shift screen's life and across a rotation; a fresh shift starts folded (A4).
    var folded by rememberSaveable { mutableStateOf(true) }
    // Back from a sale (or a paid one stepping back): the sale the stage is leaving, read once as the change starts
    // and once as it lands, never a frame. Its row must be there for the ground to shrink into, so a row the fold
    // would hide opens the tray as the change starts (the rows past the third fade in under the arriving card), and it
    // stays open until Show fewer or the shift is left (decision 269). Opened in this composition, the change's first:
    // a frame later the stage already moves and the row it shrinks into isn't there (260 a, decision 289). Once landed,
    // focus goes to that row.
    val stage = LocalStage.current
    val cameFrom = stage?.leavingSale
    val opens = folded && cameFrom != null && ShiftList.folds(sales, filter, cameFrom)
    if (opens) SideEffect { folded = false }
    val shownFolded = folded && !opens
    val items = remember(sales, filter, shownFolded) { ShiftList.sections(sales, filter, shownFolded) }
    val trayFolds = remember(items) { items.any { it is ShiftItem.Fold } }
    val list = rememberLazyListState()
    val focusOn = remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val screenReader = LocalScreenReader.current
    LaunchedEffect(cameFrom) {
        if (cameFrom == null) return@LaunchedEffect
        // Focus moves for a screen reader only: to a sighted cashier a focused row is a grey row nobody asked for.
        if (screenReader(context)) focusOn.value = cameFrom
    }
    val arrive = motion.fade.fraction
    val leave = motion.fadeQuick.fraction
    val placement = motion.settle.spec(IntOffset.VisibilityThreshold)
    Surface(Modifier.fillMaxSize(), color = mk.ground, contentColor = mk.ink) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            // The shift's own words, its chrome as much as its rows, fade through a row's grow and shrink at their own
            // size, so they are never read together with the sale's (decision 221).
            val chrome = Modifier.words(through = ShiftThrough)
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize(), state = list) {
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
                    if (items.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                ShiftList.empty(filter), fontSize = 15.sp, color = mk.muted, textAlign = TextAlign.Center,
                                modifier = chrome.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                            )
                        }
                    }
                    // One list for the tray and Earlier (A4): a row paid or ended is the same keyed item moving from the
                    // tray to the top of Earlier, so the rows' own three specs move everything (M11) and the tray, which
                    // has no node of its own, is its items' amber slices: its height is its rows'.
                    items(items, key = ::itemKey, contentType = ::itemType) { item ->
                        val moves = Modifier.animateItem(fadeInSpec = arrive, placementSpec = placement, fadeOutSpec = leave)
                        when (item) {
                            is ShiftItem.TrayHeader -> TrayHeader(item.count, moves.then(chrome))
                            ShiftItem.Earlier -> SectionHeader(ShiftList.EARLIER, EarlierTop, moves.then(chrome))
                            is ShiftItem.Fold -> FoldRow(item, moves.padding(horizontal = 16.dp).fillMaxWidth().background(mk.warnGround, TrayBottom), chrome) {
                                if (item.open) keepFoldInPlace(list, items.indexOf(item), item.hidden)
                                folded = !folded
                            }
                            is ShiftItem.Row -> {
                                // Opened by its id alone, so a row whose corners change keeps the one it had and skips.
                                val id = item.sale.orderId
                                SaleItem(item, trayFolds, now, focusOn, moves) { actions.onOpen(id) }
                            }
                        }
                    }
                    item(key = "end") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/**
 * One sale's row, in the tray or under Earlier. Tapped, the row's card grows into its sale (M7) and the sale shrinks
 * back into it; only the ground moves, the row's words fade out at their own size as the sale's fade in (decision 185).
 * In the tray the card sits in its slice of the amber, 4 dp in, so it is concentric with the tray's corners.
 */
@Composable
private fun SaleItem(item: ShiftItem.Row, trayFolds: Boolean, now: LongState, focusOn: MutableState<String?>, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val sale = item.sale
    // Only the row the cashier came back to reads where focus goes, and asks for it once the stage has landed. A row
    // that can be clicked takes no focus in touch mode, so this one row may, for as long as it is the one came back to.
    val focused by remember(sale.orderId) { derivedStateOf { focusOn.value == sale.orderId } }
    val focus = if (focused) remember { FocusRequester() } else null
    if (focus != null) {
        val stage = LocalStage.current
        LaunchedEffect(focus) {
            if (stage != null) snapshotFlow { stage.landed() }.first { it }
            focus.requestFocus()
        }
    }
    val ends = item.ends
    val shape = rowShape(ends)
    val top = ends == RowEnds.First || ends == RowEnds.Only
    val bottom = (ends == RowEnds.Last || ends == RowEnds.Only) && !trayFolds
    val slice = if (!item.tray) Modifier else Modifier.background(mk.warnGround, traySlice(top, bottom))
        .padding(start = TrayPadding, end = TrayPadding, top = if (top) TrayPadding else 0.dp, bottom = if (bottom) TrayPadding else 0.dp)
    Box(modifier.padding(horizontal = 16.dp).fillMaxWidth().then(slice)) {
        Box {
            val ground = rememberGroundTrack("sale/${sale.orderId}")
            Box(Modifier.matchParentSize().sharedGround("sale/${sale.orderId}", Move.RowGrow, rowLook(ends, mk.card), ground))
            Column(Modifier.fillMaxWidth().words(over = Move.RowGrow, on = ground).clip(shape)) {
                if (ends == RowEnds.Middle || ends == RowEnds.Last) Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(1.dp).background(mk.divider))
                SaleRow(sale, now, if (focus != null) Modifier.focusRequester(focus).focusProperties { canFocus = true } else Modifier, onOpen)
            }
        }
    }
}

/** "Waiting for payment" and how many, over the tray: one heading to TalkBack. */
@Composable
private fun TrayHeader(count: Int, modifier: Modifier) = Row(
    modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 8.dp)
        .clearAndSetSemantics { heading(); contentDescription = ShiftList.trayHeaderSaid(count) },
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(ShiftList.TRAY_HEADER, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = mk.ink, modifier = Modifier.weight(1f))
    Text("$count", style = tabular, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = mk.muted)
}

/** A section's header over its rows. */
@Composable
private fun SectionHeader(text: String, top: Dp, modifier: Modifier) = Text(
    text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = mk.ink,
    modifier = modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = top, bottom = 8.dp).semantics { heading() },
)

/**
 * The tray's last slice: "Show N more waiting" folded, "Show fewer" open, in ink (a neutral button: the link orange is
 * under 4.5:1 on the tint), the chevron turning over as it changes. Focus stays on it as the rows come and go.
 */
@Composable
private fun FoldRow(fold: ShiftItem.Fold, modifier: Modifier, words: Modifier, onToggle: () -> Unit) {
    val m = motion
    val through = m.fadeThrough.fraction
    val quick = m.fadeQuick.fraction
    val turn by animateFloatAsState(if (fold.open) 180f else 0f, m.settle.spec(FoldTurnThreshold), label = "fold chevron")
    Row(
        modifier.height(48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = ShiftList.foldSaid(fold)
                stateDescription = if (fold.open) "Expanded" else "Collapsed"
                onClick { onToggle(); true }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(words, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Two different words, so through, not across: the old out quickly, the new in once it has gone (decision
            // 188; the canvas's "crossfades", decision 291).
            AnimatedContent(targetState = ShiftList.foldLabel(fold), transitionSpec = { (fadeIn(through) togetherWith fadeOut(quick)).using(null) }, label = "fold label") { label ->
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = mk.ink, maxLines = 1)
            }
            Icon(MkIcons.Fold, contentDescription = null, tint = mk.ink, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = turn })
        }
    }
}

/**
 * Folding an open tray scrolled down past its third row: the rows above the fold row go, and with them the item the
 * list keeps its place by, so the fold row would jump off the top of the screen. The list is asked instead to put it
 * where it is now, under the finger: [fold] is its index before the fold, [hidden] the rows going.
 */
private fun keepFoldInPlace(list: LazyListState, fold: Int, hidden: Int) {
    val after = fold - hidden
    val visible = list.layoutInfo.visibleItemsInfo
    val first = visible.firstOrNull() ?: return
    // Anchored on an item that stays (the third row or above), the list keeps its place by itself.
    if (first.index < after) return
    val y = visible.firstOrNull { it.index == fold }?.offset ?: 0
    val row = first.size.coerceAtLeast(1)
    // The kept rows go above it, one row's height each (the rows are one height), as many as reach where it is.
    val above = ((y + row - 1) / row).coerceIn(0, ShiftList.FOLD_AFTER)
    list.requestScrollToItem(after - above, scrollOffset = (above * row - y).coerceAtLeast(0))
}

/** A list item's key: the tray's three parts by name, a row by its sale, so a row keeps its item between sections. */
private fun itemKey(item: ShiftItem): Any = when (item) {
    is ShiftItem.TrayHeader -> "tray"
    is ShiftItem.Fold -> "fold"
    ShiftItem.Earlier -> "earlier"
    is ShiftItem.Row -> item.sale.orderId
}

private fun itemType(item: ShiftItem): Any = when (item) {
    is ShiftItem.TrayHeader -> 0
    is ShiftItem.Fold -> 1
    ShiftItem.Earlier -> 2
    is ShiftItem.Row -> 3
}

/** The chevron's turn is in degrees: a tenth of one is still. */
private const val FoldTurnThreshold = 0.1f

/** The tray: its corners, and how far inside them the rows' card sits (24 − 4 is the card's 20). */
private val TrayCorner = 24.dp
private val TrayPadding = 4.dp
private val TrayTop = RoundedCornerShape(topStart = TrayCorner, topEnd = TrayCorner)
private val TrayBottom = RoundedCornerShape(bottomStart = TrayCorner, bottomEnd = TrayCorner)
private val TrayOnly = RoundedCornerShape(TrayCorner)

/** A tray row's slice of the amber: rounded at the tray's top, its bottom (when no fold row closes it), or both. */
private fun traySlice(top: Boolean, bottom: Boolean): Shape = when {
    top && bottom -> TrayOnly
    top -> TrayTop
    bottom -> TrayBottom
    else -> RectangleShape
}

/** The Earlier header's space above it, more than the tray header's: it follows the tray. */
private val EarlierTop = 22.dp

/**
 * Whether a screen reader is exploring by touch, asked as a Back lands on the shift: only then does focus go to the
 * row the cashier came from (§12.3's TalkBack). A test seam, as [app.monokular.LocalGroundTracks] is: the app's reads
 * the system's accessibility manager; a device test provides its own.
 */
val LocalScreenReader = staticCompositionLocalOf<(Context) -> Boolean> {
    { context -> context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }
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
private fun SaleRow(sale: TillSale, now: LongState, modifier: Modifier, onOpen: () -> Unit) {
    // Only a live row reads the clock, and only its own second: the shift above it doesn't tick.
    val seconds by remember(sale) { derivedStateOf { if (sale.live) secondsLeft(sale.expiresAt, now.longValue) else 0L } }
    val pos = posOf(sale)
    val row = remember(sale, pos, seconds) { ShiftList.row(sale, pos, seconds) }
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp)
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
