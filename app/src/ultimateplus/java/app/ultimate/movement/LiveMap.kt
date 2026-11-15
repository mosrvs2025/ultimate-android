package app.ultimate.movement

import android.graphics.Matrix
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.core.motionOn
import app.ultimate.fusion.FusedPath
import app.ultimate.fusion.MapCamera
import app.ultimate.fusion.WebMercator
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Your route on a living map. Streets underneath (OpenStreetMap, once you allow it), the route glowing in the colour of
 * your pace (teal when easy, violet, amber when fast), and you as a pulsing drop with a halo as wide as the GPS's
 * uncertainty and a soft cone showing which way you're heading. Pinch to zoom, drag to move, twist to turn, double-tap to
 * snap back to following you. 3D tilts the ground away and lifts the route by the barometer's heights, so hills rise.
 */
@Composable
fun LiveMap(path: FusedPath?, fix: DoubleArray?, live: Boolean, modifier: Modifier = Modifier, scrub: Float? = null) {
    val ctx = LocalContext.current
    val tiles = remember { TileStore.get(ctx) }
    val arrived by tiles.arrived.collectAsState()
    val scope = rememberCoroutineScope()
    val motion = motionOn()
    val anchor = path?.anchor
    var allowed by remember { mutableStateOf(tiles.allowed) }

    // Camera: follows the newest point until you move the map yourself; double-tap to follow again.
    var follow by remember { mutableStateOf(true) }
    var centre by remember { mutableStateOf(0.0 to 0.0) }
    var scale by remember { mutableStateOf(0.0) } // px per metre; 0 = fit on first draw
    var bearing by remember { mutableStateOf(0.0) }
    val tilt = remember { Animatable(0f) }
    val pulse by rememberInfiniteTransition(label = "you").animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart), label = "p")

    // Where you are now: the newest GPS fix when it can be placed on the route's frame, else the route's last point.
    val youLocal: DoubleArray? = when {
        fix != null && anchor != null -> WebMercator.toLocal(fix[0], fix[1], anchor[0], anchor[1])
        path != null && path.size > 0 -> doubleArrayOf(path.e[path.size - 1], path.n[path.size - 1])
        else -> null
    }
    val heading: Double? = path?.let { p ->
        if (p.size < 6) null else { val i = p.size - 1; val j = max(0, i - 6); val dx = p.e[i] - p.e[j]; val dy = p.n[i] - p.n[j]; if (hypot(dx, dy) < 1.5) null else atan2(dx, dy) }
    }

    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF080B10))) {
        Canvas(Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, rot ->
                    if (scale <= 0) return@detectTransformGestures
                    follow = false
                    scale = (scale * zoom).coerceIn(0.05, 40.0)
                    bearing -= Math.toRadians(rot.toDouble())
                    // Pan in screen terms, turned back into ground metres (and stretched on a tilted map, where far is smaller).
                    val c = cos(bearing); val s = sin(bearing); val k = 1.0 / scale
                    val sy = 1.0 / max(0.35, cos(tilt.value.toDouble()))
                    val u = -pan.x * k; val v = pan.y * k * sy
                    centre = (centre.first + u * c + v * s) to (centre.second - u * s + v * c)
                    @Suppress("UNUSED_EXPRESSION") centroid
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { follow = true; bearing = 0.0 }) }) {
            @Suppress("UNUSED_EXPRESSION") arrived
            val p = path
            if (p == null || p.size < 1) {
                drawGrid(MapCamera(0.0, 0.0, 4.0), this)
                return@Canvas
            }
            // First draw: fit the whole route.
            if (scale <= 0.0 || (follow && !live)) {
                val minE = p.e.min(); val maxE = p.e.max(); val minN = p.n.min(); val maxN = p.n.max()
                scale = min(size.width / max(20.0, (maxE - minE) * 1.25), size.height / max(20.0, (maxN - minN) * 1.25))
                if (!live || p.size < 30) centre = (minE + maxE) / 2 to (minN + maxN) / 2
            }
            // Following: show the whole route while it fits on screen, then keep you in the middle. Glide there (a quarter of
            // the way per frame) and stop redrawing once arrived.
            if (follow && live && youLocal != null) {
                val minE = min(p.e.min(), youLocal[0]); val maxE = max(p.e.max(), youLocal[0]); val minN = min(p.n.min(), youLocal[1]); val maxN = max(p.n.max(), youLocal[1])
                val fits = (maxE - minE) * scale < size.width * 0.92 && (maxN - minN) * scale < size.height * 0.92
                val tx = if (fits) (minE + maxE) / 2 else youLocal[0]; val ty = if (fits) (minN + maxN) / 2 else youLocal[1]
                if (hypot(tx - centre.first, ty - centre.second) > 0.05) centre = (centre.first + (tx - centre.first) * 0.25) to (centre.second + (ty - centre.second) * 0.25)
            }
            val t = tilt.value.toDouble()
            val cam = MapCamera(centre.first, centre.second, scale, bearing, t, lift = 3.0)
            val cx = size.width / 2; val cy = size.height * (0.5f + 0.12f * tilt.value); val depth = size.height * 1.6
            fun pt(e: Double, n: Double, h: Double = 0.0) = cam.project(e, n, h, cx, cy, depth)

            // ---- the ground: streets when allowed, otherwise a faint grid
            var drewTiles = false
            if (anchor != null && allowed == true) {
                val corners = listOf(0f to 0f, size.width to 0f, size.width to size.height, 0f to size.height).mapNotNull { (x, y) ->
                    cam.ground(x, max(y, size.height * 0.08f * tilt.value), cx, cy, depth)
                }
                if (corners.size >= 3) {
                    val reach = 1.0 / scale * size.height * 2.5
                    val w = max(corners.minOf { it[0] }, centre.first - reach); val e = min(corners.maxOf { it[0] }, centre.first + reach)
                    val s = max(corners.minOf { it[1] }, centre.second - reach); val n = min(corners.maxOf { it[1] }, centre.second + reach)
                    val z = WebMercator.zoomFor(scale * (1.0 - 0.45 * t), anchor[0])
                    val m = Matrix(); val dst = FloatArray(8)
                    drawIntoCanvas { canvas ->
                        for (tile in WebMercator.tilesFor(w, s, e, n, anchor[0], anchor[1], z, cap = 64)) {
                            val bmp = tiles.get(tile) ?: continue
                            val cs = WebMercator.corners(tile, anchor[0], anchor[1]).mapNotNull { pt(it[0], it[1]) }
                            if (cs.size < 4) continue
                            for (i in 0 until 4) { dst[i * 2] = cs[i][0]; dst[i * 2 + 1] = cs[i][1] }
                            val sz = bmp.width.toFloat()
                            m.setPolyToPoly(floatArrayOf(0f, 0f, sz, 0f, sz, sz, 0f, sz), 0, dst, 0, 4)
                            canvas.nativeCanvas.drawBitmap(bmp, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                            drewTiles = true
                        }
                    }
                }
            }
            if (!drewTiles) drawGrid(cam, this, cx, cy, depth)
            // A soft fog towards the horizon in 3D, so the far ground fades instead of ending in a hard line.
            if (tilt.value > 0.05f) drawRect(Brush.verticalGradient(listOf(Color(0xFF080B10), Color(0x00080B10)), 0f, size.height * 0.45f), alpha = tilt.value.coerceIn(0f, 1f))

            // ---- the route
            val h = p.height
            val speeds = DoubleArray(p.size) { i -> val a = max(0, i - 3); val b = min(p.size - 1, i + 3); if (b > a) hypot(p.e[b] - p.e[a], p.n[b] - p.n[a]) / max(0.5, p.t[b] - p.t[a]) else 0.0 }
            fun paceColor(v: Double): Color { val x = ((v - 0.5) / 2.5).toFloat().coerceIn(0f, 1f); return if (x < 0.5f) lerp(Color(0xFF3DDCD0), Color(0xFF8E7CFF), x * 2) else lerp(Color(0xFF8E7CFF), Color(0xFFFFB454), (x - 0.5f) * 2) }
            val pts = Array(p.size) { i -> pt(p.e[i], p.n[i], h?.get(i) ?: 0.0) }
            // In 3D the route stands on curtains down to the ground, so its height reads at a glance.
            if (tilt.value > 0.05f && h != null) for (i in 0 until p.size step 3) { val a = pts[i] ?: continue; val g = pt(p.e[i], p.n[i]) ?: continue
                drawLine(paceColor(speeds[i]).copy(alpha = 0.18f * tilt.value), Offset(a[0], a[1]), Offset(g[0], g[1]), 2f) }
            for (pass in 0..1) for (i in 1 until p.size) {
                val a = pts[i - 1] ?: continue; val b = pts[i] ?: continue
                val col = paceColor(speeds[i]); val wpx = (if (pass == 0) 14f else 5f) * ((a[2] + b[2]) / 2).coerceIn(0.4f, 1.6f)
                drawLine(if (pass == 0) col.copy(alpha = 0.18f) else col, Offset(a[0], a[1]), Offset(b[0], b[1]), wpx, StrokeCap.Round)
            }
            pts.firstOrNull()?.let { drawCircle(Color(0xFF6BE38A), 10f, Offset(it[0], it[1])); drawCircle(Color.White, 10f, Offset(it[0], it[1]), style = Stroke(2f)) }
            if (scrub != null) { val i = (scrub * (p.size - 1)).toInt().coerceIn(0, p.size - 1); pts[i]?.let { drawCircle(Color.White, 13f, Offset(it[0], it[1])); drawCircle(Color(0xFF3DDCD0), 8f, Offset(it[0], it[1])) } }

            // ---- you: halo as wide as the GPS uncertainty, a heading cone, and a pulsing drop
            if (youLocal != null && (live || scrub == null)) {
                val yh = h?.lastOrNull() ?: 0.0
                pt(youLocal[0], youLocal[1], yh)?.let { y ->
                    val c = Offset(y[0], y[1])
                    val acc = (fix?.get(2) ?: 0.0)
                    if (acc > 0) drawCircle(Color(0x223DDCD0), (acc * scale * y[2]).toFloat().coerceIn(12f, size.minDimension), c)
                    if (heading != null) {
                        val ahead = pt(youLocal[0] + 12 * sin(heading), youLocal[1] + 12 * cos(heading), yh)
                        if (ahead != null) {
                            val dir = atan2(ahead[1] - y[1], ahead[0] - y[0]); val len = 70f
                            val cone = Path().apply {
                                moveTo(c.x, c.y); lineTo(c.x + len * cos(dir - 0.45).toFloat(), c.y + len * sin(dir - 0.45).toFloat())
                                lineTo(c.x + len * cos(dir + 0.45).toFloat(), c.y + len * sin(dir + 0.45).toFloat()); close()
                            }
                            drawPath(cone, Brush.radialGradient(listOf(Color(0x883DDCD0), Color(0x003DDCD0)), c, len))
                        }
                    }
                    if (live && motion) drawCircle(Color(0xFF3DDCD0).copy(alpha = 0.45f * (1 - pulse)), 12f + 34f * pulse, c, style = Stroke(3f))
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFC8FFF9), Color(0xFF3DDCD0)), c - Offset(3f, 3f), 16f), 11f, c)
                    drawCircle(Color.White, 11f, c, style = Stroke(2.5f))
                }
            }
        }

        // ---- controls
        Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(if (tilt.value > 0.3f) "2D" else "3D") { scope.launch { tilt.animateTo(if (tilt.value > 0.3f) 0f else 0.95f, if (motion) spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow) else spring(1f, 10_000f)) } }
            if (!follow) Chip("Follow") { follow = true; bearing = 0.0 }
        }
        if (anchor != null && allowed == true) Text("© OpenStreetMap contributors", color = Color(0x99B8C4D6), fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
        if (anchor != null && allowed == null) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).background(Color(0xE0141B26), RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text("Show streets under your route?", color = Color.White, fontSize = 14.sp)
                Text("Map pictures come from OpenStreetMap over the internet, so their server sees which area you're viewing. Kept on the phone afterwards.", color = Color(0xFF93A1B5), fontSize = 11.sp)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Show streets") { tiles.allowed = true; allowed = true }
                    Chip("No thanks") { tiles.allowed = false; allowed = false }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC1B2432)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

/** A faint ground grid (every 10 m, stronger every 50 m), projected with the camera so it recedes in 3D too. */
private fun drawGrid(cam: MapCamera, d: DrawScope, cx: Float = d.size.width / 2, cy: Float = d.size.height / 2, depth: Double = d.size.height * 1.6) {
    val step = when { cam.scale > 4 -> 10.0; cam.scale > 0.8 -> 50.0; else -> 250.0 }
    val reach = d.size.maxDimension / cam.scale * 1.6
    val e0 = Math.floor((cam.centreE - reach) / step) * step; val n0 = Math.floor((cam.centreN - reach) / step) * step
    var e = e0
    while (e <= cam.centreE + reach) {
        val a = cam.project(e, cam.centreN - reach, 0.0, cx, cy, depth); val b = cam.project(e, cam.centreN + reach, 0.0, cx, cy, depth)
        if (a != null && b != null) d.drawLine(Color(0x14B8C4D6), Offset(a[0], a[1]), Offset(b[0], b[1]), 1f)
        e += step
    }
    var n = n0
    while (n <= cam.centreN + reach) {
        val a = cam.project(cam.centreE - reach, n, 0.0, cx, cy, depth); val b = cam.project(cam.centreE + reach, n, 0.0, cx, cy, depth)
        if (a != null && b != null) d.drawLine(Color(0x14B8C4D6), Offset(a[0], a[1]), Offset(b[0], b[1]), 1f)
        n += step
    }
}
