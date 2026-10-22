package app.monokular.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.monokular.R
import app.monokular.core.ConnectCode
import app.monokular.core.Connection
import app.monokular.core.SalePhase
import app.monokular.sales.SquareLocation
import app.monokular.sales.TillSale
import kotlinx.coroutines.delay

/** A screen's frame: safe from the system bars, scrolling, at most a phone's width on a tablet. */
@Composable
fun Page(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) { content() }
        }
    }
}

@Composable
fun Title(text: String) = Text(
    text,
    style = MaterialTheme.typography.headlineMedium,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.semantics { heading() },
)

@Composable
fun Muted(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun Problem(text: String?) {
    if (text == null) return
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
    )
}

@Composable
fun Mark(size: Int = 72) = Image(painterResource(R.drawable.monokulo_mark), contentDescription = null, modifier = Modifier.size(size.dp))

@Composable
fun WelcomeScreen(onConnect: () -> Unit) = Page {
    Spacer(Modifier.height(32.dp))
    Mark(96)
    Title("Take Monero at your till")
    Muted("Monokular shows your customer the payment code when you take a Monero sale, and tells you when it's paid.")
    Muted("To start, open your store's settings on its Monokulo, go to Connections › Apps › Connect an app, and scan the code it shows.")
    Spacer(Modifier.height(8.dp))
    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect to a store") }
}

@Composable
fun ScanScreen(onCode: (String) -> Boolean, onBack: () -> Unit) = Page {
    val context = LocalContext.current
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }
    var pasted by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Title("Scan the connect code")
    Muted("It's on your store's settings: Connections › Apps › Connect an app.")
    if (allowed) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
            QrScanner(onText = { text -> if (!onCode(text)) wrong = true }, modifier = Modifier.fillMaxSize())
        }
    } else {
        Muted("Allow the camera to scan the code, or paste the link below.")
    }
    if (wrong) Problem("That code isn't a store's connect code.")
    OutlinedTextField(
        value = pasted,
        onValueChange = { pasted = it; wrong = false },
        label = { Text("Or paste the link") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { if (!onCode(pasted)) wrong = true }, enabled = pasted.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text("Use this link")
    }
    TextButton(onClick = onBack) { Text("Back") }
}

@Composable
fun ConfirmScreen(code: ConnectCode, busy: Boolean, problem: String?, onConnect: () -> Unit, onBack: () -> Unit) = Page {
    Title("Connect to ${code.host}?")
    Muted("This device gets a key of its own for one store on ${code.host}. It can make payment codes and follow them, never change the store or its wallet.")
    Problem(problem)
    Button(onClick = onConnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Connecting…" else "Connect") }
    TextButton(onClick = onBack, enabled = !busy) { Text("Cancel") }
}

@Composable
fun Facts(vararg rows: Pair<String, String>) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    rows.forEach { (name, value) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted(name)
            Text(value, textAlign = TextAlign.End, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
fun ConnectedScreen(connection: Connection, onNext: () -> Unit, onWrong: () -> Unit) = Page {
    Title("Connected to ${connection.store.name}")
    Muted("Check this is the store you meant. Payments go to its wallet.")
    Facts(
        "Store" to connection.store.name,
        "Server" to connection.endpoint.substringAfter("://"),
        "Wallet" to (connection.wallet?.let { "${it.name} · ${it.addressShort}" } ?: "—"),
        "Network" to (connection.wallet?.network ?: "—"),
        "This device" to connection.deviceName,
    )
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    TextButton(onClick = onWrong) { Text("Wrong store: disconnect") }
}

@Composable
fun SquareScreen(busy: Boolean, problem: String?, onSignIn: () -> Unit, onSkip: () -> Unit) = Page {
    Title("Sign in to Square")
    Muted("When you tender a sale as \"Other: Monero\" in Square, this till finds it, shows the customer the code, and puts the receipt in it.")
    Muted("Your Square sign-in stays on this device. Monokulo never sees it, or what was bought. It lets this device read the business's payments and orders, and refund a sale that's never paid.")
    Problem(problem)
    Button(onClick = onSignIn, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Signing in…" else "Sign in to Square") }
    TextButton(onClick = onSkip, enabled = !busy) { Text("Not now: only show sales from other tills") }
}

@Composable
fun LocationsScreen(locations: List<SquareLocation>, onPick: (SquareLocation) -> Unit) = Page {
    Title("Which location is this till at?")
    if (locations.isEmpty()) Muted("Square has no active locations for this business.")
    locations.forEach { location ->
        OutlinedButton(onClick = { onPick(location) }, modifier = Modifier.fillMaxWidth()) {
            Text("${location.name} · ${location.currency}")
        }
    }
}

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

@Composable
fun TillScreen(
    storeName: String,
    running: Boolean,
    live: Boolean,
    sales: List<TillSale>,
    problem: String?,
    signInAgain: Boolean,
    onSettings: () -> Unit,
    onRefund: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onSignIn: () -> Unit,
) = Page {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Mark(36)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(storeName, fontWeight = FontWeight.SemiBold)
            val status = when {
                !running -> "Shift stopped"
                live -> "Watching for Monero sales"
                else -> "Reconnecting…"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (running && live) Paid else MaterialTheme.colorScheme.onSurfaceVariant))
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp), fontSize = 14.sp)
            }
        }
        TextButton(onClick = onSettings) { Text("Settings") }
    }
    Problem(problem)
    if (signInAgain) Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in to Square again") }
    val current = sales.firstOrNull { !it.phase.isFinal } ?: sales.firstOrNull()
    if (current == null) {
        Spacer(Modifier.height(48.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Mark(96)
            Spacer(Modifier.height(16.dp))
            Text("No sale waiting", style = MaterialTheme.typography.titleLarge)
            Muted("Tender a sale as \"Other: Monero\" in your POS and its code appears here.")
        }
    } else {
        SaleCard(current, onRefund, onDismiss)
        val rest = sales.filter { it !== current }
        if (rest.isNotEmpty()) {
            HorizontalDivider()
            Muted("Earlier")
            rest.forEach { SaleRow(it, onDismiss) }
        }
    }
}

private fun phaseText(sale: TillSale): String = when (sale.phase) {
    SalePhase.Waiting -> "Waiting for payment"
    SalePhase.Seen -> "Payment seen"
    SalePhase.Confirming -> "Confirming"
    SalePhase.Paid -> "Paid"
    SalePhase.Partial -> "Part paid"
    SalePhase.Expired -> if (sale.refunded) "Not paid · refunded" else "Not paid"
    SalePhase.DoubleSpend -> "Payment reversed"
}

@Composable
fun SaleCard(sale: TillSale, onRefund: (String) -> Unit, onDismiss: (String) -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
) {
    Text("${sale.amount} ${sale.currency}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
    Muted(listOfNotNull("${sale.xmr} XMR", sale.reference).joinToString(" · "))
    when (sale.phase) {
        SalePhase.Waiting, SalePhase.Partial -> {
            QrCode(sale.code.uri, "Payment code for ${sale.amount} ${sale.currency}", Modifier.fillMaxWidth())
            val left = secondsLeft(sale.expiresAt)
            Text("${phaseText(sale)} · ${left / 60}:${"%02d".format(left % 60)} left")
            Muted(if (sale.code.hasReceipt) "The receipt is in the code" else "Payment only: no receipt in the code")
        }
        SalePhase.Seen, SalePhase.Confirming -> {
            Text(phaseText(sale), style = MaterialTheme.typography.titleLarge)
            Muted("The customer can go: it will be confirmed shortly.")
        }
        SalePhase.Paid -> {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Paid), contentAlignment = Alignment.Center) {
                Text("✓", color = Color.White, fontSize = 40.sp)
            }
            Text("Paid", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { onDismiss(sale.orderId) }) { Text("Done") }
        }
        SalePhase.Expired, SalePhase.DoubleSpend -> {
            Text(phaseText(sale), style = MaterialTheme.typography.titleLarge)
            if (sale.found != null && !sale.refunded) {
                Muted("Square recorded this sale as paid in Monero. Refund it there so the books match.")
                Button(onClick = { onRefund(sale.orderId) }) { Text("Refund the Square sale") }
            } else if (!sale.refunded) {
                Muted("Refund this sale in your POS.")
            }
            OutlinedButton(onClick = { onDismiss(sale.orderId) }) { Text("Dismiss") }
        }
    }
}

@Composable
fun SaleRow(sale: TillSale, onDismiss: (String) -> Unit) = Row(
    Modifier.fillMaxWidth().clickable(enabled = sale.phase.isFinal) { onDismiss(sale.orderId) }.padding(vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    Text("${sale.amount} ${sale.currency}")
    Muted(phaseText(sale))
}

@Composable
fun SettingsScreen(
    connection: Connection?,
    location: SquareLocation?,
    squareSignedIn: Boolean,
    shiftOn: Boolean,
    debug: Boolean,
    squareBaseUrl: String?,
    onShift: (Boolean) -> Unit,
    onSquare: () -> Unit,
    onSignOutSquare: () -> Unit,
    onDisconnect: () -> Unit,
    onSquareBaseUrl: (String) -> Unit,
    onBack: () -> Unit,
) = Page {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Title("Settings")
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onBack) { Text("Done") }
    }
    Muted("Store")
    Facts(
        "Store" to (connection?.store?.name ?: "—"),
        "Server" to (connection?.endpoint?.substringAfter("://") ?: "—"),
        "Wallet" to (connection?.wallet?.let { "${it.name} · ${it.addressShort}" } ?: "—"),
        "Network" to (connection?.wallet?.network ?: "—"),
        "This device" to (connection?.deviceName ?: "—"),
    )
    Muted("Shift")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Watch for sales")
            Muted("Keeps checking the POS while it's on, with a notification.")
        }
        Switch(checked = shiftOn, onCheckedChange = onShift)
    }
    Muted("Wake-ups")
    Facts("Checks" to "Every 2 seconds during a shift", "Monokular Bell" to "Not set up yet")
    Muted("Square")
    if (squareSignedIn && location != null) {
        Facts("Location" to location.name, "Currency" to location.currency)
        OutlinedButton(onClick = onSignOutSquare, modifier = Modifier.fillMaxWidth()) { Text("Sign out of Square") }
    } else {
        Button(onClick = onSquare, modifier = Modifier.fillMaxWidth()) { Text("Sign in to Square") }
    }
    if (debug) {
        Muted("Developer (debug builds)")
        var base by remember { mutableStateOf(squareBaseUrl ?: "") }
        OutlinedTextField(base, { base = it }, label = { Text("Stand-in Square address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { onSquareBaseUrl(base) }, modifier = Modifier.fillMaxWidth()) { Text("Use it") }
    }
    HorizontalDivider()
    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect this device") }
    Muted("Its key stops working at once. Sales already made can still be paid.")
}
