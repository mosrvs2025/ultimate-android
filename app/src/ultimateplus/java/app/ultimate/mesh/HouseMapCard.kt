package app.ultimate.mesh

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.core.GlassCard
import app.ultimate.core.Liquid
import app.ultimate.core.LiquidButton
import kotlin.math.max

/**
 * "Map my devices": every pair of your devices measures its distance by sound, and the shape those distances make is
 * drawn as a floor plan of drops. Measured pairs are joined by lines with their distance; a pair that disagrees with the
 * rest (usually an echo) is dashed in red.
 */
@Composable
fun HouseMapCard(state: MeshManager.HouseState, peers: Int, lends: Boolean, onMap: () -> Unit,
                 you: WhereAmI.Estimate? = null, youNote: String? = null, tracking: Boolean = false, onTrack: (Boolean) -> Unit = {}) {
    GlassCard(title = "Map my devices", live = state is MeshManager.HouseState.Measuring, accent = Liquid.violet) {
        Text("Your phones chirp to each other two at a time and time the sound, then work out where each one is from the distances. No cameras, no internet.",
            color = Liquid.dim, fontSize = 14.sp, lineHeight = 20.sp)
        when (state) {
            is MeshManager.HouseState.Measuring -> {
                Text("Measuring ${state.a} ↔ ${state.b}", color = Color.White, fontSize = 15.sp)
                LinearProgressIndicator({ (state.done + 0.5f) / state.total }, Modifier.fillMaxWidth(), color = Liquid.teal)
                Text("Keep the phones still and the room quiet for a moment.", color = Liquid.dim, fontSize = 12.sp)
            }
            is MeshManager.HouseState.Done -> {
                HouseMapResult(state, if (tracking) you else null)
                if (state.result is HouseLayout.Result.Map) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Show where I am", color = Color.White, fontSize = 15.sp)
                            Text("From how strongly each phone hears your watch (Proximity beacon on the watch, Spatial sensing on the phones).", color = Liquid.dim, fontSize = 12.sp)
                        }
                        app.ultimate.core.GooeySwitch(tracking, onTrack)
                    }
                    // The same map in a headset (Quest) browser, served by Send to computer over https.
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val send = (ctx.applicationContext as app.ultimate.UltimateApp).sendServer
                    val sendState by send.state.collectAsState()
                    Text(sendState.secureAddress?.let { "See it in your Quest: in its browser open $it/house (accept the warning about the phone's own certificate once), then Step in." }
                        ?: "To see it in your Quest, turn on Send to computer; this card then shows the address to open in the headset's browser.",
                        color = Liquid.dim, fontSize = 12.sp)
                    if (tracking) {
                        val near = you?.nearest?.let { state.names[it] ?: it }
                        Text(when {
                            you == null || you.heardBy == 0 -> "Listening for your watch…"
                            near != null && you.sure -> "You're right by the $near."
                            near != null -> "You're probably nearest the $near (the circle shows how unsure)."
                            else -> ""
                        }, color = Liquid.teal, fontSize = 14.sp)
                        youNote?.let { Text(it, color = Liquid.dim, fontSize = 12.sp) }
                    }
                }
            }
            MeshManager.HouseState.Idle -> Unit
        }
        val pairs = (peers + 1) * peers / 2
        Text(when {
            peers < 2 -> "Needs at least two other devices on the mesh (three in all) to draw a map."
            !lends -> "The other devices need Lend this phone on, so they can chirp back."
            else -> "${peers + 1} devices, $pairs distances to measure: about ${pairs * 7} seconds."
        }, color = Liquid.dim, fontSize = 12.sp)
        if (state !is MeshManager.HouseState.Measuring) LiquidButton(if (state is MeshManager.HouseState.Done) "Measure again" else "Map my devices", onMap, Modifier.fillMaxWidth(), enabled = peers >= 2)
    }
}

@Composable
private fun HouseMapResult(s: MeshManager.HouseState.Done, you: WhereAmI.Estimate?) {
    when (val r = s.result) {
        is HouseLayout.Result.NotEnough -> {
            Text(r.why, color = Color(0xFFFFB4A9), fontSize = 14.sp)
            if (r.missing.isNotEmpty()) Text("Needs more: " + r.missing.joinToString { s.names[it] ?: it }, color = Color.White, fontSize = 14.sp)
        }
        is HouseLayout.Result.Map -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HouseCanvas(r, s.names, s.measured, you)
            Text(if (r.rms < 0.15) "Fits the measurements to within ${"%.0f".format(r.rms * 100)} cm on average." else "Rough: the measurements disagree by about ${"%.0f".format(r.rms * 100)} cm.",
                color = if (r.rms < 0.15) Liquid.teal else Color(0xFFFFC857), fontSize = 13.sp)
            r.doubtful.forEach { d -> Text("${s.names[d.a] ?: d.a} ↔ ${s.names[d.b] ?: d.b} looks like an echo was heard; measuring again may fix it.", color = Color(0xFFFFB4A9), fontSize = 12.sp) }
            Text("Distances alone cannot say which way is north, or left from right: the real room may be this map turned or mirrored.", color = Liquid.dim, fontSize = 12.sp)
        }
    }
    s.failed.forEach { Text(it, color = Color(0xFFFFB4A9), fontSize = 12.sp) }
}

@Composable
private fun HouseCanvas(r: HouseLayout.Result.Map, names: Map<String, String>, measured: List<HouseLayout.Measure>, you: WhereAmI.Estimate? = null) {
    // You glide to each new estimate on a soft spring and your drop breathes, so the reading looks alive, not jumpy.
    val yx = remember { Animatable(0f) }; val yy = remember { Animatable(0f) }; val yr = remember { Animatable(0f) }
    LaunchedEffect(you?.at, you?.radius) {
        val p = you?.at ?: return@LaunchedEffect
        kotlinx.coroutines.coroutineScope {
            launch { yx.animateTo(p.x.toFloat(), spring(0.7f, Spring.StiffnessVeryLow)) }
            launch { yy.animateTo(p.y.toFloat(), spring(0.7f, Spring.StiffnessVeryLow)) }
            launch { yr.animateTo(you.radius.toFloat(), spring(0.8f, Spring.StiffnessVeryLow)) }
        }
    }
    val breathe by androidx.compose.animation.core.rememberInfiniteTransition(label = "you").animateFloat(0.85f, 1.15f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1_400), androidx.compose.animation.core.RepeatMode.Reverse), label = "b")
    val grow = remember(r) { Animatable(0f) }
    LaunchedEffect(r) { grow.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
    val bad = r.doubtful.map { setOf(it.a, it.b) }.toSet()
    Canvas(Modifier.fillMaxWidth().aspectRatio(1.1f)) {
        val pts = r.at.values
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }; val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        val pad = 44.dp.toPx()
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(0.5)
        val k = ((minOf(size.width, size.height) - 2 * pad) / span).toFloat()
        val ox = (size.width - (maxX - minX).toFloat() * k) / 2; val oy = (size.height - (maxY - minY).toFloat() * k) / 2
        fun at(id: String): Offset { val p = r.at.getValue(id); return Offset(ox + ((p.x - minX) * k).toFloat(), size.height - (oy + ((p.y - minY) * k).toFloat())) }
        val label = android.graphics.Paint().apply { isAntiAlias = true; color = android.graphics.Color.WHITE; textSize = 12.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER }
        val small = android.graphics.Paint(label).apply { textSize = 10.sp.toPx(); color = 0xCCB8C4D6.toInt() }
        // Lines between measured pairs, with the measured distance at the middle, nudged off the line; a label that
        // would sit on top of one already drawn (or on a device) is left out rather than smudged.
        val taken = ArrayList<android.graphics.RectF>()
        r.at.keys.forEach { id -> val c = at(id); val q = 20.dp.toPx(); taken += android.graphics.RectF(c.x - q, c.y - q, c.x + q, c.y + q + 16.sp.toPx()) }
        for (m in measured) {
            if (m.a !in r.at || m.b !in r.at) continue
            val a = at(m.a); val b = at(m.b); val isBad = setOf(m.a, m.b) in bad
            drawLine(if (isBad) Color(0xFFFF8A80) else Liquid.teal.copy(alpha = 0.35f + 0.4f * m.weight.toFloat().coerceAtMost(1f)), a, b, 2.dp.toPx(),
                pathEffect = if (isBad || m.weight < 1) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null)
            val mid = (a + b) / 2f
            val dir = b - a; val len = dir.getDistance().coerceAtLeast(1f)
            val off = Offset(-dir.y / len, dir.x / len) * 9.dp.toPx()
            val text = "%.2f m".format(m.metres); val tw = small.measureText(text); val th = small.textSize
            val spot = mid + off
            val box = android.graphics.RectF(spot.x - tw / 2 - 2, spot.y - th, spot.x + tw / 2 + 2, spot.y + 3)
            if (taken.none { android.graphics.RectF.intersects(it, box) }) {
                taken += box
                drawIntoCanvas { it.nativeCanvas.drawText(text, spot.x, spot.y, small) }
            }
        }
        // You: a halo as wide as the uncertainty, and a warm drop at the best guess.
        if (you?.at != null) {
            val c = Offset(ox + ((yx.value - minX) * k).toFloat(), size.height - (oy + ((yy.value - minY) * k).toFloat()))
            val halo = (yr.value * k).coerceAtLeast(14.dp.toPx())
            drawCircle(Brush.radialGradient(listOf(Color(0x55FFC857), Color(0x00FFC857)), c, halo), halo, c)
            drawCircle(Color(0x66FFC857), halo, c, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE29A), Color(0xFFFF9F43)), c, 12.dp.toPx() * breathe), 9.dp.toPx() * breathe, c)
            drawIntoCanvas { it.nativeCanvas.drawText("You", c.x, c.y - 14.dp.toPx(), label) }
        }
        // Devices as drops that swell into place; the one you are nearest glows.
        r.at.keys.forEach { id ->
            val c = at(id); val rad = 16.dp.toPx() * grow.value
            if (id == you?.nearest) drawCircle(Color(0x55FFC857), rad * 1.7f * breathe, c)
            drawCircle(Brush.radialGradient(listOf(Liquid.teal, Liquid.violet), c - Offset(rad * 0.3f, rad * 0.3f), rad * 1.6f), rad, c)
            drawCircle(Color.White.copy(alpha = 0.7f), rad, c, style = Stroke(1.5f))
            drawIntoCanvas { it.nativeCanvas.drawText(names[id] ?: id, c.x, c.y + rad + 14.sp.toPx(), label) }
        }
        // A one-metre scale bar.
        val bar = k; val y0 = size.height - 10.dp.toPx(); val x0 = 12.dp.toPx()
        if (bar in 10f..size.width * 0.8f) {
            drawLine(Color.White.copy(alpha = 0.6f), Offset(x0, y0), Offset(x0 + bar, y0), 2.dp.toPx())
            drawIntoCanvas { it.nativeCanvas.drawText("1 m", x0 + bar / 2, y0 - 5.dp.toPx(), small) }
        }
    }
}
