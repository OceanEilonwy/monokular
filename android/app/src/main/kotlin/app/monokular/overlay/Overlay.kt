package app.monokular.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.monokular.Bubble
import app.monokular.BubbleLook
import app.monokular.Graph
import app.monokular.SaleText
import app.monokular.sales.TillSale
import app.monokular.ui.MonokularTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/** What the overlay's windows show. */
data class OverlayState(
    val look: BubbleLook = BubbleLook.Idle,
    /** The sale the bubble and the panel are about: the one waiting, or the one that just finished. */
    val sale: TillSale? = null,
    val place: String = "",
)

/**
 * The bubble over other apps and the panel it opens (`docs/design.md` §9
 * "overlay"; the design's AndIdle, AndSale, AndPanel, AndPaid and
 * AndTabFloat). Shown during a shift when the cashier allowed "display
 * over other apps" and didn't choose notifications only, and hidden while
 * Monokular itself is on screen. Runs on the main thread, in the till's
 * service.
 */
class Overlay(
    private val context: Context,
    private val graph: Graph,
    private val scope: CoroutineScope,
    private val onCopy: (TillSale) -> Unit,
    private val onFullScreen: (TillSale) -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val owner = WindowOwner()
    private val state = MutableStateFlow(OverlayState())
    private val panelOpen = MutableStateFlow(false)
    private val justPaid = MutableStateFlow(false)
    private var bubble: BubbleFrame? = null
    private var panel: View? = null
    private var job: Job? = null
    private var paidTimer: Job? = null
    private var focus: String? = null

    /** Whether the person allowed Monokular to draw over other apps. */
    fun allowed(): Boolean = Settings.canDrawOverlays(context)

    fun start() {
        owner.start()
        job = scope.launch {
            combine(graph.runner.state, graph.appVisible, graph.bubbleWanted, panelOpen, justPaid) { runner, visible, wanted, open, paid ->
                val sales = runner.till.sales
                focus = Bubble.focus(sales, focus, paid)
                val sale = sales.firstOrNull { it.orderId == focus }
                val look = Bubble.look(sales, paid)
                state.value = OverlayState(look, sale, sale?.let { SaleText.place(it, graph.stores.location?.name) }.orEmpty())
                val usable = wanted && runner.running && !runner.disconnected && !visible && allowed()
                Triple(usable, open && sale != null, wide())
            }.collect { (usable, open, wide) -> show(usable, open, wide) }
        }
    }

    fun stop() {
        job?.cancel()
        paidTimer?.cancel()
        removeBubble()
        removePanel()
        owner.stop()
    }

    /** A sale was just paid: the bubble is green for a few seconds. */
    fun paid() {
        paidTimer?.cancel()
        justPaid.value = true
        paidTimer = scope.launch {
            delay(Bubble.PAID_MS)
            justPaid.value = false
        }
    }

    /** The notification's "Show code": opens the panel; false when the overlay can't be shown. */
    fun showCode(): Boolean {
        if (!graph.bubbleWanted.value || !allowed()) return false
        panelOpen.value = true
        return true
    }

    private fun wide(): Boolean {
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels / metrics.density >= 600
    }

    private fun show(usable: Boolean, open: Boolean, wide: Boolean) {
        val panelShown = usable && open
        // On a phone the panel docks the bubble at its top; on a tablet the bubble stays where it was.
        val bubbleShown = usable && !(panelShown && !wide)
        if (bubbleShown) addBubble() else removeBubble()
        if (panelShown) addPanel(wide) else removePanel()
        if (!usable) panelOpen.value = false
    }

    private fun compose(content: @androidx.compose.runtime.Composable () -> Unit) = ComposeView(context).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setContent { MonokularTheme { content() } }
    }

    private fun overlayParams(width: Int, height: Int, touchThrough: Boolean) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else 0),
        PixelFormat.TRANSLUCENT,
    )

    private fun addBubble() {
        if (bubble != null) return
        val prefs = graph.stores
        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchThrough = true).apply {
            gravity = Gravity.TOP or if (prefs.bubbleOnLeft) Gravity.START else Gravity.END
            x = 0
            y = prefs.bubbleY ?: (context.resources.displayMetrics.heightPixels / 3)
        }
        val frame = BubbleFrame(context, wm, params, onTap = { panelOpen.value = true }) { left, y ->
            prefs.bubbleOnLeft = left
            prefs.bubbleY = y
        }
        frame.setViewTreeLifecycleOwner(owner)
        frame.setViewTreeSavedStateRegistryOwner(owner)
        frame.addView(
            compose {
                val s by state.collectAsStateWithLifecycle()
                androidx.compose.runtime.SideEffect { frame.describe(Bubble.label(s.look)) }
                BubbleFace(s.look, s.sale?.let { SaleText.amount(it) }, pillOnLeft = !frame.dockedLeft)
            }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS },
        )
        runCatching { wm.addView(frame, params) }.onSuccess { bubble = frame }
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeViewImmediate(it) } }
        bubble = null
    }

    private fun addPanel(wide: Boolean) {
        if (panel != null) return
        val density = context.resources.displayMetrics.density
        val params = if (wide) {
            overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchThrough = true).apply {
                gravity = Gravity.TOP or Gravity.END
                // The panel draws 24 dp in from its window's edge, room for its shadow.
                x = (4 * density).toInt()
                y = (8 * density).toInt()
            }
        } else {
            overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, touchThrough = false)
        }
        val minimise = { panelOpen.value = false }
        val view = compose {
            val s by state.collectAsStateWithLifecycle()
            val sale = s.sale ?: return@compose
            val copy = { onCopy(sale) }
            val full = { panelOpen.value = false; onFullScreen(sale) }
            if (wide) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(24.dp)) {
                    SalePanel(sale, s.place, wide = true, minimise, copy, full)
                }
            } else {
                PhonePanel(sale, s.place, s.look, minimise, copy, full)
            }
        }
        runCatching { wm.addView(view, params) }.onSuccess { panel = view }
    }

    private fun removePanel() {
        panel?.let { runCatching { wm.removeViewImmediate(it) } }
        panel = null
    }
}

/** The lifecycle and saved state Compose needs to run in a window that has no activity. */
private class WindowOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = saved.savedStateRegistry

    fun start() {
        if (registry.currentState != Lifecycle.State.INITIALIZED) return
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        if (registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/**
 * The bubble's window: a tap opens the panel, a drag moves it, and letting
 * go snaps it to the nearer side. Touches are handled here, in screen
 * coordinates, because the window moves under the finger. TalkBack hears
 * one button, named for the bubble's state.
 */
@SuppressLint("ViewConstructor")
private class BubbleFrame(
    context: Context,
    private val wm: WindowManager,
    private val params: WindowManager.LayoutParams,
    private val onTap: () -> Unit,
    private val onMoved: (left: Boolean, y: Int) -> Unit,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    /** Which side the window's x is measured from. */
    private var fromLeft = params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.START

    /** The side it's docked to, which the amount stays away from: changes only when it snaps. */
    var dockedLeft by mutableStateOf(fromLeft)
        private set

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun describe(label: String) {
        if (contentDescription != label) contentDescription = label
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width() else resources.displayMetrics.widthPixels

    private fun screenHeight(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height() else resources.displayMetrics.heightPixels

    /** The window's left edge in screen coordinates, whichever side it's docked to. */
    private fun absoluteX(): Int = if (fromLeft) params.x else screenWidth() - width - params.x

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                startX = absoluteX()
                startY = params.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!dragging && hypot(dx, dy) > slop) {
                    dragging = true
                    params.gravity = Gravity.TOP or Gravity.START
                    fromLeft = true
                }
                if (dragging) {
                    params.x = (startX + dx).toInt().coerceIn(0, screenWidth() - width)
                    params.y = (startY + dy).toInt().coerceIn(0, screenHeight() - height)
                    runCatching { wm.updateViewLayout(this, params) }
                }
            }
            MotionEvent.ACTION_UP -> if (dragging) snap() else performClick()
            MotionEvent.ACTION_CANCEL -> if (dragging) snap()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    /** Slides to the nearer side (the system's animation scale applies: none when animations are off). */
    private fun snap() {
        dragging = false
        val left = params.x + width / 2 < screenWidth() / 2
        val target = if (left) 0 else screenWidth() - width
        ValueAnimator.ofInt(params.x, target).apply {
            duration = (120 + abs(target - params.x) / 6).toLong().coerceAtMost(250)
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { wm.updateViewLayout(this@BubbleFrame, params) }
            }
            doOnEnd {
                params.gravity = Gravity.TOP or if (left) Gravity.START else Gravity.END
                params.x = 0
                fromLeft = left
                dockedLeft = left
                runCatching { wm.updateViewLayout(this@BubbleFrame, params) }
                onMoved(left, params.y)
            }
            start()
        }
    }

    private fun ValueAnimator.doOnEnd(block: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = block()
    })
}
