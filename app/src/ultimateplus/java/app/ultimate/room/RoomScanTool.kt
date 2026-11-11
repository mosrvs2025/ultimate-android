package app.ultimate.room

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.Signal
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Room scan: walk round a room with the camera and the phone builds its shape in 3D, measures it, notes the furniture and household
 * things it recognises, and remembers the room so it can tell later that you are back in it. Uses ARCore (depth and motion tracking)
 * and an on-device detector. Scans, pictures and maps stay on this phone.
 */
class RoomScanTool(private val host: UltimateApp) : Tool {
    private val store = RoomStore(File(host.filesDir, "rooms"))
    private val rooms = MutableStateFlow(store.list())
    override val id = "roomscan"
    override val title = "Room scan"
    override val tagline = "Scan a room in 3D, measure it, and remember it"
    override val icon = Icons.Rounded.ViewInAr
    private val _headline = MutableStateFlow(headlineOf(rooms.value))
    override val headline: StateFlow<String> = _headline
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override val acceptedMimePrefixes: List<String> = emptyList()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    private fun headlineOf(l: List<SavedRoom>) = if (l.isEmpty()) "No rooms yet" else "${l.size} room${if (l.size == 1) "" else "s"} remembered"
    private fun reload() { rooms.value = store.list(); _headline.value = headlineOf(rooms.value) }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        var scanning by remember { mutableStateOf(false) }
        var viewing by remember { mutableStateOf<String?>(null) }
        val list by rooms.collectAsState()
        BackHandler { if (scanning) scanning = false else if (viewing != null) viewing = null else onExit() }
        Scaffold(topBar = { TopAppBar(title = { Text(if (scanning) "Scanning" else list.firstOrNull { it.id == viewing }?.name ?: "Room scan") }, navigationIcon = { IconButton({ if (scanning) scanning = false else if (viewing != null) viewing = null else onExit() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when {
                    scanning -> ScanScreen(list, onSaved = { reload(); scanning = false; viewing = it }, onChanged = { reload() })
                    viewing != null -> list.firstOrNull { it.id == viewing }?.let { r -> Viewer(r, onRename = { store.rename(r.id, it); reload() }, onDelete = { store.delete(r.id); viewing = null; reload() }, onChanged = { reload() }) } ?: run { viewing = null }
                    else -> RoomList(list, onScan = { scanning = true }, onOpen = { viewing = it })
                }
            }
        }
    }

    @Composable
    private fun RoomList(list: List<SavedRoom>, onScan: () -> Unit, onOpen: (String) -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Walk slowly round a space pointing the camera at the floor, the walls up to the ceiling, and the furniture. The phone builds it in 3D and measures only what it has really seen. Scan a saved space again and the new scan is lined up with it and folded in, so it gets more complete and more trustworthy each time. Everything stays on this phone.", style = MaterialTheme.typography.bodyMedium)
            Button(onScan, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Rounded.ViewInAr, null); Spacer(Modifier.width(8.dp)); Text("Scan a space") }
            if (list.isNotEmpty()) Text("Remembered spaces", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            list.sortedWith(compareBy<SavedRoom> { it.place.isBlank() }.thenBy { it.place }.thenByDescending { it.updatedAt }).forEach { r ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { onOpen(r.id) }.padding(14.dp)) {
                    Text("${kindEmoji(r.kind)} ${r.name}" + if (r.place.isNotBlank()) "  ·  ${r.place}" else "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(dims(r.stats), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text("${r.sessions.size} scan${if (r.sessions.size == 1) "" else "s"} · confidence ${r.stats.confidence.name.lowercase()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.objects.any { it.state == "here" }) Text(r.objects.filter { it.state == "here" }.groupingBy { it.label }.eachCount().entries.joinToString(" · ") { (l, n) -> "${RoomLabels.emoji(l)} ${if (n > 1) "$n× " else ""}$l" }, style = MaterialTheme.typography.bodySmall)
                    Text(app.ultimate.core.relativeTime(r.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Needs a phone that supports ARCore depth. Distances come from the camera moving (most phones have no depth sensor), so expect a few centimetres of error up close and more across a large room, and worse on blank white walls, mirrors, glass and in dim light.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    private fun kindEmoji(kind: String) = when (kind) { "vehicle" -> "🚗"; "shelving" -> "🗄️"; "other" -> "📦"; else -> "🏠" }

    /** Only what was measured: a size needs walls seen on both sides, a height needs floor and ceiling. */
    private fun dims(s: RoomStats): String {
        val l = s.length; val w = s.width
        val size = when { l != null && w != null -> "%.2f × %.2f m".format(l, w); l != null -> "%.2f m one way, other way not measured".format(l); else -> "size not measured" }
        return size + (s.height?.let { " · %.2f m high".format(it) } ?: " · height not measured")
    }
    private fun detail(s: RoomStats) = (s.footprint?.let { "footprint %.1f m² · ".format(it) } ?: "") + "floor seen %.1f m² · confidence ${s.confidence.name.lowercase()}".format(s.floorSeen)

    private fun Context.activity(): Activity? { var c: Context? = this; while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }

    @Composable
    private fun ScanScreen(saved: List<SavedRoom>, onSaved: (String) -> Unit, onChanged: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var allowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
        if (!allowed) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Scanning uses the camera. Pictures are looked at on the phone and dropped; only the room's shape is kept."); Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow the camera") } }; return }

        // ARCore may need installing or updating from the Play Store first; it asks, and we try again when you come back.
        var session by remember { mutableStateOf<Session?>(null) }
        var problem by remember { mutableStateOf<String?>(null) }
        var retry by remember { mutableIntStateOf(0) }
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(retry) {
            if (session != null) return@LaunchedEffect
            val act = context.activity() ?: run { problem = "Room scan can't start from here."; return@LaunchedEffect }
            try {
                if (ArCoreApk.getInstance().requestInstall(act, true) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) { problem = "Install or update \"Google Play Services for AR\", then come back."; return@LaunchedEffect }
                session = Session(act); problem = null
            } catch (e: com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException) { problem = "This phone doesn't support ARCore, so it can't scan rooms."
            } catch (e: com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException) { problem = "Room scan needs \"Google Play Services for AR\" from the Play Store."
            } catch (e: Exception) { problem = "ARCore couldn't start: ${e.message ?: e.javaClass.simpleName}" }
        }
        val s = session
        if (s == null) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(problem ?: "Starting the camera…"); if (problem != null) Button({ retry++ }) { Text("Try again") } }; return }

        val scan = remember(s) { ArScan(context, s) }
        val st by scan.state.collectAsState()
        val view = LocalView.current
        var gl by remember { mutableStateOf<GLSurfaceView?>(null) }
        DisposableEffect(s, owner) {
            view.keepScreenOn = true
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) { runCatching { s.resume() }.onFailure { problem = "The camera is in use by something else." }; gl?.onResume() }
                if (e == Lifecycle.Event.ON_PAUSE) { gl?.onPause(); runCatching { s.pause() } }
            }
            owner.lifecycle.addObserver(obs)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) runCatching { s.resume() }
            onDispose { view.keepScreenOn = false; owner.lifecycle.removeObserver(obs); gl?.onPause(); runCatching { s.pause() }; scan.close(); Thread { runCatching { s.close() } }.start() }
        }

        // Recognising a remembered room from its shape.
        var match by remember { mutableStateOf<Pair<SavedRoom, Alignment>?>(null) }
        var looking by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf<String?>(null) }
        fun findPlace(quiet: Boolean) {
            if (looking || saved.isEmpty()) return
            looking = true; if (!quiet) note = null
            scope.launch {
                var unsure: SavedRoom? = null
                val found = withContext(Dispatchers.Default) {
                    val fresh = synchronized(scan.map) { scan.map.cleaned() }
                    if (fresh.size < 2500) return@withContext null
                    val all = saved.mapNotNull { r -> store.map(r.id)?.let { m -> Aligner.align(fresh, m.cleaned())?.let { r to it } } }
                    unsure = all.filter { it.second.ambiguous }.maxByOrNull { it.second.score }?.first
                    all.filter { !it.second.ambiguous }.maxByOrNull { it.second.score }
                }
                looking = false
                if (found == null) { if (!quiet || unsure != null) note = unsure?.let { "This could be ${it.name}, but it fits more than one way round. Scan a part with a door or furniture." } ?: "This doesn't match a space you've saved. Scan more of it (walls and tall furniture) and try again." }
                else { match = found; note = null; host.senses.publish(Signal("spatial", "room_" + found.first.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'), "You're in ${found.first.name}")) }
            }
        }
        // Keep trying quietly as the scan grows, so a saved space is recognised without being asked.
        LaunchedEffect(st.keyFrames / 60, saved.size) { if (match == null && st.keyFrames >= 60) findPlace(true) }
        var naming by remember { mutableStateOf(false) }
        if (naming) {
            var name by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { naming = false }, title = { Text("Name this room") }, text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Living room, office…") }) },
                confirmButton = { TextButton({ naming = false; scope.launch { val r = withContext(Dispatchers.IO) { synchronized(scan.map) { store.save(name, scan.map, synchronized(scan.memory) { scan.memory.confirmed() }, st.stats) } }; if (r != null) onSaved(r.id) else note = "Not enough scanned yet to save." } }, enabled = name.isNotBlank()) { Text("Save") } },
                dismissButton = { TextButton({ naming = false }) { Text("Cancel") } })
        }

        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
                AndroidView({ c -> GLSurfaceView(c).apply { setEGLContextClientVersion(2); preserveEGLContextOnPause = true; setRenderer(scan); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY; gl = this } }, Modifier.fillMaxSize())
                // Taps measure: the first sets a point, the second gives the straight-line distance between the two.
                Canvas(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { o -> scan.tap(o.x / size.width, o.y / size.height) } }) {
                    val c = Offset(size.width / 2, size.height / 2)
                    drawCircle(Color.White, 14f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(3f)); drawCircle(Color.White, 3f, c)
                    val v = st.view; val p = st.proj
                    if (v != null && p != null) {
                        val pts = st.measure.mapNotNull { m -> Depth.toScreen(m, v, p)?.let { Offset(it.first * size.width, it.second * size.height) } }
                        pts.forEach { drawCircle(Color(0xFFFFC107), 12f, it); drawCircle(Color.Black, 5f, it) }
                        if (pts.size == 2 && st.measure.size == 2) {
                            drawLine(Color(0xFFFFC107), pts[0], pts[1], 5f, StrokeCap.Round)
                            val mid = Offset((pts[0].x + pts[1].x) / 2, (pts[0].y + pts[1].y) / 2)
                            drawContext.canvas.nativeCanvas.drawText("%.2f m".format(st.measure[0].dist(st.measure[1])), mid.x, mid.y - 18f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 20.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true; setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK) })
                        }
                    }
                }
                // Labels float on the things they name: found in this scan (white) and remembered from the matched room (green).
                val v = st.view; val p = st.proj
                if (v != null && p != null) {
                    val live = st.objects.map { it to false }
                    val remembered = match?.let { (room, a) -> room.objects.map { it.copy(at = a.invert(it.at)) to true } }.orEmpty().filter { (r, _) -> st.objects.none { it.label == r.label && it.at.dist(r.at) < 0.8f } }
                    (live + remembered).forEach { (o, old) ->
                        Depth.toScreen(o.at, v, p)?.takeIf { it.first in 0f..1f && it.second in 0f..1f }?.let { (sx, sy) ->
                            Text("${RoomLabels.emoji(o.label)} ${o.label}", Modifier.offset { IntOffset((sx * wPx).roundToInt() - 60, (sy * hPx).roundToInt() - 20) }.clip(RoundedCornerShape(50)).background((if (old) Color(0xFF1B7F4B) else Color.Black).copy(alpha = 0.65f)).padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
                var showDiag by remember { mutableStateOf(false) }
                Column(Modifier.align(UiAlign.TopStart).padding(10.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.6f)).clickable { showDiag = !showDiag }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(st.centreM?.let { (if (st.centreApprox) "≈ %.1f m to the dot (estimate)" else "%.2f m to the dot").format(it) } ?: "No depth at the dot", color = Color.White, fontWeight = FontWeight.Bold)
                    val s = st.stats
                    if (s == null) Text("Measuring… keep scanning", color = Color.White, fontSize = 12.sp)
                    else { Text(dims(s), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(detail(s), color = when (s.confidence) { Confidence.HIGH -> Color(0xFF7CF0A0); Confidence.MEDIUM -> Color(0xFFFFD166); else -> Color(0xFFFF9E80) }, fontSize = 11.sp) }
                    Text("${"%,d".format(st.solid)} points · ${st.objects.size} things · ${st.keyFrames} views" + if (st.depthSize.isNotEmpty()) " · depth ${st.depthSize} · ${st.fps.roundToInt()} fps" else "", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                    match?.let { (r, a) -> Text("📍 ${r.name} · ${(a.score * 100).roundToInt()}% fit · ±${"%.0f".format(a.rmsCm)} cm", color = Color(0xFF7CF0A0), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    if (showDiag) Text(st.diag.ifEmpty { "No depth pictures yet" } + "\nTap to hide", color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp, modifier = Modifier.width(260.dp))
                }
                (st.why ?: st.coach ?: note ?: st.stats?.notes?.firstOrNull())?.let { Text(it, Modifier.align(UiAlign.BottomCenter).padding(12.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White, fontSize = 13.sp) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = UiAlign.CenterVertically) {
                val m = match
                if (m == null) Button({ naming = true }, enabled = st.stats != null) { Text("Save") }
                else Button({ scope.launch {
                    val msg = withContext(Dispatchers.IO) {
                        val room = store.list().firstOrNull { it.id == m.first.id } ?: return@withContext null
                        val base = store.map(room.id) ?: return@withContext null
                        val fresh = synchronized(scan.map) { scan.map.mapped { it } }
                        val now = System.currentTimeMillis()
                        val res = Fusion.update(base, room.objects, fresh, synchronized(scan.memory) { scan.memory.confirmed() }, m.second, now) { java.util.UUID.randomUUID().toString() }
                        store.save(room.name, res.map, res.objects, res.stats ?: room.stats, room.id, room.at, room.kind, room.place, room.sessions + ScanSession(now, res.report.added, res.report.reinforced, res.report.removed, m.second.score, m.second.rmsCm, res.map.solid))
                        "${room.name} updated: ${"%,d".format(res.report.reinforced)} points confirmed, ${"%,d".format(res.report.added)} new, ${"%,d".format(res.report.removed)} cleared."
                    }
                    onChanged(); if (msg != null) scan.reset(); note = msg ?: "Couldn't update that space."
                } }) { Text("Add to ${m.first.name}") }
                if (saved.isNotEmpty() && m == null) FilledTonalButton({ findPlace(false) }, enabled = !looking && st.stats != null) { Text(if (looking) "Looking…" else "Have I been here?") }
                if (m != null) TextButton({ match = null; naming = true }) { Text("Save as new") }
                if (st.measure.isNotEmpty()) TextButton({ scan.clearMeasure() }) { Text("Clear measure") }
                TextButton({ scan.reset(); match = null; note = null }) { Text("Start over") }
            }
            if (!st.detector) Text("Object recognition isn't in this build, so things won't be labelled.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }

    /** The saved room from outside: drag to turn it, pinch to zoom. Colour is height above the floor. */
    @Composable
    private fun Viewer(room: SavedRoom, onRename: (String) -> Unit, onDelete: () -> Unit, onChanged: () -> Unit) {
        var pts by remember(room.id) { mutableStateOf<List<P3>?>(null) }
        LaunchedEffect(room.id, room.updatedAt) { pts = withContext(Dispatchers.IO) { store.map(room.id)?.cleaned()?.let { if (it.size > 30_000) it.filterIndexed { i, _ -> i % (it.size / 30_000 + 1) == 0 } else it } ?: emptyList() } }
        var yaw by remember { mutableFloatStateOf(35f) }; var pitch by remember { mutableFloatStateOf(35f) }; var zoom by remember { mutableFloatStateOf(0f) }
        var renaming by remember { mutableStateOf(false) }; var deleting by remember { mutableStateOf(false) }
        if (renaming) { var n by remember { mutableStateOf(room.name) }
            AlertDialog(onDismissRequest = { renaming = false }, title = { Text("Rename") }, text = { OutlinedTextField(n, { n = it }, singleLine = true) }, confirmButton = { TextButton({ onRename(n); renaming = false }, enabled = n.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton({ renaming = false }) { Text("Cancel") } }) }
        if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Forget ${room.name}?") }, text = { Text("Its shape and what was found in it are deleted from this phone.") }, confirmButton = { TextButton({ deleting = false; onDelete() }) { Text("Forget", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } })
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0E1116)).pointerInput(Unit) { detectTransformGestures { _, pan, z, _ -> yaw += pan.x * 0.3f; pitch = (pitch + pan.y * 0.3f).coerceIn(-10f, 89f); zoom = (zoom * z).coerceIn(20f, 2000f) } }) {
                val list = pts
                if (list == null) Text("Loading…", Modifier.align(UiAlign.Center), color = Color.White)
                else if (list.isEmpty()) Text("This room's shape couldn't be read.", Modifier.align(UiAlign.Center), color = Color.White)
                else Canvas(Modifier.fillMaxSize()) {
                    val cx = list.sumOf { it.x.toDouble() }.toFloat() / list.size; val cz = list.sumOf { it.z.toDouble() }.toFloat() / list.size
                    val floorY = room.stats.floorY ?: list.minOf { it.y }
                    val centre = P3(cx, floorY + (room.stats.height ?: 2.4f) / 2, cz)
                    if (zoom == 0f) zoom = size.minDimension / (((room.stats.length ?: (list.maxOf { it.x } - list.minOf { it.x })).coerceAtLeast(1f)) * 1.6f)
                    // Eight height bands, far side first is close enough for points this small.
                    val bands = Array(8) { ArrayList<Offset>() }
                    val span = (room.stats.height ?: 2.4f).coerceAtLeast(0.5f)
                    list.forEach { p -> val (sx, sy, _) = Orbit.project(p, centre, yaw, pitch, zoom); bands[(((p.y - floorY) / span) * 8).toInt().coerceIn(0, 7)] += Offset(size.width / 2 + sx, size.height / 2 + sy) }
                    bands.forEachIndexed { i, b -> if (b.isNotEmpty()) drawPoints(b, PointMode.Points, Color.hsv(160f + i * 14f, 0.7f, 1f), strokeWidth = (zoom * 0.05f).coerceIn(2f, 9f), cap = StrokeCap.Round) }
                    // The measured outline, drawn on the floor with its sizes: only the sides that were really measured wall to wall.
                    val u = room.stats.u; val v = room.stats.v
                    if (u != null && v != null && u.complete && v.complete) {
                        val a = Math.toRadians(room.stats.headingDeg.toDouble()); val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
                        fun corner(uu: Float, vv: Float): Offset { val (sx, sy, _) = Orbit.project(P3(uu * c - vv * s, floorY, uu * s + vv * c), centre, yaw, pitch, zoom); return Offset(size.width / 2 + sx, size.height / 2 + sy) }
                        val q = listOf(corner(u.lo, v.lo), corner(u.hi, v.lo), corner(u.hi, v.hi), corner(u.lo, v.hi))
                        val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 13.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true; setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK) }
                        for (i in 0..3) { val p0 = q[i]; val p1 = q[(i + 1) % 4]; drawLine(Color.White, p0, p1, 3f)
                            if (i < 2) drawContext.canvas.nativeCanvas.drawText("%.2f m".format(if (i == 0) u.size else v.size), (p0.x + p1.x) / 2, (p0.y + p1.y) / 2 - 8f, paint) }
                    }
                    room.objects.forEach { o -> val (sx, sy, _) = Orbit.project(o.at, centre, yaw, pitch, zoom); val at = Offset(size.width / 2 + sx, size.height / 2 + sy)
                        drawCircle(if (o.state == "here") Color.White else Color.Gray, 9f, at); drawContext.canvas.nativeCanvas.drawText("${RoomLabels.emoji(o.label)} ${o.label}", at.x + 14f, at.y + 6f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 14.sp.toPx(); setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK) }) }
                }
                Row(Modifier.align(UiAlign.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton({ pitch = 89f; yaw = -room.stats.headingDeg }) { Text("Plan") }; FilledTonalButton({ pitch = 30f; yaw = 35f }) { Text("3D") }
                }
                Text("Drag to turn · pinch to zoom", Modifier.align(UiAlign.TopCenter).padding(8.dp), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
            var describing by remember { mutableStateOf(false) }
            if (describing) { var kind by remember { mutableStateOf(room.kind) }; var place by remember { mutableStateOf(room.place) }
                AlertDialog(onDismissRequest = { describing = false }, title = { Text("What is this space?") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("room", "vehicle", "shelving", "other").forEach { k -> if (k == kind) Button({}) { Text("${kindEmoji(k)} $k") } else FilledTonalButton({ kind = k }) { Text("${kindEmoji(k)} $k") } } }
                    OutlinedTextField(place, { place = it }, singleLine = true, label = { Text("Part of (home, office, garage…)") })
                    Text("Spaces with the same \"part of\" are listed together.", style = MaterialTheme.typography.labelSmall) } },
                    confirmButton = { TextButton({ store.describe(room.id, kind, place); describing = false; onChanged() }) { Text("Save") } }, dismissButton = { TextButton({ describing = false }) { Text("Cancel") } }) }
            Column(Modifier.fillMaxWidth().height(250.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(dims(room.stats), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(detail(room.stats), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                room.stats.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(if (room.objects.isEmpty()) "Nothing recognised in it." else room.objects.joinToString(" · ") { "${RoomLabels.emoji(it.label)} ${it.label}" + if (it.state != "here") " (not seen last scan)" else "" }, style = MaterialTheme.typography.bodyMedium)
                Text("Scan history", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                room.sessions.reversed().forEachIndexed { i, x -> Text((if (i == room.sessions.size - 1) "First scan" else "Scan ${room.sessions.size - i}") + " · ${app.ultimate.core.relativeTime(x.at)}" + (if (i == room.sessions.size - 1) " · ${"%,d".format(x.voxels)} points" else " · ${(x.score * 100).roundToInt()}% fit, ±${"%.0f".format(x.rmsCm)} cm · ${"%,d".format(x.reinforced)} confirmed, ${"%,d".format(x.added)} new, ${"%,d".format(x.removed)} cleared"), style = MaterialTheme.typography.bodySmall) }
                Text("${"%,d".format(room.stats.voxels)} points of 5 cm · ${app.ultimate.core.formatBytes(store.bytes(room.id))} on this phone only", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState())) { TextButton({ renaming = true }) { Text("Rename") }; TextButton({ describing = true }) { Text("Details") }
                    if (store.canUndo(room.id)) TextButton({ store.undo(room.id); onChanged() }) { Text("Undo last scan") }
                    TextButton({ deleting = true }) { Text("Forget", color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
}
