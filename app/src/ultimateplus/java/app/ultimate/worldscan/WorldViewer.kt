package app.ultimate.worldscan

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment as UiAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.core.formatBytes
import app.ultimate.core.relativeTime
import app.ultimate.room.Orbit
import app.ultimate.room.P3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** One walk, loaded for drawing in its place's frame: poses with their times, and the GPS fixes written in the same frame. */
private class WalkView(val meta: WalkMeta, val color: Color, val t: LongArray, val x: FloatArray, val y: FloatArray, val z: FloatArray, val tracked: BooleanArray, val cum: DoubleArray, val fixT: LongArray, val fixX: FloatArray, val fixZ: FloatArray, val fixAcc: FloatArray, val note: String?)
private class PlaceView(val raster: Raster?, val image: ImageBitmap?, val pts: List<P3>, val walks: List<WalkView>, val baseGeo: GeoRegistration?)

/** Screen <-> map mapping for the top-down view: the map point (cx, cz) is at the middle, [zoom] pixels per metre, the whole picture turned [rotDeg] (clockwise). */
private class MapXf(val w: Float, val h: Float, val cx: Float, val cz: Float, val zoom: Float, val rotDeg: Float) {
    private val phi = Math.toRadians(rotDeg.toDouble()); val c = cos(phi).toFloat(); val s = sin(phi).toFloat()
    fun pt(x: Float, z: Float) = Offset(w / 2 + zoom * ((x - cx) * c - (z - cz) * s), h / 2 + zoom * ((x - cx) * s + (z - cz) * c))
    /** A drag of ([dx], [dy]) pixels as a shift on the map. */
    fun map(dx: Float, dy: Float) = Offset((dx * c + dy * s) / zoom, (-dx * s + dy * c) / zoom)
}

private val walkColours = listOf(WorldColors.teal, WorldColors.amber, WorldColors.violet, WorldColors.green, WorldColors.red)

@Composable
internal fun WorldViewer(tool: WorldScanTool, place: Place, onWalk: () -> Unit, onChanged: () -> Unit, onDeleted: () -> Unit) {
    val store = tool.store; val context = LocalContext.current; val scope = rememberCoroutineScope()
    var pv by remember(place.id, place.updatedAt) { mutableStateOf<PlaceView?>(null) }
    LaunchedEffect(place.id, place.updatedAt) { pv = withContext(Dispatchers.Default) { loadPlace(tool, place) } }
    var tab by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf("") }
    var selected by remember(place.id) { mutableStateOf(place.baseWalkId) }
    var message by remember { mutableStateOf<String?>(null) }
    when (dialog) {
        "rename" -> { var n by remember { mutableStateOf(place.name) }
            AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Rename") }, text = { OutlinedTextField(n, { n = it }, singleLine = true) },
                confirmButton = { TextButton({ store.renamePlace(place.id, n); dialog = ""; onChanged() }, enabled = n.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton({ dialog = "" }) { Text("Cancel") } }) }
        "delete" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Delete ${place.name}?") }, text = { Text("All its walks, maps and sensor logs are deleted from this phone. This can't be undone.") },
            confirmButton = { TextButton({ store.deletePlace(place.id); dialog = ""; onDeleted() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ dialog = "" }) { Text("Cancel") } })
        "undo" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Undo the last added walk?") }, text = { Text("The map goes back to how it was before that walk was added. The walk itself stays stored, unmerged.") },
            confirmButton = { TextButton({ store.undoLastMerge(place.id); dialog = ""; onChanged() }) { Text("Undo") } }, dismissButton = { TextButton({ dialog = "" }) { Text("Cancel") } })
        "export" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Share a file") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nothing leaves this phone unless you share it. Pick what to make:", fontSize = 13.sp)
            Text("• Path on the map (GeoJSON): where you walked, on Earth. It shows your location history, so share it only with people you trust.", fontSize = 12.sp)
            Text("• 3D points (PLY): the map as a point cloud for a computer (CloudCompare, MeshLab). It has no location in it.", fontSize = 12.sp) } },
            confirmButton = { Row { TextButton({ dialog = ""; scope.launch { message = withContext(Dispatchers.IO) { exportPath(tool, context, place) } } }) { Text("Path (GeoJSON)") }; TextButton({ dialog = ""; scope.launch { message = withContext(Dispatchers.IO) { exportPoints(tool, context, place) } } }) { Text("Points (PLY)") } } },
            dismissButton = { TextButton({ dialog = "" }) { Text("Cancel") } })
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Map", "3D", "Details").forEachIndexed { i, t -> Text(t, Modifier.clip(RoundedCornerShape(50)).background(if (tab == i) WorldColors.teal else WorldColors.card).clickable { tab = i }.padding(horizontal = 16.dp, vertical = 8.dp), color = if (tab == i) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val v = pv
            if (v == null) Text("Loading the map…", Modifier.align(UiAlign.Center), color = Color.White)
            else when (tab) {
                0 -> MapTab(v, selected, { selected = it })
                1 -> CloudTab(v)
                else -> DetailsTab(tool, place, v, selected, { selected = it }, onChanged)
            }
            message?.let { Text(it, Modifier.align(UiAlign.BottomCenter).padding(12.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.8f)).clickable { message = null }.padding(12.dp), color = Color.White, fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ onWalk() }) { Text("Add a walk") }
            if (store.canUndo(place.id)) Button({ dialog = "undo" }, colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()) { Text("Undo last add") }
            Button({ dialog = "export" }, colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()) { Text("Share…") }
            Button({ dialog = "rename" }, colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()) { Text("Rename") }
            Button({ dialog = "delete" }, colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
    }
}

// ---------------------------------------------------------------------- loading
private fun loadPlace(tool: WorldScanTool, place: Place): PlaceView {
    val store = tool.store
    val base = store.baseMap(place.id)
    val raster = base?.let { TopDown.render(listOf(it), cell = 0.25f) }
    val image = raster?.let { WorldScanTool.bitmapOf(it) }
    // At most ~40,000 cubes for the 3D view, taken evenly from the whole map without building a second copy of it.
    val pts = ArrayList<P3>().also { out -> base?.let { b -> val step = maxOf(1, b.solid / 40_000); var i = 0; b.forEachSolid { x, y, z -> if (i++ % step == 0) out += P3(x, y, z) } } }
    val views = ArrayList<WalkView>()
    for ((n, w) in place.walks.withIndex()) {
        val isBase = w.id == place.baseWalkId
        // A walk that was refused for merging is not in the place's frame, but if both it and the place are on the Earth it can still be drawn where GPS says it went (dashed, grey).
        val gpsOnly = !isBase && w.merge == null
        val pg = place.bestGeo?.takeIf { it.usable }
        if (gpsOnly && !(w.geo?.usable == true && pg != null)) continue
        val track = store.track(place.id, w.id) ?: continue
        val fits = w.merge?.pieces ?: emptyList()
        val n0 = track.points.size
        val t = LongArray(n0); val x = FloatArray(n0); val y = FloatArray(n0); val z = FloatArray(n0); val ok = BooleanArray(n0); val cum = DoubleArray(n0)
        var k = 0; var run = 0.0
        for (p in track.points) {
            val a = if (isBase) null else (fits.firstOrNull { it.alignment != null && p.tNs in it.t0Ns..it.t1Ns }?.alignment ?: w.merge?.global)
            val q = when {
                gpsOnly -> { val ll = w.geo!!.toLatLon(p.x, p.z); val e = pg!!.anchor.project(GeoFix(ll.first, ll.second, null, 1f, 0L)); val (lx, lz) = pg.toLocal(e.eastMeters, e.northMeters); P3(lx, p.y, lz) }
                a == null -> P3(p.x, p.y, p.z)
                else -> a.apply(P3(p.x, p.y, p.z))
            }
            if (k > 0 && p.tracking == TrackingQuality.TRACKING && ok[k - 1]) { val d = hypot((q.x - x[k - 1]).toDouble(), (q.z - z[k - 1]).toDouble()); if (d < 3.0) run += d }
            t[k] = p.tNs; x[k] = q.x; y[k] = q.y; z[k] = q.z; ok[k] = p.tracking == TrackingQuality.TRACKING; cum[k] = run; k++
        }
        // GPS fixes written in the place's frame (only for the first walk: later walks' fixes would need their own placement drawn).
        var ft = LongArray(0); var fx = FloatArray(0); var fz = FloatArray(0); var fa = FloatArray(0)
        if (isBase && w.geo?.usable == true) {
            val g = w.geo
            val list = store.fixes(place.id, w.id).ifEmpty { store.rawLog(place.id, w.id)?.let { f -> runCatching { SessionLog.read(f).fixes }.getOrNull() } ?: emptyList() }
            ft = LongArray(list.size); fx = FloatArray(list.size); fz = FloatArray(list.size); fa = FloatArray(list.size)
            list.forEachIndexed { i, f -> val e = g.anchor.project(f); val (lx, lz) = g.toLocal(e.eastMeters, e.northMeters); ft[i] = f.elapsedRealtimeNanos; fx[i] = lx; fz[i] = lz; fa[i] = f.horizontalAccuracyMeters }
        }
        views += WalkView(w, if (isBase) walkColours[0] else if (gpsOnly) WorldColors.dim else walkColours[1 + (n % (walkColours.size - 1))], t.copyOf(k), x.copyOf(k), y.copyOf(k), z.copyOf(k), ok.copyOf(k), cum.copyOf(k), ft, fx, fz, fa, if (gpsOnly) "GPS only" else null)
    }
    return PlaceView(raster, image, pts, views, place.bestGeo)
}

// ---------------------------------------------------------------------- map + replay
@Composable
private fun MapTab(v: PlaceView, selected: String, onSelect: (String) -> Unit) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var cx by remember { mutableFloatStateOf(0f) }; var cz by remember { mutableFloatStateOf(0f) }; var zoom by remember { mutableFloatStateOf(0f) }
    var northUp by remember { mutableStateOf(v.baseGeo?.usable == true) }
    var showGps by remember { mutableStateOf(false) }
    var showPaths by remember { mutableStateOf(true) }
    val walk = v.walks.firstOrNull { it.meta.id == selected } ?: v.walks.firstOrNull()
    val duration = walk?.let { if (it.t.size < 2) 0.0 else (it.t.last() - it.t.first()) / 1e9 } ?: 0.0
    var tSec by remember(walk?.meta?.id) { mutableFloatStateOf(duration.toFloat()) }
    var playing by remember { mutableStateOf(false) }
    val rot = if (northUp && v.baseGeo?.usable == true) (-Math.toDegrees(v.baseGeo.thetaRad)).toFloat() else 0f
    // Fit the whole map into the view the first time and when the layers change.
    LaunchedEffect(boxSize, v) {
        if (boxSize.width == 0) return@LaunchedEffect
        val r = v.raster; val xs = ArrayList<Float>(); val zs = ArrayList<Float>()
        if (r != null) { xs += r.x0; xs += r.x0 + r.w * r.cell; zs += r.z0; zs += r.z0 + r.h * r.cell }
        v.walks.forEach { w -> w.x.forEach { xs += it }; w.z.forEach { zs += it } }
        if (xs.isEmpty()) return@LaunchedEffect
        val minX = xs.min(); val maxX = xs.max(); val minZ = zs.min(); val maxZ = zs.max()
        cx = (minX + maxX) / 2; cz = (minZ + maxZ) / 2
        val span = max(maxX - minX, maxZ - minZ).coerceAtLeast(5f); zoom = min(boxSize.width, boxSize.height) / (span * 1.15f)
    }
    LaunchedEffect(playing, walk?.meta?.id) { if (playing) { if (tSec >= duration - 0.05) tSec = 0f; while (playing) { delay(40); tSec += 0.04f * 6f; if (tSec >= duration) { tSec = duration.toFloat(); playing = false } } } }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(WorldColors.bg).onSizeChanged { boxSize = it }.pointerInput(Unit) {
            detectTransformGestures { _, pan, z, _ ->
                if (zoom > 0f) { val xf = MapXf(boxSize.width.toFloat(), boxSize.height.toFloat(), cx, cz, zoom, rot); val d = xf.map(pan.x, pan.y); cx -= d.x; cz -= d.y; zoom = (zoom * z).coerceIn(0.05f, 80f) }
            }
        }) {
            Canvas(Modifier.fillMaxSize()) {
                if (zoom <= 0f) return@Canvas
                val xf = MapXf(size.width, size.height, cx, cz, zoom, rot)
                v.raster?.let { r -> v.image?.let { img ->
                    withTransform({ translate(size.width / 2, size.height / 2); rotate(rot, Offset.Zero); scale(zoom, zoom, Offset.Zero); translate(-cx, -cz); translate(r.x0, r.z0); scale(r.cell, r.cell, Offset.Zero) }) { drawImage(img, filterQuality = FilterQuality.None) }
                } }
                if (showGps) v.walks.firstOrNull { it.fixX.isNotEmpty() }?.let { w -> for (i in w.fixX.indices) { val o = xf.pt(w.fixX[i], w.fixZ[i]); drawCircle(Color.White.copy(alpha = 0.10f), w.fixAcc[i] * zoom, o); drawCircle(Color.White, 3f, o) } }
                if (showPaths) for (w in v.walks) {
                    val p = Path(); var started = false
                    for (i in w.x.indices) { if (!w.tracked[i]) { started = false; continue }; val o = xf.pt(w.x[i], w.z[i]); if (!started) { p.moveTo(o.x, o.y); started = true } else p.lineTo(o.x, o.y) }
                    drawPath(p, w.color.copy(alpha = if (w === walk) 0.45f else 0.9f), style = Stroke(if (w === walk) 3f else 4f, cap = StrokeCap.Round, pathEffect = if (w.note != null) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null))
                }
                // The part of the selected walk replayed so far, bright, with the phone and which way it faces.
                if (walk != null && walk.t.isNotEmpty()) {
                    val upTo = replayIndex(walk, tSec)
                    val p = Path(); var started = false
                    for (i in 0..upTo) { if (!walk.tracked[i]) { started = false; continue }; val o = xf.pt(walk.x[i], walk.z[i]); if (!started) { p.moveTo(o.x, o.y); started = true } else p.lineTo(o.x, o.y) }
                    drawPath(p, walk.color, style = Stroke(5f, cap = StrokeCap.Round))
                    val here = xf.pt(walk.x[upTo], walk.z[upTo]); drawCircle(Color.White, 11f, here); drawCircle(walk.color, 7f, here)
                    val first = xf.pt(walk.x[0], walk.z[0]); drawCircle(WorldColors.green, 7f, first, style = Stroke(3f))
                }
                // Scale bar and north.
                val nice = listOf(1f, 2f, 5f, 10f, 20f, 50f, 100f, 200f, 500f, 1000f).firstOrNull { it * zoom >= 70f } ?: 1000f
                val y = size.height - 22f; drawLine(Color.White, Offset(18f, y), Offset(18f + nice * zoom, y), 4f, StrokeCap.Round)
                drawContext.canvas.nativeCanvas.drawText(if (nice >= 1000) "${(nice / 1000).toInt()} km" else "${nice.toInt()} m", 18f, y - 10f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 12.sp.toPx(); isFakeBoldText = true; setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK) })
                v.baseGeo?.takeIf { it.usable }?.let { g ->
                    val north = xf.pt(cx + sin(g.thetaRad).toFloat() * 10f, cz - cos(g.thetaRad).toFloat() * 10f) - xf.pt(cx, cz)
                    val len = hypot(north.x, north.y).coerceAtLeast(1f); val d = Offset(north.x / len, north.y / len); val c0 = Offset(size.width - 38f, 42f)
                    drawCircle(Color.Black.copy(alpha = 0.5f), 24f, c0); drawLine(WorldColors.red, c0 - d * 4f, c0 + d * 17f, 4f, StrokeCap.Round); drawCircle(Color.White, 3f, c0)
                    drawContext.canvas.nativeCanvas.drawText("N", c0.x + d.x * 30f - 6f, c0.y + d.y * 30f + 6f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 12.sp.toPx(); isFakeBoldText = true })
                }
            }
            Row(Modifier.align(UiAlign.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Toggle("North up", northUp, v.baseGeo?.usable == true) { northUp = it }
                Toggle("Paths", showPaths, true) { showPaths = it }
                Toggle("GPS", showGps, v.walks.any { it.fixX.isNotEmpty() }) { showGps = it }
            }
            if (v.raster == null) Text("No map to show yet.", Modifier.align(UiAlign.Center), color = Color.White)
        }
        // --- replay bar
        if (walk != null && duration > 0) Column(Modifier.fillMaxWidth().background(WorldColors.card).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (v.walks.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { v.walks.forEach { w -> Text(w.meta.name + (w.note?.let { " ($it)" } ?: ""), Modifier.clip(RoundedCornerShape(50)).background(if (w === walk) w.color else WorldColors.bg).clickable { onSelect(w.meta.id) }.padding(horizontal = 10.dp, vertical = 4.dp), color = if (w === walk) Color.Black else w.color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) } }
            Row(verticalAlignment = UiAlign.CenterVertically) {
                Text(if (playing) "❚❚" else "▶", Modifier.clip(RoundedCornerShape(50)).background(WorldColors.bg).clickable { playing = !playing }.padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White)
                Slider(tSec, { tSec = it; playing = false }, Modifier.weight(1f).padding(horizontal = 8.dp), valueRange = 0f..duration.toFloat().coerceAtLeast(0.1f))
            }
            Text(replayLine(walk, tSec), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
        }
    }
}

@Composable private fun Toggle(label: String, on: Boolean, enabled: Boolean, set: (Boolean) -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(50)).background(if (on && enabled) WorldColors.teal else Color.Black.copy(alpha = 0.6f)).clickable(enabled = enabled) { set(!on) }.padding(horizontal = 12.dp, vertical = 6.dp), color = if (!enabled) WorldColors.dim else if (on) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

/** The last pose at or before [tSec] seconds into the walk. */
private fun replayIndex(w: WalkView, tSec: Float): Int {
    if (w.t.isEmpty()) return 0
    val target = w.t.first() + (tSec * 1e9).toLong(); var lo = 0; var hi = w.t.size - 1
    while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (w.t[mid] <= target) lo = mid else hi = mid - 1 }
    return lo
}

private fun replayLine(w: WalkView, tSec: Float): String {
    val i = replayIndex(w, tSec)
    // Speed over the last second of the walk's own time.
    var j = i; while (j > 0 && w.t[i] - w.t[j] < 1_000_000_000L) j--
    val dt = (w.t[i] - w.t[j]) / 1e9; val speed = if (dt > 0.2) (w.cum[i] - w.cum[j]) / dt else 0.0
    var gps = ""
    if (w.fixT.isNotEmpty()) { var best = 0; var bd = Long.MAX_VALUE; for (k in w.fixT.indices) { val d = abs(w.fixT[k] - w.t[i]); if (d < bd) { bd = d; best = k } }; if (bd < 2_000_000_000L) gps = " · GPS ±${w.fixAcc[best].roundToInt()} m" }
    return "${WorldScanTool.clockText(tSec.toDouble())} · ${w.cum[i].roundToInt()} m · ${"%.1f".format(speed)} m/s · ${if (w.tracked[i]) "tracking ✓" else "tracking lost"}$gps"
}

// ---------------------------------------------------------------------- 3D
@Composable
private fun CloudTab(v: PlaceView) {
    var yaw by remember { mutableFloatStateOf(30f) }; var pitch by remember { mutableFloatStateOf(50f) }; var zoom by remember { mutableFloatStateOf(0f) }
    val pts = v.pts
    val centre = remember(pts) { if (pts.isEmpty()) P3(0f, 0f, 0f) else P3(pts.map { it.x }.sorted()[pts.size / 2], pts.map { it.y }.sorted()[pts.size / 2], pts.map { it.z }.sorted()[pts.size / 2]) }
    val ground = remember(pts) { if (pts.isEmpty()) 0f else pts.map { it.y }.sorted()[pts.size / 10] }
    Box(Modifier.fillMaxSize().background(WorldColors.bg).pointerInput(Unit) { detectTransformGestures { _, pan, z, _ -> yaw += pan.x * 0.3f; pitch = (pitch + pan.y * 0.3f).coerceIn(-10f, 89f); zoom = (zoom * z).coerceIn(0.5f, 400f) } }) {
        if (pts.isEmpty()) Text("Nothing mapped yet.", Modifier.align(UiAlign.Center), color = Color.White)
        else Canvas(Modifier.fillMaxSize()) {
            if (zoom <= 0f) { val xs = pts.map { it.x }; val zs = pts.map { it.z }; zoom = min(size.width, size.height) / (max(xs.max() - xs.min(), zs.max() - zs.min()).coerceAtLeast(4f) * 1.1f); return@Canvas }
            fun at(p: P3): Offset { val (sx, sy, _) = Orbit.project(p, centre, yaw, pitch, zoom); return Offset(size.width / 2 + sx, size.height / 2 + sy) }
            val groups = Array(5) { ArrayList<Offset>() }
            for (p in pts) groups[heightClass(p.y - ground)].add(at(p))
            val cols = listOf(Color(0xFF5A6C85), WorldColors.teal, WorldColors.green, WorldColors.amber, Color(0xFFFF7896))
            for (g in 0..4) drawPoints(groups[g], PointMode.Points, cols[g].copy(alpha = 0.85f), 3f, StrokeCap.Round)
            for (w in v.walks) { val path = Path(); var started = false; for (i in w.x.indices) { if (!w.tracked[i]) { started = false; continue }; val o = at(P3(w.x[i], w.y[i] - 1.2f, w.z[i])); if (!started) { path.moveTo(o.x, o.y); started = true } else path.lineTo(o.x, o.y) }; drawPath(path, w.color, style = Stroke(3f, cap = StrokeCap.Round)) }
        }
        Text("Drag to turn · pinch to zoom · coloured by height above the ground", Modifier.align(UiAlign.BottomCenter).padding(10.dp), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
    }
}
private fun heightClass(h: Float) = when { h < 0.25f -> 0; h < 0.9f -> 1; h < 2.2f -> 2; h < 4.5f -> 3; else -> 4 }

// ---------------------------------------------------------------------- details
@Composable
private fun DetailsTab(tool: WorldScanTool, place: Place, v: PlaceView, selected: String, onSelect: (String) -> Unit, onChanged: () -> Unit) {
    val store = tool.store; val scope = rememberCoroutineScope()
    val w = place.walks.firstOrNull { it.id == selected } ?: place.walks.first()
    var retrying by remember { mutableStateOf(false) }; var retryMsg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            place.walks.forEach { x -> Text(x.name + if (x.id == place.baseWalkId) " (first)" else "", Modifier.clip(RoundedCornerShape(50)).background(if (x.id == w.id) WorldColors.teal else WorldColors.card).clickable { onSelect(x.id) }.padding(horizontal = 12.dp, vertical = 6.dp), color = if (x.id == w.id) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }
        Section("The walk", listOf(
            "Walked" to "${"%,.0f".format(w.lengthM)} m in ${WorldScanTool.clockText(w.durationS)} (${relativeTime(w.startedAt)})",
            "Extent" to "${"%.0f".format(w.extentX)} × ${"%.0f".format(w.extentZ)} m",
            "Motion tracking" to "${"%.0f".format(w.trackedShare * 100)}% of the time" + if (w.longestLossS > 0.5) ", longest loss ${"%.1f".format(w.longestLossS)} s" else "",
            "Pace (95th percentile)" to "${"%.1f".format(w.speedP95)} m/s" + if (w.jumps > 0) " · ${w.jumps} jump${if (w.jumps == 1) "" else "s"} in the track" else "",
            "Map" to "${"%,d".format(w.cubes)} cubes of 10 cm in ${w.pieces.size} pieces, from ${w.keyFrames} depth pictures"))
        val g = w.geo
        Section("On the Earth", if (g == null) listOf("GPS" to "Not recorded for this walk, so it is mapped but not placed on Earth.") else listOf(
            "Placement" to g.summary(),
            "Heading" to if (g.usable) "walk's −Z axis points ${"%.0f".format(g.northOffsetDeg)}° from north (±${"%.1f".format(g.yawSigmaDeg)}°)" else "unknown",
            "GPS fit" to if (g.rmsM.isNaN()) "—" else "${"%.1f".format(g.rmsM)} m RMS, worst ${"%.1f".format(g.maxResidualM)} m, χ ${"%.1f".format(g.chi)}",
            "Length vs GPS" to if (g.used < 4) "—" else "scale ${"%.3f".format(g.scale)} (1.000 = motion tracking and GPS agree on distance)",
            "Fixes" to "${w.fixes} received, median accuracy ${w.gpsMedianAccM?.let { "%.1f m".format(it) } ?: "—"}"))
        w.drift?.let { d ->
            var busy by remember(w.id, d.applied) { mutableStateOf(false) }
            var msg by remember(w.id) { mutableStateOf<String?>(null) }
            val canChange = w.id == place.baseWalkId && place.mergeOrder.isEmpty()
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(WorldColors.card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Drift", color = WorldColors.teal, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(if (d.applied) "Corrected. " + d.summary() + " The raw walk is kept." else d.summary(), color = Color.White, fontSize = 12.sp, lineHeight = 16.sp)
                if (d.applicable && !d.applied) Text("Motion tracking drifts, GPS doesn't, so over a long walk the two disagree more and more. This fits the simplest model of that drift. A GPS error that grows slowly (between tall buildings) can look like drift, so look at the graph below before applying: it is only worth it if the walk really bends away from GPS.", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, lineHeight = 15.sp)
                if (canChange && d.applicable && !d.applied) Button({ busy = true; scope.launch { msg = withContext(Dispatchers.Default) { store.applyDrift(place.id, w.id) }; busy = false; onChanged() } }, enabled = !busy) { Text(if (busy) "Correcting…" else "Correct the drift") }
                if (canChange && d.applied) Button({ busy = true; scope.launch { msg = withContext(Dispatchers.Default) { store.revertDrift(place.id, w.id) }; busy = false; onChanged() } }, enabled = !busy, colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()) { Text(if (busy) "Undoing…" else "Undo the correction") }
                if (!canChange && d.applicable && !d.applied) Text("Applying it is only possible on a place's first walk before other walks are added to it.", color = WorldColors.amber, fontSize = 11.sp)
                msg?.let { Text(it, color = WorldColors.amber, fontSize = 12.sp) }
            }
        }
        Section("Time synchronisation", listOf(
            "Camera clock" to (w.clock?.let { if (it.sameClock == true) "same clock as the sensors" else if (it.sameClock == false) "a different clock (offset followed)" else "unknown" } ?: "not measured"),
            "Jitter" to (w.clock?.let { "%.1f ms typical (the error bar on matching camera to GPS and sensors) over %,d frames".format(it.jitterMs, it.samples) } ?: "—"),
            "Drift" to (w.clock?.driftMsPerHour?.let { "%.0f ms/hour".format(it) } ?: "not enough time to measure"),
            "Sensor log" to if (w.logBytes > 0) "${formatBytes(w.logBytes)} compressed" + (if (w.logTruncated) " · TRUNCATED at the size limit (fast sensors stopped; GPS and poses continued)" else "") + " · " + w.sensorCounts.entries.sortedBy { it.key }.joinToString(", ") { "${sensorName(it.key)} ${"%,d".format(it.value)}" } else "none (no location permission)"))
        if (w.id != place.baseWalkId) {
            val m = w.merge
            Section("Added to the map", if (m != null) listOf(
                "Result" to "Added ${m.used} of ${m.pieces.size} pieces, whole-walk fit ${"%.0f".format(m.globalMeanDist * 100)} cm, ${"%.0f".format(m.globalInlier * 100)}% of walls within 30 cm",
                "Search" to m.prior,
                "Uniqueness" to "margin ${"%.0f".format(m.margin * 100)}% over the next-best placement",
                "Fitted individually" to "${m.pieces.count { it.how == "refined" }} pieces (drift is corrected per piece of about 15 m)",
                "Changes" to "+${"%,d".format(m.pieces.sumOf { it.added })} new, ${"%,d".format(m.pieces.sumOf { it.reinforced })} confirmed, −${"%,d".format(m.pieces.sumOf { it.removed })} cleared",
                "Left out" to (m.pieces.filter { it.alignment == null }.joinToString { "piece ${it.index + 1}: ${it.how}" }.ifBlank { "none" }))
            else listOf("Not added" to (w.unmergedReason ?: "unknown"), "Kept" to "All its pieces, track and sensor log are stored. The saved map was not changed."))
            if (m == null) Column {
                Button({ retrying = true; scope.launch { val r = withContext(Dispatchers.Default) { store.retryMerge(place.id, w.id) }; retryMsg = r?.summary(); retrying = false; onChanged() } }, enabled = !retrying) { Text(if (retrying) "Trying…" else "Try adding it again") }
                retryMsg?.let { Text(it, color = WorldColors.amber, fontSize = 12.sp) }
            }
        }
        Section("The phone", listOf(
            "Heat" to if (w.thermalMax < 0) "not recorded" else "peak: ${WorldScanTool.thermalName(w.thermalMax)} (Android's thermal status ${w.thermalMax})",
            "Battery" to if (w.batteryStart < 0 || w.batteryEnd < 0) "not recorded" else "${w.batteryStart}% to ${w.batteryEnd}% over ${WorldScanTool.clockText(w.durationS)}" + if (w.durationS > 120 && w.batteryStart >= w.batteryEnd) " (${"%.1f".format((w.batteryStart - w.batteryEnd) * 3600.0 / w.durationS)}% an hour; whole-percent resolution)" else ""))
        val walkView = v.walks.firstOrNull { it.meta.id == w.id }
        val drift = walkView?.let { wv -> if (wv.fixT.isEmpty()) null else DriftSeries.of(wv.t, wv.x, wv.z, wv.tracked, wv.fixT, wv.fixX, wv.fixZ) }
        if (drift != null && drift.size >= 4) {
            val ends = DriftSeries.firstAndLastThird(drift)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(WorldColors.card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Walk vs GPS over time", color = WorldColors.teal, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                val top = (drift.maxOf { it.second }).coerceAtLeast(2.0).toFloat()
                Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                    val tMax = drift.last().first.coerceAtLeast(1.0).toFloat()
                    drawLine(WorldColors.line, Offset(0f, size.height), Offset(size.width, size.height), 2f)
                    val p = Path(); drift.forEachIndexed { i, (ts, m) -> val o = Offset(ts.toFloat() / tMax * size.width, size.height - (m.toFloat() / top) * size.height); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
                    drawPath(p, WorldColors.amber, style = Stroke(3f, cap = StrokeCap.Round))
                }
                Text("How far apart the walk and GPS were at each fix (0 to ${"%.0f".format(top)} m). A steady rise means the walk drifted; spikes are GPS noise." + (ends?.let { " First third median ${"%.1f".format(it.first)} m, last third ${"%.1f".format(it.second)} m." } ?: ""), color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        place.geo?.let { pg ->
            val own = place.walks.firstOrNull { it.id == place.baseWalkId }?.geo
            Section("The place on the Earth", listOf(
                "Placement" to pg.summary(),
                "From" to "${place.geoWalks} walk${if (place.geoWalks == 1) "" else "s"} with GPS, each carried into the place's frame by the way it was added",
                "Heading" to "±${"%.2f".format(pg.yawSigmaDeg)}°" + (own?.takeIf { it.usable }?.let { " (the first walk alone: ±${"%.2f".format(it.yawSigmaDeg)}°)" } ?: ""),
                "North" to if (pg.usable) "the first walk's −Z axis points ${"%.0f".format(pg.northOffsetDeg)}° from north" else "unknown"))
        }
        Section("This place", listOf("Walks" to "${place.walks.size} (${place.mergeOrder.size} added to the first)", "Stored" to "${formatBytes(store.bytes(place.id))} on this phone only", "Free space" to formatBytes(store.freeBytes())))
        Spacer(Modifier.height(20.dp))
    }
}

private fun sensorName(type: Int) = when (type) { 1 -> "accelerometer"; 2 -> "magnetic"; 4 -> "gyroscope"; 5 -> "light"; 6 -> "pressure"; 11 -> "rotation"; else -> "type $type" }

@Composable
private fun Section(title: String, rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(WorldColors.card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = WorldColors.teal, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        rows.forEach { (k, value) -> Row { Text(k, Modifier.width(120.dp), color = WorldColors.dim, fontSize = 12.sp); Text(value, Modifier.weight(1f), color = Color.White, fontSize = 12.sp, lineHeight = 16.sp) } }
    }
}

// ---------------------------------------------------------------------- export
private fun exportPath(tool: WorldScanTool, context: Context, place: Place): String {
    val walks = place.walks.mapNotNull { w -> tool.store.track(place.id, w.id)?.let { w to it } }
    val json = WorldExport.geoJson(place.name, walks, baseWalkId = place.baseWalkId, placeGeo = place.geo)
    if (!json.contains("\"LineString\"")) return "No walk in this place could be placed on the map, so there is no path to share."
    return share(context, "${safe(place.name)}.geojson", json.toByteArray())
}
private fun exportPoints(tool: WorldScanTool, context: Context, place: Place): String {
    val map = tool.store.baseMap(place.id) ?: return "The map couldn't be read."
    val f = File(File(context.cacheDir, "worldscan").apply { mkdirs() }, "${safe(place.name)}.ply")
    val n = f.outputStream().buffered().use { WorldExport.ply(map, it) }
    return shareFile(context, f, "$n points")
}
private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifBlank { "world" }
private fun share(context: Context, name: String, bytes: ByteArray): String { val f = File(File(context.cacheDir, "worldscan").apply { mkdirs() }, name); f.writeBytes(bytes); return shareFile(context, f, "path") }
private fun shareFile(context: Context, f: File, what: String): String {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    android.os.Handler(android.os.Looper.getMainLooper()).post { app.ultimate.core.shareFiles(context, listOf(uri)) }
    return "Sharing $what (${f.name}). The file stays in this app's cache until the phone clears it."
}
