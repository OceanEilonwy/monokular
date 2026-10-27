package app.monokular

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.monokular.core.SalePhase
import app.monokular.notify.SaleNotifications
import app.monokular.overlay.Overlay
import app.monokular.sales.TillSale
import app.monokular.sales.live
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the till running during a shift (`docs/design.md` §3, §7): a
 * foreground service whose ongoing notification is the current sale, and
 * which holds the bubble over other apps.
 */
class TillService : LifecycleService() {
    private val graph get() = (application as MonokularApp).graph
    private lateinit var notes: SaleNotifications
    private lateinit var overlay: Overlay

    override fun onCreate() {
        super.onCreate()
        notes = SaleNotifications(this).also { it.channels() }
        ServiceCompat.startForeground(
            this,
            SaleNotifications.ONGOING_ID,
            watching(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        graph.runner.start()
        overlay = Overlay(
            this, graph, lifecycleScope,
            onCopy = { SaleNotifications.copyAddress(this, it.address) },
            onCopyXmr = { SaleNotifications.copyXmr(this, it.xmr) },
            onFullScreen = ::openSale,
            onOpenShift = ::openShift,
        )
        overlay.start()
        val changes = PhaseChanges()
        var posted: List<Any?>? = null
        val alerted = mutableSetOf<String>()
        lifecycleScope.launch {
            combine(graph.runner.state, graph.permissions, graph.store.now) { state, _, _ -> state }.collect { state ->
                val sales = state.till.sales
                for (sale in changes.next(sales)) {
                    when (sale.phase) {
                        SalePhase.Paid -> {
                            notes.paid(sale, quiet = graph.appVisible.value)
                            overlay.paid()
                        }
                        SalePhase.Expired, SalePhase.Cancelled, SalePhase.DoubleSpend -> notes.unpaid(sale, quiet = graph.appVisible.value)
                        else -> Unit
                    }
                }
                val current = sales.firstOrNull { it.live }
                val key = listOf(current?.orderId, current?.phase, notes.allowed(), graph.store.now.value.store?.name)
                if (key == posted) return@collect
                posted = key
                if (current == null) {
                    notes.ongoing(watching())
                } else {
                    // A heads-up once per sale, when it first appears; not again when an older one comes back to the top.
                    val alert = current.phase == SalePhase.Waiting && alerted.add(current.orderId)
                    notes.ongoing(notes.sale(current, graph.stores.location?.name, alert))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_SHOW_CODE && !overlay.showCode()) {
            graph.runner.state.value.till.sales.firstOrNull { it.live }?.let(::openSale)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        overlay.stop()
        graph.runner.stop()
        super.onDestroy()
    }

    /** Opens Monokular on the shift (the panel's header row): the sale stays live, on the bubble and in the list. */
    private fun openShift() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_SHIFT, true),
        )
    }

    private fun watching() = notes.watching(graph.store.now.value.store?.name.orEmpty(), graph.stores.location?.name)

    /** Opens Monokular full screen on [sale] (from the bubble's panel: an app showing an overlay may start activities). */
    private fun openSale(sale: TillSale) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ORDER, sale.orderId),
        )
    }

    companion object {
        const val ACTION_SHOW_CODE = "app.monokular.SHOW_CODE"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, TillService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, TillService::class.java))
    }
}
