package app.ultimate.launcher

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.ultimate.core.Liquid
import app.ultimate.core.UltimateTheme
import app.ultimate.core.gooey
import app.ultimate.core.motionOn
import app.ultimate.orb.OverlayOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where the finger is on the screen (raw screen pixels) while it is on the edge rail. */
data class EdgeDrag(val down: Boolean = false, val x: Float = 0f, val y: Float = 0f, val moved: Boolean = false)

/**
 * The letter rail on every screen. A thin liquid tab sits on the right edge over whatever app is open; put a thumb on it and slide,
 * and the window opens to the whole screen with the finger still down, the same gooey rail pours out, and that letter's apps
 * spring in beside the thumb. Let go on an app to open it; let go on the rail to keep the letter open and tap; tap the dim
 * background to put it away. The "Now" offers sit at the top. Uses the "display over other apps" permission (the same one the
 * orb uses); it is off until switched on in Home screen, and draws nothing else and reads nothing from the app underneath.
 */
class EdgeRailService : Service() {
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val owner = OverlayOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repo: LauncherRepo
    private var root: FrameLayout? = null
    private lateinit var params: WindowManager.LayoutParams

    private val drag = MutableStateFlow(EdgeDrag())
    private val expanded = MutableStateFlow(false)
    /** The app under the finger, set by the drawing (which knows where the rows are), read when the finger lifts. */
    private val hover = MutableStateFlow<String?>(null)
    private var intercept = false
    private var downX = 0f; private var downY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        owner.start()
        repo = LauncherRepo(this)
        scope.launch { repo.load() }
        runCatching { show() }.onFailure { stopSelf() }
    }

    override fun onDestroy() {
        scope.cancel()
        root?.let { v -> runCatching { wm.removeViewImmediate(v) } }; root = null
        runCatching { owner.stop() }
        super.onDestroy()
    }

    private val density get() = resources.displayMetrics.density
    private fun screen(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() } else resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private fun collapsedParams(): WindowManager.LayoutParams {
        val (sw, sh) = screen()
        val w = (24 * density).roundToInt(); val h = (sh * 0.42f).roundToInt()
        return base(w, h).apply { x = sw - w; y = (sh * 0.30f).roundToInt() }
    }

    private fun base(w: Int, h: Int) = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun expand() {
        if (expanded.value) return
        expanded.value = true
        params.x = 0; params.y = 0; params.width = WindowManager.LayoutParams.MATCH_PARENT; params.height = WindowManager.LayoutParams.MATCH_PARENT
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    private fun collapse() {
        expanded.value = false; drag.value = EdgeDrag(); hover.value = null; intercept = false
        params = collapsedParams()
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        params = collapsedParams()
        val content = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner); setViewTreeSavedStateRegistryOwner(owner); setViewTreeViewModelStoreOwner(owner)
            setContent { UltimateTheme { EdgeRailContent(repo, drag, expanded, hover, onOpen = { pkg -> repo.launch(pkg); collapse() }, onAction = { id -> repo.act(id); collapse() }, onClose = { collapse() }) } }
        }
        // The frame takes the touch for the drag that starts on the tab (raw screen coordinates survive the window growing under
        // the finger); once the finger lifts, touches go to the drawing, where rows and the background are tappable.
        val frame = object : FrameLayout(this) { override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = !expanded.value || intercept }
        frame.setViewTreeLifecycleOwner(owner); frame.setViewTreeSavedStateRegistryOwner(owner); frame.setViewTreeViewModelStoreOwner(owner)
        frame.addView(content)
        frame.contentDescription = "Letter rail. Slide along the edge to find an app."
        frame.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (expanded.value) return@setOnTouchListener false
                    intercept = true; downX = e.rawX; downY = e.rawY
                    expand()
                    drag.value = EdgeDrag(true, e.rawX, e.rawY)
                }
                MotionEvent.ACTION_MOVE -> if (intercept) drag.value = EdgeDrag(true, e.rawX, e.rawY, drag.value.moved || abs(e.rawY - downY) + abs(e.rawX - downX) > 12 * density)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (intercept) {
                    intercept = false
                    val open = hover.value
                    drag.value = drag.value.copy(down = false)
                    if (open != null && e.actionMasked == MotionEvent.ACTION_UP) { repo.launch(open); collapse() }
                }
            }
            true
        }
        wm.addView(frame, params)
        root = frame
    }

    companion object {
        private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        fun enabled(c: Context) = prefs(c).getBoolean("edge_rail", false)
        /** Turns the edge rail on or off; it needs the overlay permission, and starts only if it is granted. */
        fun set(c: Context, on: Boolean) {
            prefs(c).edit().putBoolean("edge_rail", on).apply()
            val i = Intent(c, EdgeRailService::class.java)
            if (on && Settings.canDrawOverlays(c)) runCatching { c.startService(i) } else c.stopService(i)
        }
        /** Brings it back after the app restarts, if it was on (and Android allows starting it from here). */
        fun resume(c: Context) { if (enabled(c) && Settings.canDrawOverlays(c)) runCatching { c.startService(Intent(c, EdgeRailService::class.java)) } }
    }
}

@Composable
internal fun EdgeRailContent(
    repo: LauncherRepo, dragFlow: MutableStateFlow<EdgeDrag>, expandedFlow: MutableStateFlow<Boolean>, hoverFlow: MutableStateFlow<String?>,
    onOpen: (String) -> Unit, onAction: (String) -> Unit, onClose: () -> Unit,
) {
    // The same colours and hidden apps as the home screen.
    val settings by repo.settings.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(LocalLauncherTheme provides settings.accent) {
        EdgeRailBody(repo, settings, dragFlow, expandedFlow, hoverFlow, onOpen, onAction, onClose)
    }
}

@Composable
private fun EdgeRailBody(
    repo: LauncherRepo, settings: LauncherSettings, dragFlow: MutableStateFlow<EdgeDrag>, expandedFlow: MutableStateFlow<Boolean>, hoverFlow: MutableStateFlow<String?>,
    onOpen: (String) -> Unit, onAction: (String) -> Unit, onClose: () -> Unit,
) {
    val d by dragFlow.collectAsState()
    val open by expandedFlow.collectAsState()
    val allApps by repo.apps.collectAsState()
    val apps = remember(allApps, settings.hidden) { LauncherMath.visible(allApps, settings.hidden) }
    val letters = remember(apps) { LauncherMath.letters(apps) }
    val motion = motionOn()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    if (!open) { EdgeTab(); return }

    var railTop by remember { mutableFloatStateOf(0f) }
    var railLeft by remember { mutableFloatStateOf(0f) }
    var railHeight by remember { mutableFloatStateOf(1f) }
    var panelTop by remember { mutableFloatStateOf(0f) }
    var sticky by remember { mutableStateOf<Char?>(null) }
    var nowItems by remember { mutableStateOf<List<Now>>(emptyList()) }
    LaunchedEffect(Unit) { nowItems = repo.now() }

    val fingerY = d.y - railTop
    val inset = with(density) { 22.dp.toPx() }
    val usable = (railHeight - 2 * inset).coerceAtLeast(1f)
    var held by remember { mutableStateOf<Int?>(null) }
    val rawPos = LauncherMath.position(fingerY, inset, usable, letters.size)
    val index = if (d.down) LauncherMath.indexSticky(rawPos, held, letters.size) else LauncherMath.index(rawPos, letters.size)
    androidx.compose.runtime.SideEffect { held = if (d.down) index else null }
    val current = if (d.down) letters.getOrNull(index) else sticky
    LaunchedEffect(d.down) { if (!d.down) sticky = letters.getOrNull(index) }
    LaunchedEffect(index, d.down) { if (d.down && settings.railTicks) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val wave by animateFloatAsState(if (d.down) 1f else 0f, if (motion) spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow) else spring(1f, 10_000f), label = "wave")
    val follow by animateFloatAsState(fingerY, if (motion) spring(0.45f, Spring.StiffnessMediumLow) else spring(1f, 10_000f), label = "follow")
    val pop = remember { Animatable(1f) }
    LaunchedEffect(current) { if (current != null && motion) { pop.snapTo(0.6f); pop.animateTo(1f, spring(Spring.DampingRatioHighBouncy, Spring.StiffnessMedium)) } }
    val scrim by animateFloatAsState(if (open) 0.62f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "scrim")
    val shown = current?.let { LauncherMath.appsFor(apps, it, settings.drawerSort) }.orEmpty()
    val rowPx = with(density) { 56.dp.toPx() }
    // The app under the finger once it has slid left off the rail onto the list.
    val under = if (d.down && d.x < railLeft + with(density) { 16.dp.toPx() }) shown.getOrNull(((d.y - panelTop) / rowPx).toInt().takeIf { d.y >= panelTop } ?: -1)?.pkg else null
    androidx.compose.runtime.SideEffect { hoverFlow.value = under }
    val hovered by hoverFlow.collectAsState()

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)).clickable(remember { MutableInteractionSource() }, null) { onClose() }) {
        if (settings.suggestions && nowItems.isNotEmpty()) Column(Modifier.padding(start = 24.dp, end = 120.dp, top = 64.dp)) { NowRow(repo, nowItems) { } }
        if (current != null && shown.isNotEmpty()) {
            val panelH = rowPx * shown.size
            val top = ((if (d.down) d.y else railTop + railHeight / 2) - panelH / 2).coerceIn(with(density) { 150.dp.toPx() }, (railTop + railHeight - panelH).coerceAtLeast(with(density) { 150.dp.toPx() }))
            Column(Modifier.offset { IntOffset(with(density) { 24.dp.roundToPx() }, top.roundToInt()) }.width(260.dp).onGloballyPositioned { panelTop = it.positionInRoot().y }) {
                shown.forEachIndexed { i, a -> AppRow(repo, a, highlighted = hovered == a.pkg, delayIndex = i, onOpen = { onOpen(a.pkg) }, big = false) }
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.74f).width(110.dp)
            .onGloballyPositioned { val p = it.positionInRoot(); railTop = p.y; railLeft = p.x; railHeight = it.size.height.toFloat() }) {
            RailVisual(letters, follow, wave.coerceAtLeast(if (sticky != null) 0.35f else 0f), pop.value, current, d.down || sticky != null)
        }
    }
}

/** The tab on the edge while the rail is put away: a sliver of liquid that breathes slowly so it can be found without being in the way. */
@Composable
private fun EdgeTab() {
    val th = LocalLauncherTheme.current
    val breathe = if (motionOn()) rememberInfiniteTransition(label = "tab").animateFloat(0.55f, 0.9f, infiniteRepeatable(tween(2_600, easing = LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "b").value else 0.7f
    Canvas(Modifier.fillMaxSize().gooey(6f)) {
        val w = 5.dp.toPx(); val x = size.width - w - 3.dp.toPx()
        val brush = Brush.verticalGradient(listOf(th.a.copy(alpha = breathe), th.b.copy(alpha = breathe)))
        drawRoundRect(brush, Offset(x, size.height * 0.2f), Size(w, size.height * 0.6f), CornerRadius(w))
        drawCircle(brush, w * 1.1f, Offset(x + w / 2, size.height * 0.5f))
    }
}
