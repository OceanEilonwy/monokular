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
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.doOnPreDraw
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
import app.monokular.core.SalePhase
import app.monokular.sales.TillSale
import app.monokular.sales.live
import app.monokular.ui.LocalPointOfSale
import app.monokular.ui.MonokularTheme
import app.monokular.ui.Motion
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
    /** The header's "Front Counter" (or "From Back till"), before "Open the shift". */
    val where: String = "",
    val page: PanelPage = PanelPage.Code,
)

/**
 * The bubble over other apps and the panel it opens (`docs/design.md` §9
 * "overlay"; the till tweaks' Panel over Square and The order, full
 * screen). Shown during a shift when the cashier allowed "display over
 * other apps" and didn't choose notifications only, and hidden while
 * Monokular itself is on screen. The bubble opens the panel and, tapped
 * again, lowers it; so do the panel's grabber and a tap beside it. Runs
 * on the main thread, in the till's service.
 */
class Overlay(
    private val context: Context,
    private val graph: Graph,
    private val scope: CoroutineScope,
    private val onCopy: (TillSale) -> Unit,
    private val onCopyXmr: (TillSale) -> Unit,
    private val onFullScreen: (TillSale) -> Unit,
    /** Opens Monokular on the shift (the panel's header row). */
    private val onOpenShift: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val owner = WindowOwner()
    private val state = MutableStateFlow(OverlayState())
    private val panelOpen = MutableStateFlow(false)
    private val page = MutableStateFlow<PanelPage>(PanelPage.Code)
    private val justPaid = MutableStateFlow(false)
    private var bubble: BubbleFrame? = null
    private var bubbleIn: Presence? = null
    private var panel: View? = null
    private var panelMotion: PanelMotion? = null
    /** The windows as the handoff between them sees them (M1). */
    private var windows = Windows()
    /** The grabber's speed for the next collapse, progress a second; null for none. */
    private var flung: Float? = null
    /** The free bubble's centre on screen, where the docked bubble comes from and goes back to. */
    private val bubbleAt = mutableStateOf<Offset?>(null)
    /** The last usable and open shown, shown again when the configuration changes. */
    private var showing: Pair<Boolean, Boolean>? = null
    private var job: Job? = null
    private var paidTimer: Job? = null
    private var focus: String? = null
    /** Whether [focus] was live at the last look, to see it turn paid. */
    private var focusLive = false

    /** Whether the person allowed Monokular to draw over other apps. */
    fun allowed(): Boolean = Settings.canDrawOverlays(context)

    fun start() {
        owner.start()
        job = scope.launch {
            val panel = combine(panelOpen, page) { open, page -> open to page }
            combine(graph.runner.state, graph.appVisible, graph.bubbleWanted, panel, justPaid) { runner, visible, wanted, (open, shown), paid ->
                val sales = runner.till.sales
                val previous = focus
                // The panel's own sale just paid: it stays up, paid, before the focus moves on, whichever of
                // this and the service's paid() sees the payment first.
                val landed = Panel.landed(sales, previous, focusLive)
                if (landed) paid()
                focus = Bubble.focus(sales, focus, paid || landed)
                val sale = sales.firstOrNull { it.orderId == focus }
                focusLive = sale?.live == true
                // The whole order is about the sale whose code is up: another sale, or this one ending, brings the code back.
                if (focus != previous || sale?.live != true || sale.receipt == null) page.value = PanelPage.Code
                val where = sale?.let { Panel.where(it, graph.stores.placeName(it)) }.orEmpty()
                state.value = OverlayState(Bubble.look(sales, paid || landed), sale, where, if (sale?.live == true) shown else PanelPage.Code)
                val usable = wanted && runner.running && !runner.disconnected && !visible && allowed()
                usable to (open && sale != null)
            }.collect { (usable, open) -> show(usable, open) }
        }
    }

    fun stop() {
        job?.cancel()
        paidTimer?.cancel()
        // The service is going: no exits to wait for.
        bubble?.let { runCatching { wm.removeViewImmediate(it) } }
        panel?.let { runCatching { wm.removeViewImmediate(it) } }
        bubble = null
        bubbleIn = null
        panel = null
        panelMotion = null
        windows = Windows()
        owner.stop()
    }

    /** A sale was just paid: the bubble is green, and the panel shows it paid, for a few seconds. */
    fun paid() {
        paidTimer?.cancel()
        justPaid.value = true
        paidTimer = scope.launch {
            delay(Motion.Dwell.PAID_MS)
            justPaid.value = false
        }
    }

    /** The notification's "Show code": opens the panel; false when the overlay can't be shown. */
    fun showCode(): Boolean {
        if (!graph.bubbleWanted.value || !allowed()) return false
        panelOpen.value = true
        return true
    }

    /** The screen changed under the windows (a fold opened, split screen): the panel's kind is judged again. */
    fun configurationChanged() {
        // Where the free bubble was is somewhere else on a turned or unfolded screen: the panel collapses into its dock.
        if (bubble == null) bubbleAt.value = null
        showing?.let { (usable, open) -> show(usable, open) }
    }

    /** Whether the panel floats: by the screen's smallest width, the same whichever way a phone is held. */
    private fun wide(): Boolean = Panel.wide(context.resources.configuration.smallestScreenWidthDp)

    private fun show(usable: Boolean, open: Boolean) {
        showing = usable to open
        on(WindowEvent.Want(usable, open, wide()))
        if (!usable) lower()
    }

    /** Moves the windows on by [event], in the handoff's order. */
    private fun on(event: WindowEvent) {
        val step = Handoff.next(windows, event)
        windows = step.windows
        for (act in step.acts) {
            when (act) {
                WindowAct.AddBubble -> addBubble(atRest = false)
                WindowAct.AddBubbleAtRest -> addBubble(atRest = true)
                WindowAct.ShowBubble -> bubbleIn?.enter()
                WindowAct.HideBubble -> bubbleIn?.exit { on(WindowEvent.BubbleLeft) }
                WindowAct.RemoveBubble -> removeBubble()
                WindowAct.AddPanel -> addPanel(windows.wide, open = false)
                WindowAct.AddPanelOpen -> addPanel(windows.wide, open = true)
                WindowAct.OpenPanel -> panelMotion?.raise?.enter()
                WindowAct.ClosePanel -> panelMotion?.raise?.exit(flung) { on(WindowEvent.PanelLanded) }
                WindowAct.RemovePanel -> removePanel()
            }
        }
        flung = null
    }

    /** Lowers the panel at the grabber's [velocity] (progress a second). */
    private fun fling(velocity: Float) {
        flung = velocity
        lower()
    }

    /** Lowers the panel to the bubble, its code face up for next time. */
    private fun lower() {
        panelOpen.value = false
        page.value = PanelPage.Code
    }

    private fun compose(content: @androidx.compose.runtime.Composable () -> Unit) = ComposeView(context).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setContent {
            MonokularTheme { CompositionLocalProvider(LocalPointOfSale provides graph.stores.pointOfSale) { content() } }
        }
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

    /** Adds the free bubble's window: growing in, or [atRest] where the docked bubble just landed. */
    private fun addBubble(atRest: Boolean) {
        if (bubble != null) return
        val prefs = graph.stores
        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchThrough = true).apply {
            gravity = Gravity.TOP or if (prefs.bubbleOnLeft) Gravity.START else Gravity.END
            x = 0
            y = prefs.bubbleY ?: (context.resources.displayMetrics.heightPixels / 3)
        }
        val presence = Presence(if (atRest) 1f else 0f)
        // On a tablet the bubble stays up beside the floating panel: a second tap lowers it.
        lateinit var frame: BubbleFrame
        frame = BubbleFrame(context, wm, params, onTap = { if (panelOpen.value) lower() else panelOpen.value = true }) { left, y ->
            prefs.bubbleOnLeft = left
            prefs.bubbleY = y
            frame.doOnPreDraw { locate(frame) }
        }
        frame.setViewTreeLifecycleOwner(owner)
        frame.setViewTreeSavedStateRegistryOwner(owner)
        frame.addView(
            compose {
                presence.Attach()
                val s by state.collectAsStateWithLifecycle()
                SideEffect { frame.describe(Bubble.label(s.look)) }
                // Appearing and going, the circle scales and fades inside its window (M4).
                Box(
                    Modifier.graphicsLayer {
                        val p = presence.value
                        alpha = p
                        scaleX = 0.6f + 0.4f * p
                        scaleY = scaleX
                    },
                ) { BubbleFace(s.look, phase = s.sale?.phase ?: SalePhase.Waiting) }
            }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS },
        )
        runCatching { wm.addView(frame, params) }.onSuccess {
            bubble = frame
            bubbleIn = presence
            if (!atRest) presence.enter()
            frame.doOnPreDraw {
                locate(frame)
                frame.post { if (bubble === frame) on(WindowEvent.BubbleDrawn) }
            }
        }
    }

    /** Notes where the free bubble's centre is on screen. */
    private fun locate(frame: View) {
        val at = IntArray(2).also(frame::getLocationOnScreen)
        bubbleAt.value = Offset(at[0] + frame.width / 2f, at[1] + frame.height / 2f)
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleIn = null
    }

    /** Adds the panel's window at progress 0 (it grows once drawn), or already [open] when its kind was swapped. */
    private fun addPanel(wide: Boolean, open: Boolean) {
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
            overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, touchThrough = false).apply {
                // Edge to edge, under the status and navigation bars and round a cutout, as the app's screens are:
                // the panel and The order pad themselves with the safe drawing insets.
                if (Build.VERSION.SDK_INT >= 30) {
                    fitInsetsTypes = 0
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        // One set of actions for the window's life, outside its content: a new state hands the panel the same ones.
        val actions = PanelActions(
            onLower = ::lower,
            onFlung = ::fling,
            onOpenShift = { lower(); onOpenShift() },
            onOrder = { page.value = PanelPage.Order },
            onBackToCode = { page.value = PanelPage.Code },
            onCopy = onCopy,
            onCopyXmr = onCopyXmr,
            onFullScreen = { lower(); onFullScreen(it) },
        )
        val motion = PanelMotion(if (open) 1f else 0f, bubbleAt)
        val view = compose {
            motion.raise.Attach()
            val s by state.collectAsStateWithLifecycle()
            val sale = s.sale ?: return@compose
            if (wide) FloatingPanel(sale, s.where, s.page, actions, motion) else PhonePanel(sale, s.where, s.look, s.page, actions, motion)
        }
        runCatching { wm.addView(view, params) }.onSuccess {
            panel = view
            panelMotion = motion
            // The free bubble goes once a frame shows the docked one where it is, never before (amendment 6).
            view.afterPlaced({ wide || motion.placed }) { if (panel === view) on(WindowEvent.PanelDrawn) }
        }
    }

    private fun removePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelMotion = null
    }
}

/**
 * Calls [then] after the first frame drawn once [placed] holds: a pre-draw
 * listener that sees it, asks for one more frame (the one drawn with what
 * was placed), and removes itself at that frame's pre-draw. Frames, not a
 * delay: under reduced motion nothing else would ask for the second one.
 */
private fun View.afterPlaced(placed: () -> Boolean, then: () -> Unit) {
    val listener = object : ViewTreeObserver.OnPreDrawListener {
        private var seen = false

        override fun onPreDraw(): Boolean {
            if (seen) {
                viewTreeObserver.removeOnPreDrawListener(this)
                post(then)
            } else if (placed()) {
                seen = true
                invalidate()
            }
            return true
        }
    }
    viewTreeObserver.addOnPreDrawListener(listener)
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
 * The bubble's window: a tap opens the panel (or lowers it again), a drag
 * moves it, and letting go snaps it to the nearer side. Touches are
 * handled here, in screen coordinates, because the window moves under the
 * finger. TalkBack hears one button, named for the bubble's state.
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
