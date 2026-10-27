package app.monokular.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import app.monokular.MainActivity
import app.monokular.R
import app.monokular.SaleText
import app.monokular.core.SalePhase
import app.monokular.sales.TillSale
import app.monokular.ui.qrBitmap

/**
 * The till's notifications (`docs/design.md` §9 "notifications"; the
 * design's AndSale, AndShade and AndPaid). One ongoing notification for
 * the shift, which becomes the current sale with its progress (Waiting,
 * Seen, Confirming, Paid) and is promoted to a Live Update on Android 16+;
 * then a short "Paid" or "No payment yet" one of its own for each sale
 * that ends.
 */
class SaleNotifications(private val context: Context) {
    private val accent = 0xFFFF6600.toInt()
    private val paidGreen = 0xFF1A7F37.toInt()

    fun channels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SHIFT, context.getString(R.string.channel_shift), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SALES, context.getString(R.string.channel_sales), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    /** Whether the cashier lets Monokular notify (Android 13+ asks). */
    fun allowed(): Boolean =
        (
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** The shift's notification with no sale: what's being watched. */
    fun watching(store: String, location: String?): Notification = NotificationCompat.Builder(context, CHANNEL_SHIFT)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setColor(accent)
        .setContentTitle(context.getString(R.string.watching_title))
        .setContentText(listOfNotNull(store.ifEmpty { null }, location).joinToString(" · "))
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(openApp(null))
        .build()

    /**
     * The shift's notification for [sale], waiting for its money. [alert]
     * makes it a heads-up (a new sale).
     */
    fun sale(sale: TillSale, location: String?, alert: Boolean): Notification {
        val showCode = showCode(sale.orderId)
        val amount = SaleText.amount(sale)
        val title = when (sale.phase) {
            SalePhase.Seen -> "Seen on the network"
            SalePhase.Confirming -> "Confirming"
            SalePhase.Partial -> "Part paid · ${SaleText.place(sale, location)}"
            else -> "New Monero sale · " + (location ?: SaleText.place(sale, location))
        }
        val detail = when (sale.phase) {
            SalePhase.Seen -> "waiting for confirmation"
            SalePhase.Confirming -> "nearly there"
            SalePhase.Partial -> "ask the customer to pay the rest"
            else -> "waiting for payment"
        }
        val text = "$amount · ${SaleText.xmr(sale)} · $detail"
        val step = SaleText.step(sale.phase)
        val style = NotificationCompat.ProgressStyle()
            .setProgressSegments(List(4) { NotificationCompat.ProgressStyle.Segment(1).setColor(accent) })
            .setStyledByProgress(true)
            .setProgress(step)
        val public = NotificationCompat.Builder(context, CHANNEL_SALES)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText("$amount · $detail")
            .build()
        val builder = NotificationCompat.Builder(context, if (alert || sale.phase == SalePhase.Waiting) CHANNEL_SALES else CHANNEL_SHIFT)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setColor(accent)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(SaleText.title(sale))
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(amount)
            .setLargeIcon(runCatching { qrBitmap(sale.code.uri, scale = 4, margin = 2) }.getOrNull())
            .setContentIntent(showCode)
            .addAction(0, "Show code", showCode)
        if (sale.phase == SalePhase.Waiting || sale.phase == SalePhase.Partial) {
            // The code's countdown, in the header.
            builder.setWhen(sale.expiresAt * 1000).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            builder.addAction(0, "Full screen", openApp(sale.orderId))
        } else {
            builder.addAction(0, "Copy address", copy(sale))
        }
        return builder.build()
    }

    /** Replaces the sale's progress once it's paid: short, and gone after a while; [quiet] (no banner) while Monokular is on screen saying so. */
    fun paid(sale: TillSale, quiet: Boolean) = post(
        sale,
        NotificationCompat.Builder(context, CHANNEL_SALES)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setColor(paidGreen)
            .setLargeIcon(tick())
            .setContentTitle("Paid · ${SaleText.title(sale)}")
            .setContentText("${SaleText.amount(sale)} · ${SaleText.xmr(sale)} received")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setTimeoutAfter(ENDED_MS)
            .setSilent(quiet)
            .setContentIntent(openApp(sale.orderId))
            .build(),
    )

    /** A sale that ended without its money: open it to refund the Square sale. */
    fun unpaid(sale: TillSale, quiet: Boolean) = post(
        sale,
        NotificationCompat.Builder(context, CHANNEL_SALES)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setColor(accent)
            .setContentTitle("${SaleText.status(sale)} · ${SaleText.title(sale)}")
            .setContentText(
                "${SaleText.amount(sale)}. " + if (sale.found != null) "Refund it in Square if the customer isn't paying." else "Refund it in Square on the till that took it.",
            )
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setSilent(quiet)
            .setContentIntent(openApp(sale.orderId))
            .build(),
    )

    /** Posts [notification] as the shift's one ongoing notification. */
    fun ongoing(notification: Notification) = notify(ONGOING_ID, notification)

    private fun post(sale: TillSale, notification: Notification) = notify(idOf(sale), notification)

    /** Android 13+ asks the cashier first; without it the till runs silently. */
    private fun notify(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    /** Opens Monokular, on [orderId]'s sale when there is one. */
    fun openApp(orderId: String?): PendingIntent = PendingIntent.getActivity(
        context,
        orderId?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (orderId != null) putExtra(MainActivity.EXTRA_ORDER, orderId) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** "Show code": the bubble's panel over whatever app is in front, or Monokular when there's no bubble. */
    fun showCode(orderId: String): PendingIntent = PendingIntent.getActivity(
        context,
        orderId.hashCode() + 1,
        Intent(context, ShowCodeActivity::class.java).putExtra(MainActivity.EXTRA_ORDER, orderId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun copy(sale: TillSale): PendingIntent = PendingIntent.getBroadcast(
        context,
        sale.orderId.hashCode(),
        Intent(context, CopyReceiver::class.java).putExtra(CopyReceiver.EXTRA_ADDRESS, sale.address),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The design's paid mark: a white tick on a green circle. */
    private fun tick(): Bitmap {
        val size = (48 * context.resources.displayMetrics.density).toInt()
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val unit = size / 24f
        canvas.drawCircle(12 * unit, 12 * unit, 11 * unit, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paidGreen })
        val tick = Path().apply {
            moveTo(7 * unit, 12.5f * unit)
            lineTo(10.2f * unit, 15.7f * unit)
            lineTo(17 * unit, 8.8f * unit)
        }
        canvas.drawPath(
            tick,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 2.2f * unit
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            },
        )
        return bitmap
    }

    companion object {
        const val ONGOING_ID = 1
        const val CHANNEL_SHIFT = "shift"
        const val CHANNEL_SALES = "sales"

        /** How long a "Paid" notification stays. */
        const val ENDED_MS = 5 * 60 * 1000L

        private fun idOf(sale: TillSale) = 1000 + (sale.orderId.hashCode() and 0x7FFFFF)

        /**
         * Copies [address] (a sale's, for the customer, or the store wallet's
         * from Settings), saying so where Android doesn't (before 13). Not
         * marked sensitive: a receiving address is for sharing, and Android's
         * preview lets the cashier check what was copied.
         */
        fun copyAddress(context: Context, address: String) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Monero address", address))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
        }

        /** Copies a sale's exact XMR amount (the panel's XMR line), for a wallet that wants it typed. */
        fun copyXmr(context: Context, xmr: String) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("XMR amount", xmr))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Amount copied", Toast.LENGTH_SHORT).show()
        }
    }
}

/** The notification's "Copy address". */
class CopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return
        SaleNotifications.copyAddress(context, address)
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
    }
}
