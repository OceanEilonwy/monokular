package app.monokular.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monokular.StoreNow
import app.monokular.core.Connection
import app.monokular.core.Tor
import app.monokular.sales.SquareLocation

/** Settings' state. */
data class SettingsState(
    val connection: Connection?,
    /** The store as last read: shown while Settings reads it again. */
    val store: StoreNow,
    val location: SquareLocation?,
    val squareSignedIn: Boolean,
    val tenderName: String,
    val live: Boolean,
    val shiftOn: Boolean,
    val alerts: Boolean,
    val bubbleWanted: Boolean,
    val bubbleAllowed: Boolean,
    val debug: Boolean,
    val squareBaseUrl: String?,
    /** Orbot's proxy answers; null while that's being checked. */
    val torRunning: Boolean? = null,
)

/** What settings can do. */
class SettingsActions(
    val onShift: (Boolean) -> Unit,
    val onAlerts: () -> Unit,
    val onBubble: (Boolean) -> Unit,
    val onSquare: () -> Unit,
    val onChangeLocation: () -> Unit,
    val onSignOutSquare: () -> Unit,
    val onDisconnect: () -> Unit,
    val onSquareBaseUrl: (String) -> Unit,
    /** Copies the store wallet's whole address. */
    val onCopyAddress: (String) -> Unit,
    /** Reach the store over Tor, or not. */
    val onTor: (Boolean) -> Unit,
    val onBack: () -> Unit,
)

/** Settings (PhoneSettings), with Android's shift and bubble in place of the Dynamic Island. */
@Composable
fun SettingsScreen(s: SettingsState, a: SettingsActions) = Page(
    top = {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(MkIcons.Back, "Back", a.onBack)
        }
    },
) {
    Heading("Settings", Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), size = 34)

    val connection = s.connection
    SectionLabel("Store")
    if (connection != null) {
        val store = s.store.store
        val wallet = store?.wallet
        val unknown = if (s.store.reading) "Reading…" else "—"
        Notice(s.store.problem, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        Group(
            rows = listOfNotNull(
                {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Logo(40.dp)
                        Column(Modifier.weight(1f)) {
                            Text(store?.name ?: unknown, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (connection.encrypted) {
                                    Icon(MkIcons.Lock, contentDescription = "Encrypted", tint = mk.paid, modifier = Modifier.size(11.dp))
                                }
                                Muted(connection.host)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (s.live) mk.paid else mk.outline))
                            Text(if (s.live) "Online" else "Offline", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                wallet?.let { { FactRow("Payments go to", it.name) } },
                wallet?.let {
                    {
                        // The short form, with Copy for the whole address.
                        FactRow(
                            "Wallet address", it.addressShort, mono = true,
                            trailing = {
                                RoundIconButton(
                                    MkIcons.Copy, "Copy wallet address", { a.onCopyAddress(it.address) },
                                    Modifier.intoRowPadding(), iconSize = 20.dp,
                                )
                            },
                        )
                    }
                },
                wallet?.let { { FactRow("Network", it.network.replaceFirstChar(Char::uppercase)) } },
                { FactRow("Base currency", store?.baseCurrency ?: unknown) },
                { FactRow("This device", s.store.device?.deviceName ?: unknown) },
            ),
        )
        Footnote("To change the wallet, open the store's settings in your Monokulo dashboard. Monokular can't change it.")
        TorSection(connection, s.store.onionApi, s.torRunning, a.onTor)
    }

    SectionLabel("Square")
    if (s.squareSignedIn && s.location != null) {
        Group(
            rows = listOf(
                { FactRow("Account", s.location.businessName ?: "Signed in") },
                { Box(Modifier.clickable(onClickLabel = "Change the location", onClick = a.onChangeLocation)) { FactRow("Location", s.location.name) } },
                { FactRow("Payment type", s.tenderName) },
                { ActionRow("Sign out of Square", a.onSignOutSquare, mk.link) },
            ),
        )
        Footnote("Sales paid with this payment type at this location show up in Monokular.")
    } else {
        Group(rows = listOf({ ActionRow("Sign in to Square", a.onSquare, mk.link) }))
        Footnote("Without Square, Monokular shows the sales other tills make, without receipts.")
    }

    SectionLabel("Shift")
    Group(rows = listOf({ SwitchRow("Watch for sales", s.shiftOn, a.onShift) }))
    Footnote("While it's on, Monokular checks Square every 2 seconds and keeps a notification. Turn it off at the end of the day.")

    SectionLabel("Alerts")
    Group(rows = listOf({ SwitchRow("New sale alerts", s.alerts, { a.onAlerts() }) }))
    Footnote(
        if (s.alerts) {
            "A new sale makes a sound and shows a banner when you're in another app. Change the sound in Android's settings."
        } else {
            "Alerts are off. Monokular still shows new sales in its bubble and here, but won't make a sound or show a banner."
        },
    )

    SectionLabel("Bubble")
    Group(rows = listOf({ SwitchRow("Float over other apps", s.bubbleWanted && s.bubbleAllowed, a.onBubble) }))
    Footnote(
        when {
            !s.bubbleAllowed -> "Android hasn't allowed Monokular to display over other apps, so sales show in the notification only."
            s.bubbleWanted -> "A faded bubble floats over Square and lights up when a sale comes in. Drag it to either side."
            else -> "Sales show in the notification only."
        },
    )

    SectionLabel("Wake-ups")
    Group(rows = listOf({ FactRow("Checking Square", "Every 2 s") }, { FactRow("Monokular Bell", "Not available yet") }))
    Footnote("The Bell will let Square wake the till, so it can check less often.")

    if (s.debug) {
        SectionLabel("Developer (debug builds)")
        var base by remember { mutableStateOf(s.squareBaseUrl ?: "") }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(base, { base = it }, label = { Text("Stand-in Square address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlineButton("Use it", { a.onSquareBaseUrl(base) }, Modifier.fillMaxWidth())
        }
    }

    Spacer(Modifier.height(22.dp))
    Group(rows = listOf({ ActionRow("Disconnect this device", a.onDisconnect, mk.danger) }))
    Footnote("Removes Monokular from the store's Apps: its key stops working at once. Payments already made aren't affected.")
    Spacer(Modifier.height(32.dp))
}

/**
 * Tor (decision 55): offered while the store has an onion address, through
 * Orbot. A till that connected at the onion address is on Tor already.
 */
@Composable
private fun TorSection(connection: Connection, onionApi: String?, running: Boolean?, onTor: (Boolean) -> Unit) {
    val atOnion = Tor.isOnion(connection.api)
    if (!atOnion && connection.onionApi == null && onionApi == null) return
    SectionLabel("Tor")
    if (atOnion) {
        Group(rows = listOf({ FactRow("Over Tor", "Always") }))
    } else {
        Group(rows = listOf({ SwitchRow("Reach the store over Tor", connection.tor, onTor) }))
    }
    Footnote(
        when {
            connection.overTor && running == false ->
                "Orbot isn't running, so Monokular can't reach the store. Open Orbot" + if (atOnion) "." else ", or turn this off."
            atOnion -> "Monokular connected at the store's onion address, so it reaches the store through Orbot."
            connection.tor -> "Monokular reaches the store at its onion address, through Orbot."
            else -> "The store has an onion address. Turn this on to reach it over Tor, through the Orbot app."
        },
    )
}

/** A row that's a button: its label in [color]. */
@Composable
private fun ActionRow(label: String, onClick: () -> Unit, color: androidx.compose.ui.graphics.Color) = Box(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
    contentAlignment = Alignment.CenterStart,
) { Text(label, fontSize = 17.sp, color = color) }

/**
 * Lets a 48 dp button reach into its row's padding (10 dp above and below,
 * 12 of the 16 dp at the end), so the row keeps the height of the rows
 * around it and the icon lines up with their values. It still takes taps
 * across all 48 dp.
 */
private fun Modifier.intoRowPadding() = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val vertical = 10.dp.roundToPx()
    layout(placeable.width - 12.dp.roundToPx(), placeable.height - 2 * vertical) { placeable.place(0, -vertical) }
}
