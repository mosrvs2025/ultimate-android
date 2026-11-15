package app.ultimate.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.core.Liquid
import app.ultimate.core.gooey
import app.ultimate.core.motionOn
import app.ultimate.mesh.DeviceFling
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dragging an app off the home screen to another of your devices. Long-press a row and pull: your connected devices
 * well up as liquid drops on the left edge, the app's icon rides under your thumb, and letting go on a drop sends it there.
 * Positions are in root coordinates, which on the home screen are the screen's.
 */
internal class FlingDrag {
    var app by mutableStateOf<HomeApp?>(null); private set
    var pos by mutableStateOf(Offset.Zero)
    var targets by mutableStateOf<List<DeviceFling.Target>>(emptyList()); private set
    val zones = mutableStateMapOf<String, Rect>()
    /** The last result, shown for a moment as a pill: "Sent Maps to Pixel 8", or why not. */
    var toast by mutableStateOf<String?>(null)

    /** Starts a drag, or returns false when this edition has no mesh (then the long-press just opens the menu). */
    fun start(a: HomeApp, at: Offset): Boolean {
        if (DeviceFling.provider == null) return false
        targets = DeviceFling.targets(); app = a; pos = at; return true
    }

    fun hot(): DeviceFling.Target? = targets.firstOrNull { t -> zones[t.id]?.inflate(36f)?.contains(pos) == true }

    /** Ends the drag and says where it was dropped (null: nowhere). */
    fun release(): Pair<HomeApp, DeviceFling.Target>? { val a = app; val t = hot(); app = null; return if (a != null && t != null) a to t else null }
    fun cancel() { app = null }
}

internal val LocalFlingDrag = staticCompositionLocalOf<FlingDrag?> { null }

/** The drops and the floating icon while a fling drag is under way, and the result pill afterwards. */
@Composable
internal fun FlingLayer(repo: LauncherRepo, drag: FlingDrag) {
    val a = drag.app
    val density = LocalDensity.current
    val shown by animateFloatAsState(if (a != null) 1f else 0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "fling")
    Box(Modifier.fillMaxSize()) {
        if (shown > 0.01f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f * shown.coerceIn(0f, 1f))))
            val hot = drag.hot()
            Column(Modifier.align(Alignment.CenterStart).padding(start = 14.dp).offset { IntOffset(((shown - 1f) * 140 * density.density).roundToInt(), 0) },
                verticalArrangement = Arrangement.spacedBy(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (drag.targets.isEmpty()) {
                    Text("No other devices on your mesh right now.\nTurn on Octopus Mesh on both phones.", color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.width(220.dp).background(Color(0xCC1A2230), RoundedCornerShape(18.dp)).padding(14.dp))
                }
                drag.targets.forEachIndexed { i, t ->
                    FlingDrop(t, hot == t, i, Modifier.onGloballyPositioned { drag.zones[t.id] = it.boundsInRoot() })
                }
            }
            if (a != null) {
                val icon = repo.icon(a.pkg)
                val half = with(density) { 30.dp.toPx() }
                Box(Modifier.offset { IntOffset((drag.pos.x - half).roundToInt(), (drag.pos.y - half * 2.2f).roundToInt()) }.size(60.dp).scale(if (hot != null) 0.8f else 1.15f)) {
                    if (icon != null) Image(icon, a.label, Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Liquid.teal, Liquid.violet)), RoundedCornerShape(18.dp)))
                }
            }
        }
        drag.toast?.let { msg ->
            LaunchedEffect(msg) { kotlinx.coroutines.delay(2_600); if (drag.toast == msg) drag.toast = null }
            Text(msg, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp, start = 24.dp, end = 24.dp)
                    .background(Brush.horizontalGradient(listOf(Liquid.teal.copy(alpha = 0.85f), Liquid.violet.copy(alpha = 0.85f))), RoundedCornerShape(50))
                    .padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}

/**
 * One device as a wobbling liquid drop. It swells and its skin ripples faster when something is held over it; drops
 * well up one after another. Shared by the home screen and the share sheet.
 */
@Composable
fun FlingDrop(t: DeviceFling.Target, hot: Boolean, index: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val motion = motionOn()
    val enter = remember(t.id) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(t.id) { if (motion) { kotlinx.coroutines.delay(70L * index); enter.animateTo(1f, spring(Spring.DampingRatioHighBouncy, Spring.StiffnessLow)) } }
    val grow by animateFloatAsState(if (hot) 1.35f else 1f, spring(Spring.DampingRatioHighBouncy, Spring.StiffnessMedium), label = "grow")
    val phase by rememberInfiniteTransition(label = "skin").animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(if (hot) 900 else 2_600, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Column(modifier.scale(enter.value.coerceAtLeast(0f)).alpha(enter.value.coerceIn(0f, 1f)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().gooey(10f)) {
                val c = Offset(size.width / 2, size.height / 2)
                val r = size.minDimension * 0.32f * grow
                val wob = if (motion) (if (hot) 0.12f else 0.06f) else 0f
                val path = Path()
                for (k in 0..48) {
                    val a = k / 48f * 2 * PI.toFloat()
                    val rr = r * (1 + wob * sin(3 * a + phase) + wob * 0.6f * cos(5 * a - phase * 1.3f))
                    val p = Offset(c.x + rr * cos(a), c.y + rr * sin(a))
                    if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(path, Brush.radialGradient(listOf(if (hot) Liquid.teal else Liquid.violet, Liquid.teal.copy(alpha = 0.7f)), c, r * 1.3f))
                // A little satellite droplet that orbits and melts back in: reads as liquid even when still.
                if (motion) drawCircle(Liquid.teal, r * 0.22f, Offset(c.x + r * 1.05f * cos(phase), c.y + r * 1.05f * sin(phase)))
            }
            Icon(when (t.kind) { "pc" -> Icons.Rounded.Computer; "tab" -> Icons.Rounded.TabletAndroid; "tv" -> Icons.Rounded.Tv; "headset" -> Icons.Rounded.ViewInAr; else -> Icons.Rounded.PhoneAndroid }, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Text(t.name, color = Color.White, fontSize = 13.sp, fontWeight = if (hot) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, lineHeight = 15.sp, modifier = Modifier.width(112.dp), textAlign = TextAlign.Center)
        if (t.near) Text("right here", color = Liquid.teal, fontSize = 11.sp)
    }
}
