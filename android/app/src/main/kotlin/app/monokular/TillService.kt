package app.monokular

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.monokular.core.SalePhase
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the till running during a shift (`docs/design.md` §3, §7): a
 * foreground service with an ongoing notification, which becomes the
 * current sale while one is waiting.
 */
class TillService : LifecycleService() {
    private val graph get() = (application as MonokularApp).graph

    override fun onCreate() {
        super.onCreate()
        channels(this)
        ServiceCompat.startForeground(
            this,
            ONGOING_ID,
            watching(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        graph.runner.start()
        lifecycleScope.launch {
            graph.runner.state
                .map { s -> s.till.sales.firstOrNull { !it.phase.isFinal || it.phase == SalePhase.Paid } }
                .distinctUntilChanged()
                .collect { sale -> post(sale) }
        }
    }

    override fun onDestroy() {
        graph.runner.stop()
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun watching() = NotificationCompat.Builder(this, CHANNEL_SHIFT)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(getString(R.string.watching_title))
        .setContentText(graph.stores.connection?.store?.name ?: "")
        .setOngoing(true)
        .setContentIntent(openApp())
        .build()

    private fun post(sale: app.monokular.sales.TillSale?) {
        val manager = NotificationManagerCompat.from(this)
        if (sale == null) {
            runCatching { manager.notify(ONGOING_ID, watching()) }
            return
        }
        val amount = "${sale.amount} ${sale.currency}"
        val (title, text) = when (sale.phase) {
            SalePhase.Paid -> getString(R.string.sale_paid, amount) to getString(R.string.sale_paid_text, sale.xmr)
            SalePhase.Seen, SalePhase.Confirming -> getString(R.string.sale_seen, amount) to getString(R.string.sale_seen_text)
            else -> getString(R.string.sale_waiting, amount) to getString(R.string.sale_waiting_text)
        }
        val alert = sale.phase == SalePhase.Waiting || sale.phase == SalePhase.Paid
        val notification = NotificationCompat.Builder(this, if (alert) CHANNEL_SALES else CHANNEL_SHIFT)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(amount)
            .setContentIntent(openApp())
            .build()
        runCatching { manager.notify(ONGOING_ID, notification) }
    }

    companion object {
        private const val ONGOING_ID = 1
        private const val CHANNEL_SHIFT = "shift"
        private const val CHANNEL_SALES = "sales"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, TillService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, TillService::class.java))

        private fun channels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SHIFT, context.getString(R.string.channel_shift), NotificationManager.IMPORTANCE_LOW),
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SALES, context.getString(R.string.channel_sales), NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }
}
