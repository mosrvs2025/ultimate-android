package app.ultimate.orb

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.ultimate.MainActivity
import app.ultimate.UltimateApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One petal of the bloom: a tool to open (or Home, for null). */
data class OrbAction(val key: String, val label: String, val icon: ImageVector, val toolId: String?)

/**
 * The Ultimate orb: a bubble that floats over other apps. Drag it anywhere and it springs to the nearest edge; tap it and the
 * tools bloom out around it; drag it onto the ✕ to put it away; drop text on it to keep it. It breathes while work runs,
 * with the job's progress as a ring, and a card peeks out of it when something finishes.
 */
class OrbService : Service() {
    private val app get() = application as UltimateApp
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val owner = OverlayOwner()
    private val density get() = resources.displayMetrics.density
    private val orbPx get() = (60 * density).roundToInt()

    private var orbView: View? = null
    private var bloomView: View? = null
    private var peekView: View? = null
    private var targetView: View? = null
    private lateinit var orbParams: WindowManager.LayoutParams
    private var peekJob: Job? = null

    // Shared with the Compose content of every window.
    private val progress = MutableStateFlow<Float?>(null)
    private val busy = MutableStateFlow(false)
    private val nearTarget = MutableStateFlow(false)
    private val dropHover = MutableStateFlow(false)
    private val peek = MutableStateFlow<Pair<String, String?>?>(null)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        owner.start()
        showOrb()
        watchWork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_PEEK)?.let { showPeek(it, null) }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        orbView?.let { snapToEdge(it) }
    }

    override fun onDestroy() {
        scope.cancel()
        listOfNotNull(orbView, bloomView, peekView, targetView).forEach { v -> runCatching { wm.removeView(v) } }
        owner.stop()
        super.onDestroy()
    }

    private fun overlayParams(w: Int, h: Int, touchable: Boolean = true) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun compose(content: @Composable () -> Unit): ComposeView = ComposeView(this).apply {
        setViewTreeLifecycleOwner(owner); setViewTreeSavedStateRegistryOwner(owner); setViewTreeViewModelStoreOwner(owner)
        setContent(content)
    }

    private fun screen(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private val prefs get() = getSharedPreferences("orb", Context.MODE_PRIVATE)

    // ---- The orb itself ----

    @SuppressLint("ClickableViewAccessibility")
    private fun showOrb() {
        val (sw, sh) = screen()
        orbParams = overlayParams(orbPx, orbPx).apply {
            x = prefs.getInt("x", sw - orbPx - (6 * density).toInt()); y = prefs.getInt("y", (sh * 0.35f).toInt())
        }
        val content = compose { OrbFace(progress, busy, nearTarget, dropHover) }
        // A frame takes every touch itself (Compose would measure drags against a window that moves with the finger).
        val frame = object : FrameLayout(this) { override fun onInterceptTouchEvent(ev: MotionEvent?) = true }
        frame.addView(content)
        frame.setOnTouchListener(OrbTouch())
        frame.setOnDragListener { _, e -> onDrag(e) }
        frame.contentDescription = "Ultimate orb. Tap for tools, drag to move."
        wm.addView(frame, orbParams)
        orbView = frame
        val (sx, sy) = OrbMath.snap(orbParams.x, orbParams.y, orbPx, sw, sh, (6 * density).toInt(), statusBar(), navBar())
        orbParams.x = sx; orbParams.y = sy; wm.updateViewLayout(frame, orbParams)
    }

    private fun statusBar() = resources.getIdentifier("status_bar_height", "dimen", "android").takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: (24 * density).toInt()
    private fun navBar() = resources.getIdentifier("navigation_bar_height", "dimen", "android").takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: 0

    private inner class OrbTouch : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        private var dragging = false
        private val slop = ViewConfiguration.get(this@OrbService).scaledTouchSlop

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = orbParams.x; startY = orbParams.y; dragging = false; v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start() }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) { dragging = true; showTarget(); v.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }
                    if (dragging) {
                        val (tx, ty) = targetCenter()
                        val near = OrbMath.overTarget(e.rawX, e.rawY, tx, ty, 90 * density)
                        nearTarget.value = near
                        // Near the ✕ the orb is pulled onto it, so letting go there is easy.
                        if (near) { orbParams.x = (tx - orbPx / 2).toInt(); orbParams.y = (ty - orbPx / 2).toInt() }
                        else { orbParams.x = startX + dx.toInt(); orbParams.y = startY + dy.toInt() }
                        wm.updateViewLayout(v, orbParams)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator(3f)).start()
                    hideTarget()
                    if (dragging) {
                        if (nearTarget.value) { nearTarget.value = false; putAway(); return true }
                        snapToEdge(v)
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) bloom()
                }
            }
            return true
        }
    }

    private fun snapToEdge(v: View) {
        val (sw, sh) = screen()
        val (sx, sy) = OrbMath.snap(orbParams.x, orbParams.y, orbPx, sw, sh, (6 * density).toInt(), statusBar(), navBar())
        val fromX = orbParams.x; val fromY = orbParams.y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420; interpolator = OvershootInterpolator(1.6f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                orbParams.x = (fromX + (sx - fromX) * f).toInt(); orbParams.y = (fromY + (sy - fromY) * f).toInt()
                if (v.isAttachedToWindow) wm.updateViewLayout(v, orbParams)
            }
        }.start()
        prefs.edit().putInt("x", sx).putInt("y", sy).apply()
    }

    private fun putAway() {
        prefs.edit().putBoolean("on", false).apply()
        stopSelf()
    }

    // ---- The ✕ that appears while dragging ----

    private fun targetCenter(): Pair<Float, Float> { val (sw, sh) = screen(); return sw / 2f to sh - navBar() - 90 * density }

    private fun showTarget() {
        if (targetView != null) return
        val size = (72 * density).toInt()
        val (cx, cy) = targetCenter()
        val p = overlayParams(size, size, touchable = false).apply { x = (cx - size / 2).toInt(); y = (cy - size / 2).toInt() }
        targetView = compose { DismissTarget(nearTarget) }.also { wm.addView(it, p) }
    }

    private fun hideTarget() { targetView?.let { runCatching { wm.removeView(it) } }; targetView = null }

    // ---- Bloom: the tools around the orb ----

    private fun actions(): List<OrbAction> {
        val ids = app.tools.map { it.id }.toSet()
        val all = listOf(
            OrbAction("home", "Ultimate", Icons.Rounded.AutoAwesome, null),
            OrbAction("clips", "Clipboard", app.tools.firstOrNull { it.id == "clips" }?.icon ?: Icons.Rounded.AutoAwesome, "clips"),
            OrbAction("recorder", "Record", app.tools.firstOrNull { it.id == "recorder" }?.icon ?: Icons.Rounded.AutoAwesome, "recorder"),
            OrbAction("terminal", "Terminal", app.tools.firstOrNull { it.id == "terminal" }?.icon ?: Icons.Rounded.AutoAwesome, "terminal"),
            OrbAction("storage", "Storage", app.tools.firstOrNull { it.id == "storage" }?.icon ?: Icons.Rounded.AutoAwesome, "storage"),
            OrbAction("send", "Computer", app.tools.firstOrNull { it.id == "send" }?.icon ?: Icons.Rounded.AutoAwesome, "send"),
            OrbAction("inbox", "Notifications", app.tools.firstOrNull { it.id == "inbox" }?.icon ?: Icons.Rounded.AutoAwesome, "inbox"),
            OrbAction("cleanup", "Clean up", app.tools.firstOrNull { it.id == "cleanup" }?.icon ?: Icons.Rounded.AutoAwesome, "cleanup"),
        ).filter { it.toolId == null || it.toolId in ids }
        // Home stays first; the rest learn from what you tap most.
        return listOf(all.first()) + OrbMath.byUse(all.drop(1), { it.key }) { prefs.getInt("use.$it", 0) }.take(6)
    }

    private fun bloom() {
        if (bloomView != null) return
        val (sw, sh) = screen()
        val cx = orbParams.x + orbPx / 2f; val cy = orbParams.y + orbPx / 2f
        val list = actions()
        val spots = OrbMath.bloom(cx, cy, list.size, 118 * density, sw.toFloat(), sh.toFloat(), 44 * density)
        val open = mutableStateOf(true)
        val origin = MutableStateFlow(IntOffset.Zero)
        lateinit var view: View
        fun close(then: (() -> Unit)? = null) {
            open.value = false
            scope.launch { delay(230); runCatching { wm.removeView(view) }; bloomView = null; orbView?.visibility = View.VISIBLE; then?.invoke() }
        }
        view = compose {
            Bloom(open.value, cx, cy, list, spots, orbPx, origin, onPick = { a ->
                prefs.edit().putInt("use.${a.key}", prefs.getInt("use.${a.key}", 0) + 1).apply()
                close { launch(a.toolId) }
            }) { close() }
        }
        orbView?.visibility = View.INVISIBLE
        wm.addView(view, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT))
        // The orb's position is in screen pixels; the bloom draws in its window's, which may not start at the screen's corner.
        view.post { IntArray(2).also { view.getLocationOnScreen(it); origin.value = IntOffset(it[0], it[1]) } }
        bloomView = view
    }

    private fun launch(toolId: String?) {
        // Holding "display over other apps" lets the orb open Ultimate from the background.
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { toolId?.let { putExtra(MainActivity.EXTRA_TOOL, it) } })
    }

    // ---- Peeks: a card that slides out of the orb ----

    private fun showPeek(text: String, toolId: String?) {
        peekJob?.cancel()
        peek.value = text to toolId
        if (peekView == null) {
            val (sw, _) = screen()
            val onLeft = orbParams.x + orbPx / 2 < sw / 2
            val w = (240 * density).toInt()
            val p = overlayParams(w, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                x = if (onLeft) orbParams.x + orbPx + (6 * density).toInt() else orbParams.x - w - (6 * density).toInt()
                y = orbParams.y + (6 * density).toInt()
            }
            peekView = compose { PeekCard(peek, onLeft) { t -> peek.value = null; launch(t) } }.also { wm.addView(it, p) }
        }
        peekJob = scope.launch {
            delay(4200); peek.value = null; delay(300)
            peekView?.let { runCatching { wm.removeView(it) } }; peekView = null
        }
    }

    private fun watchWork() {
        val sources = app.work.sources
        if (sources.isEmpty()) return
        val last = HashMap<String, Boolean>()
        scope.launch {
            combine(sources.map { s -> s.work }) { it.toList() }.collect { snaps ->
                val running = snaps.filter { it.active }
                busy.value = running.isNotEmpty()
                progress.value = running.mapNotNull { it.percent }.takeIf { it.isNotEmpty() }?.average()?.toFloat()?.div(100f)
                sources.zip(snaps).forEach { (src, w) ->
                    if (last[src.workId] == true && !w.active) showPeek(w.doneTitle ?: (if (w.failed) "${src.workTitle} stopped" else "${src.workTitle} finished"), src.workId)
                    last[src.workId] = w.active
                }
            }
        }
    }

    // ---- Drop text on the orb to keep it ----

    private fun onDrag(e: DragEvent): Boolean = when (e.action) {
        DragEvent.ACTION_DRAG_STARTED -> e.clipDescription?.let { it.hasMimeType("text/*") } ?: false
        DragEvent.ACTION_DRAG_ENTERED -> { dropHover.value = true; true }
        DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { dropHover.value = false; true }
        DragEvent.ACTION_DROP -> {
            dropHover.value = false
            val text = e.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (text.isNotBlank()) {
                app.clips.add(text, "Orb")
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ultimate", text))
                showPeek("Kept and copied: " + text.lines().first().take(60), "clips")
            }
            true
        }
        else -> true
    }

    companion object {
        const val EXTRA_PEEK = "peek"
        fun on(context: Context) = context.getSharedPreferences("orb", Context.MODE_PRIVATE).getBoolean("on", false)
        fun set(context: Context, on: Boolean) {
            context.getSharedPreferences("orb", Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
            val i = Intent(context, OrbService::class.java)
            if (on) runCatching { context.startService(i) } else context.stopService(i)
        }
        /** Starts the orb if it was left on; called whenever Ultimate comes to the front. */
        fun resume(context: Context) { if (on(context) && Settings.canDrawOverlays(context)) runCatching { context.startService(Intent(context, OrbService::class.java)) } }
    }
}

/** Lifecycle and saved state for Compose views that live in overlay windows rather than an activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    fun start() { saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
    fun stop() { registry.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
}

private val ORB_BRUSH = Brush.linearGradient(listOf(Color(0xFF8E7CFF), Color(0xFF4FC3F7)))

@Composable
private fun OrbFace(progress: MutableStateFlow<Float?>, busy: MutableStateFlow<Boolean>, near: MutableStateFlow<Boolean>, hover: MutableStateFlow<Boolean>) {
    val p by progress.collectAsState()
    val working by busy.collectAsState()
    val overTarget by near.collectAsState()
    val dropping by hover.collectAsState()
    val pulse = rememberInfiniteTransition(label = "breath")
    val breath by pulse.animateFloat(1f, if (working) 1.07f else 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val scale by animateFloatAsState(if (overTarget) 0.75f else if (dropping) 1.12f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "s")
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
    Box(Modifier.fillMaxSize().padding(4.dp).graphicsLayer { scaleX = scale * breath * appear.value; scaleY = scale * breath * appear.value }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().shadow(10.dp, CircleShape).clip(CircleShape).background(ORB_BRUSH), contentAlignment = Alignment.Center) {
            Icon(if (overTarget) Icons.Rounded.Close else Icons.Rounded.AutoAwesome, null, Modifier.size(26.dp), tint = Color.White)
        }
        p?.let { f ->
            Canvas(Modifier.fillMaxSize()) {
                val w = 3.dp.toPx()
                drawArc(Color.White.copy(alpha = 0.3f), 0f, 360f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
                drawArc(Color.White, -90f, 360f * f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun DismissTarget(near: MutableStateFlow<Boolean>) {
    val over by near.collectAsState()
    val scale by animateFloatAsState(if (over) 1.25f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "t")
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(Spring.DampingRatioMediumBouncy)) }
    Box(Modifier.fillMaxSize().padding(8.dp).graphicsLayer { scaleX = scale * appear.value; scaleY = scale * appear.value; alpha = appear.value }
        .clip(CircleShape).background(if (over) Color(0xFFE53935) else Color(0xCC202024)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Close, "Put the orb away", tint = Color.White)
    }
}

@Composable
private fun Bloom(
    open: Boolean, cx: Float, cy: Float, actions: List<OrbAction>, spots: List<Pair<Float, Float>>, orbPx: Int,
    windowOrigin: MutableStateFlow<IntOffset>, onPick: (OrbAction) -> Unit, onClose: () -> Unit,
) {
    val scrim by animateFloatAsState(if (open) 0.42f else 0f, tween(220), label = "scrim")
    val d = LocalDensity.current
    val origin by windowOrigin.collectAsState()
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim))
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        actions.forEachIndexed { i, a ->
            // Each petal flies out from the orb on a spring, a beat after the one before; closing pulls them back in.
            val t = remember { Animatable(0f) }
            LaunchedEffect(open) { if (open) { delay(28L * i); t.animateTo(1f, spring(0.55f, 380f)) } else t.animateTo(0f, tween(180)) }
            val (px, py) = spots[i]
            val x = cx + (px - cx) * t.value - origin.x; val y = cy + (py - cy) * t.value - origin.y
            val sizePx = with(d) { 54.dp.toPx() }
            Column(
                Modifier.offset { IntOffset((x - sizePx).roundToInt(), (y - sizePx / 2).roundToInt()) }.width(with(d) { (sizePx * 2).toDp() })
                    .graphicsLayer { alpha = t.value.coerceIn(0f, 1f); scaleX = 0.4f + 0.6f * t.value; scaleY = 0.4f + 0.6f * t.value },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(54.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(Color(0xFF26262C)).clickable { onPick(a) }, contentAlignment = Alignment.Center) {
                    Icon(a.icon, a.label, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Text(a.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 1.dp))
            }
        }
        // The orb stays where it was, turning into a close button.
        val spin by animateFloatAsState(if (open) 135f else 0f, spring(Spring.DampingRatioMediumBouncy), label = "spin")
        Box(Modifier.offset { IntOffset((cx - orbPx / 2f - origin.x).roundToInt(), (cy - orbPx / 2f - origin.y).roundToInt()) }.size(with(d) { orbPx.toDp() }).padding(4.dp)
            .shadow(10.dp, CircleShape).clip(CircleShape).background(ORB_BRUSH).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Close", Modifier.size(26.dp).graphicsLayer { rotationZ = spin - 135f }, tint = Color.White)
        }
    }
}

@Composable
private fun PeekCard(peek: MutableStateFlow<Pair<String, String?>?>, fromLeft: Boolean, onOpen: (String?) -> Unit) {
    val now by peek.collectAsState()
    var shown by remember { mutableStateOf<Pair<String, String?>?>(null) }
    if (now != null) shown = now
    val t by animateFloatAsState(if (now != null) 1f else 0f, spring(0.6f, 300f), label = "peek")
    Row(Modifier.graphicsLayer { alpha = t; translationX = (1 - t) * (if (fromLeft) -60f else 60f) * density; scaleX = 0.85f + 0.15f * t; scaleY = 0.85f + 0.15f * t }
        .widthIn(max = 240.dp).shadow(8.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Color(0xFF26262C))
        .clickable { onOpen(shown?.second) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(ORB_BRUSH)); Spacer(Modifier.width(10.dp))
        Text(shown?.first.orEmpty(), color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
