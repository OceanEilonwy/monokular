package app.monokular.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.monokular.SaleText
import app.monokular.core.SalePhase
import app.monokular.sales.TillSale
import app.monokular.sales.live

/** The line under a code's amount: what's left of it, or how far the payment has got. */
object CodeText {
    /** "14:52 left" while the code counts down (waiting, or part paid); null once a payment is on its way. */
    fun left(sale: TillSale, secondsLeft: Long): String? = when (sale.phase) {
        SalePhase.Waiting, SalePhase.Partial -> "${SaleText.clock(secondsLeft)} left"
        else -> null
    }

    /** "0.05552 XMR · 14:52 left", or "0.05552 XMR · Seen on the network" once seen. */
    fun under(sale: TillSale, secondsLeft: Long): String = "${SaleText.xmr(sale)} · ${left(sale, secondsLeft) ?: SaleText.status(sale)}"
}

/**
 * The code enlarged (the design's CodeB, note s3): the whole screen white
 * whatever the theme, at full brightness and kept on, the code as large as
 * the screen allows. A tap anywhere, the shrink button or Android's back
 * goes back ([onBack]); so does the sale stopping being live (paid, ended,
 * closed), where the sale's own page says what happened.
 */
@Composable
fun CodeScreen(sale: TillSale, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    if (!sale.live) LaunchedEffect(sale.orderId) { onBack() }
    KeepScreenOn()
    FullBrightness()
    val now = rememberNowSeconds()
    // The design's light roles, fixed: this screen is white in both themes.
    val ink = LightTokens.ink
    val muted = LightTokens.muted
    CompositionLocalProvider(LocalContentColor provides ink) {
        Column(
            Modifier.fillMaxSize().background(Fixed.White)
                .clickable(onClickLabel = "Shrink the code", indication = null, interactionSource = null, onClick = onBack)
                .safeDrawingPadding()
                .padding(start = 6.dp, end = 6.dp, top = 20.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(SaleText.amount(sale), style = display(30, -0.5), color = ink)
                    Countdown(now, sale.expiresAt, fontSize = 15.sp, color = muted) { CodeText.under(sale, it) }
                }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(LightTokens.inset).clickable(role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Icon(MkIcons.Shrink, contentDescription = "Shrink the code", tint = ink, modifier = Modifier.size(20.dp)) }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrCode(
                    sale.code.uri,
                    "Payment code, tap to shrink",
                    Modifier.size(min(maxWidth, maxHeight)),
                    padding = 12.dp,
                    radius = 0.dp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhaseRing(sale.phase, 26.dp, track = Fixed.Track, fill = Fixed.Accent)
                Text("Scan with any Monero wallet", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink)
            }
            Text("Tap anywhere to shrink", fontSize = 13.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Keeps the screen on while it's shown: a code a customer is about to scan mustn't go dark. */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * The window at full brightness, with dark status bar icons over the white,
 * while shown; both as they were after.
 */
@Composable
private fun FullBrightness() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.activity()?.window ?: return@DisposableEffect onDispose { }
        val brightness = window.attributes.screenBrightness
        val bars = WindowCompat.getInsetsController(window, view)
        val lightStatus = bars.isAppearanceLightStatusBars
        val lightNavigation = bars.isAppearanceLightNavigationBars
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        bars.isAppearanceLightStatusBars = true
        bars.isAppearanceLightNavigationBars = true
        onDispose {
            window.attributes = window.attributes.apply { screenBrightness = brightness }
            bars.isAppearanceLightStatusBars = lightStatus
            bars.isAppearanceLightNavigationBars = lightNavigation
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
