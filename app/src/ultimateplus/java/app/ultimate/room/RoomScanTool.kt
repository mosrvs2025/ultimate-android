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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.delay
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
        var target by remember { mutableStateOf<String?>(null) }
        var viewing by remember { mutableStateOf<String?>(null) }
        val list by rooms.collectAsState()
        fun back() { if (scanning) { scanning = false; target = null; reload() } else if (viewing != null) viewing = null else onExit() }
        BackHandler { back() }
        Scaffold(topBar = { TopAppBar(title = { Text(if (scanning) (list.firstOrNull { it.id == target }?.let { "Scanning ${it.name}" } ?: "Scanning") else list.firstOrNull { it.id == viewing }?.name ?: "Room scan") }, navigationIcon = { IconButton({ back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when {
                    scanning -> ScanScreen(list, target, onSaved = { reload(); scanning = false; target = null; viewing = it }, onChanged = { reload() })
                    viewing != null -> list.firstOrNull { it.id == viewing }?.let { r -> Viewer(r, onScan = { target = r.id; scanning = true }, onChanged = { reload() }, onDeleted = { viewing = null; reload() }) } ?: run { viewing = null }
                    else -> RoomList(list, onScan = { target = null; scanning = true }, onOpen = { viewing = it })
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
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { onOpen(r.id) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${kindEmoji(r.kind)} ${r.name}" + if (r.place.isNotBlank()) "  ·  ${r.place}" else "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(dims(r.stats), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text("${r.sessions.size} scan${if (r.sessions.size == 1) "" else "s"} · confidence ${r.stats.confidence.name.lowercase()} · ${app.ultimate.core.relativeTime(r.updatedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val here = r.objects.filter { it.state == "here" }
                    if (here.isNotEmpty()) Text(here.groupingBy { it.label }.eachCount().entries.joinToString(" · ") { (l, n) -> "${RoomLabels.emoji(l)} ${if (n > 1) "$n× " else ""}$l" }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        return size + (s.height?.let { " · " + heightText(s) } ?: " · height not measured")
    }
    /** The height, and where it came from when that isn't plain depth: "≈" is estimated from the walls, "kept" is from an earlier scan, "marked" is yours. */
    private fun heightText(s: RoomStats): String = "%.2f m high".format(s.height ?: 0f) + when (s.ceilingSource) { "walls" -> " (≈ from wall tops)"; "kept" -> " (kept from an earlier scan)"; "you" -> " (marked by you)"; "plane" -> " (ARCore estimate)"; else -> "" }
    private fun detail(s: RoomStats) = (s.footprint?.let { "footprint %.1f m² · ".format(it) } ?: "") + "floor seen %.1f m² · confidence ${s.confidence.name.lowercase()}".format(s.floorSeen) + (if (s.floorSource == "you") " · floor marked by you" else "")

    private fun Context.activity(): Activity? { var c: Context? = this; while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun ScanScreen(saved: List<SavedRoom>, target: String?, onSaved: (String) -> Unit, onChanged: () -> Unit) {
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

        // Recognising a remembered room from its shape (only the one being rescanned, when there is one).
        val candidates = remember(saved, target) { if (target != null) saved.filter { it.id == target } else saved }
        var match by remember { mutableStateOf<Pair<SavedRoom, Alignment>?>(null) }
        var looking by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        /** A passing message (a mark was set, a space was updated): gone after a few seconds. [relocNote] is about recognising the space and is replaced whenever that is re-checked. */
        var note by remember { mutableStateOf<String?>(null) }
        var relocNote by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(note) { if (note != null) { val n = note; delay(6000); if (note == n) note = null } }
        var measureOn by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var expanded by remember { mutableStateOf(false) }
        var askHeight by remember { mutableStateOf(false) }
        LaunchedEffect(target) { saved.firstOrNull { it.id == target }?.let { scan.holdHeight(it.heightM) } }
        /** The first time this visit something was added to a saved space: later adds update the same scan-history entry instead of making a new one. */
        var visitAt by remember { mutableStateOf<Long?>(null) }
        /** Looks for the space(s) in what has been scanned. True when it settled on one. Things in common break a tie between two ways of fitting. */
        suspend fun locate(quiet: Boolean): Boolean {
            if (looking || candidates.isEmpty()) return false
            looking = true; if (!quiet) note = null
            var unsure: SavedRoom? = null; var tooLittle = false
            val found = withContext(Dispatchers.Default) {
                val fresh = synchronized(scan.map) { scan.map.cleaned() }
                if (fresh.size < 2500) { tooLittle = true; return@withContext null }
                val freshThings = synchronized(scan.memory) { scan.memory.confirmed() }
                val all = candidates.mapNotNull { r -> store.map(r.id)?.let { m -> Aligner.resolve(Aligner.alignAll(fresh, m.cleaned()), freshThings, r.objects.filter { it.state == "here" })?.let { r to it } } }
                unsure = all.filter { it.second.ambiguous }.maxByOrNull { it.second.score }?.first
                all.filter { !it.second.ambiguous }.maxByOrNull { it.second.score }
            }
            looking = false
            if (found != null) { match = found; relocNote = null; scan.holdHeight(found.first.heightM); host.senses.publish(Signal("spatial", "room_" + found.first.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'), "You're in ${found.first.name}")); return true }
            // Every check replaces the last message, so a stale "fits more than one way" can't stay up after the scan has moved on.
            relocNote = when { tooLittle -> if (quiet) null else "Scan a little more first: walls and furniture, a minute or so."
                unsure != null -> "This could be ${unsure!!.name}, but it fits more than one way round. Scan a part with a door or furniture."
                quiet -> null
                else -> "This doesn't match ${if (target != null) "that space" else "a space you've saved"} yet. Scan more of it (walls and tall furniture) and try again." }
            return false
        }
        fun findPlace(quiet: Boolean) { scope.launch { locate(quiet) } }
        // Keep trying quietly as the scan grows, so a saved space is recognised without being asked.
        // Also whenever a new thing is confirmed: two things in common settle a room that otherwise fits two ways.
        LaunchedEffect(st.keyFrames / 40, st.objects.size, candidates.size) { if (match == null && st.keyFrames >= 40) findPlace(true) }
        var naming by remember { mutableStateOf(false) }
        if (naming) {
            var name by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { naming = false }, title = { Text("Name this space") }, text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Living room, office…") }) },
                confirmButton = { TextButton({ naming = false; busy = true; scope.launch { val r = withContext(Dispatchers.IO) { synchronized(scan.map) { store.save(name, scan.map, synchronized(scan.memory) { scan.memory.confirmed() }, st.stats) } }; busy = false; if (r != null) onSaved(r.id) else note = "Not enough scanned yet to save." } }, enabled = name.isNotBlank()) { Text("Save") } },
                dismissButton = { TextButton({ naming = false }) { Text("Cancel") } })
        }
        if (askHeight) {
            var h by remember { mutableStateOf("") }
            fun set(v: Float?) { askHeight = false; note = if (v != null && v in 1.8f..6f && scan.markCeilingHeight(v)) "Ceiling set to %.2f m by you.".format(v) else "Needs a number between 1.8 and 6 m, and the floor found first." }
            AlertDialog(onDismissRequest = { askHeight = false }, title = { Text("How high is the ceiling?") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("There isn't a distance at the dot to take it from. Pick the height from the floor (most homes are 2.4 to 2.7 m).", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(2.4f to "2.4 m · 8 ft", 2.7f to "2.7 m · 9 ft", 3.0f to "3.0 m · 10 ft").forEach { (v, l) -> FilledTonalButton({ set(v) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) { Text(l, fontSize = 12.sp) } } }
                OutlinedTextField(h, { h = it }, singleLine = true, label = { Text("Or type it, in metres") }) } },
                confirmButton = { TextButton({ set(h.replace(',', '.').toFloatOrNull()) }, enabled = h.isNotBlank()) { Text("Set") } }, dismissButton = { TextButton({ askHeight = false }) { Text("Cancel") } })
        }
        fun markCeiling() { when (scan.markCeiling()) { MarkResult.SET -> note = "Ceiling set where the dot was."; else -> askHeight = true } }
        fun markFloor() { note = when (scan.markFloor()) { MarkResult.SET -> "Floor set where the dot was."; MarkResult.NO_DEPTH -> "No distance at the dot right now: aim it at the floor and hold still."; else -> "That spot is higher than the phone has been, so it can't be the floor." } }

        /** Folds the live scan into the saved space it was recognised as, and keeps scanning: nothing is reset. */
        fun addToSaved() {
            val m = match ?: return; if (busy) return
            busy = true
            scope.launch {
                val msg = withContext(Dispatchers.IO) {
                    val room = store.list().firstOrNull { it.id == m.first.id } ?: return@withContext null
                    val base = store.map(room.id) ?: return@withContext null
                    val fresh = synchronized(scan.map) { scan.map.mapped { it } }
                    val now = System.currentTimeMillis()
                    // The alignment is polished against the saved shape every time, not carried over from the first match.
                    val al = Aligner.refine(m.second, fresh.cleaned(), base.cleaned())
                    val res = Fusion.update(base, room.objects, fresh, synchronized(scan.memory) { scan.memory.confirmed() }, al, now, room.heightM, st.stats?.trustedHeight) { java.util.UUID.randomUUID().toString() }
                    val first = visitAt == null; val at = visitAt ?: now
                    val prev = room.sessions.lastOrNull()?.takeIf { !first && it.at == at }
                    val entry = ScanSession(at, (prev?.added ?: 0) + res.report.added, res.report.reinforced, (prev?.removed ?: 0) + res.report.removed, al.score, al.rmsCm, res.map.solid)
                    store.save(room.name, res.map, res.objects, res.stats ?: room.stats, room.id, room.at, room.kind, room.place, (if (prev != null) room.sessions.dropLast(1) else room.sessions) + entry, now, res.heightM, snapshot = first)
                    visitAt = at; match = room to al
                    "${room.name} updated: ${"%,d".format(res.report.reinforced)} points confirmed, ${"%,d".format(res.report.added)} new, ${"%,d".format(res.report.removed)} cleared · fit ±${"%.0f".format(al.rmsCm)} cm. Keep scanning to add more."
                }
                busy = false; onChanged(); note = msg ?: "Couldn't update that space."
            }
        }

        /** Update the space this scan was started from: line up first if that hasn't happened yet, and say so plainly if it can't be done. Never asks for a name. */
        fun updateTarget() {
            scope.launch {
                if (match == null) { busy = true; val ok = locate(false); busy = false; if (!ok) { if (relocNote == null) relocNote = "Couldn't line this scan up with it yet: scan the walls, a door or the furniture."; return@launch } }
                addToSaved()
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
            AndroidView({ c -> GLSurfaceView(c).apply { setEGLContextClientVersion(2); preserveEGLContextOnPause = true; setRenderer(scan); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY; gl = this } }, Modifier.fillMaxSize())
            // Taps measure: the first sets a point, the second gives the straight-line distance between the two.
            Canvas(Modifier.fillMaxSize().pointerInput(measureOn) { if (measureOn) detectTapGestures { o -> scan.tap(o.x / size.width, o.y / size.height) } }) {
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
            // Labels float on the things they name: found in this scan (white) and remembered from the matched space (green, only ones last seen there).
            val v = st.view; val p = st.proj
            if (v != null && p != null) {
                val live = st.objects.map { it to false }
                val remembered = match?.let { (room, a) -> room.objects.filter { it.state == "here" }.map { it.copy(at = a.invert(it.at)) to true } }.orEmpty().filter { (r, _) -> st.objects.none { it.label == r.label && it.at.dist(r.at) < 1.5f * RoomLabels.radius(r.label) + 0.2f } }
                (live + remembered).forEach { (o, old) ->
                    Depth.toScreen(o.at, v, p)?.takeIf { it.first in 0f..1f && it.second in 0f..1f }?.let { (sx, sy) ->
                        Text("${RoomLabels.emoji(o.label)} ${o.label}", Modifier.offset { IntOffset((sx * wPx).roundToInt() - 60, (sy * hPx).roundToInt() - 20) }.clip(RoundedCornerShape(50)).background((if (old) Color(0xFF1B7F4B) else Color.Black).copy(alpha = 0.65f)).padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White, fontSize = 13.sp)
                    }
                }
            }
            // One small readout: distance and size at a glance; tap it for everything else.
            Column(Modifier.align(UiAlign.TopStart).padding(10.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.55f)).clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(st.centreM?.let { (if (st.centreApprox) "≈ %.1f m to the dot (estimate)" else "%.2f m to the dot").format(it) } ?: "No depth at the dot", color = Color.White, fontWeight = FontWeight.Bold)
                val sx = st.stats
                if (sx == null) Text("Measuring… keep scanning", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                else Text(dims(sx), color = when (sx.confidence) { Confidence.HIGH -> Color(0xFF7CF0A0); Confidence.MEDIUM -> Color(0xFFFFD166); else -> Color.White }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (sx != null && (sx.floorSource == "you" || sx.ceilingSource == "you")) Text("✕ Reset floor / ceiling you marked", Modifier.clickable { scan.clearMarks() }.padding(vertical = 2.dp), color = Color(0xFFFFD166), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                match?.let { (r, a) -> Text("📍 ${r.name} · fit ±${"%.0f".format(a.rmsCm)} cm", color = Color(0xFF7CF0A0), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                if (expanded) {
                    sx?.let { Text(detail(it), color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp); it.notes.forEach { n -> Text("• $n", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, modifier = Modifier.width(250.dp)) } }
                    Text("${"%,d".format(st.solid)} points · ${st.objects.size} things · ${st.keyFrames} views" + if (st.depthSize.isNotEmpty()) " · depth ${st.depthSize} · ${st.fps.roundToInt()} fps" else "", color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp)
                    Text(st.diag.ifEmpty { "No depth pictures yet" }, color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp, modifier = Modifier.width(250.dp))
                }
            }
            // Aimed up or down at something that is not measured yet: one tap says what it is.
            val sx = st.stats
            val chip = when {
                st.pitchDeg > 15f && sx?.ceilingY == null -> "Tap to mark the ceiling" to { markCeiling() }
                st.pitchDeg < -15f && sx?.floorY == null -> "Tap to mark the floor" to { markFloor() }
                else -> null
            }
            chip?.let { (label, act) -> Text(label, Modifier.align(UiAlign.Center).offset(y = 56.dp).clip(RoundedCornerShape(50)).background(Color(0xFF3D6CF2)).clickable { act() }.padding(horizontal = 16.dp, vertical = 9.dp), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            if (measureOn) Row(Modifier.align(UiAlign.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFFC107)).padding(horizontal = 14.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = UiAlign.CenterVertically) {
                Text(if (st.measure.size == 1) "📏 Tap the second point" else "📏 Tap two points", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("✕ Done", Modifier.clickable { measureOn = false; scan.clearMeasure() }, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            // The one message that matters now, above the controls.
            (st.why ?: st.coach ?: note ?: relocNote.takeIf { match == null } ?: sx?.notes?.firstOrNull())?.let { Text(it, Modifier.align(UiAlign.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 84.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            // Controls: one main action and a menu.
            Row(Modifier.align(UiAlign.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = UiAlign.CenterVertically) {
                val m = match
                // Started from a saved space, this is always an update of that space and never asks for a name; otherwise it saves a new one.
                val to = m?.first ?: saved.firstOrNull { it.id == target }
                Button({ if (to == null) naming = true else if (m != null) addToSaved() else updateTarget() }, Modifier.weight(1f).height(54.dp), enabled = !busy && !looking && st.stats != null, shape = RoundedCornerShape(27.dp)) {
                    Icon(if (to == null) Icons.Rounded.ViewInAr else Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(when { busy -> "Saving…"; looking -> "Lining up…"; to == null -> "Save space"; else -> "Update ${to.name}" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton({ menu = true }, Modifier.size(54.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))) { Icon(Icons.Rounded.MoreVert, "More", tint = Color.White) }
                    DropdownMenu(menu, { menu = false }) {
                        if (m != null || target != null) DropdownMenuItem({ Text("Save as a new space instead") }, { menu = false; match = null; naming = true })
                        if (m == null && target == null && candidates.isNotEmpty()) DropdownMenuItem({ Text(if (looking) "Looking…" else "Have I been here?") }, { menu = false; findPlace(false) }, enabled = !looking && st.stats != null)
                        DropdownMenuItem({ Text("Mark floor at the dot") }, { menu = false; markFloor() })
                        DropdownMenuItem({ Text("Mark ceiling at the dot") }, { menu = false; markCeiling() })
                        DropdownMenuItem({ Text(if (measureOn) "Stop measuring distances" else "Measure a distance") }, { menu = false; measureOn = !measureOn; if (!measureOn) scan.clearMeasure() })
                        if (sx != null && (sx.floorSource == "you" || sx.ceilingSource == "you")) DropdownMenuItem({ Text("Reset floor / ceiling marks") }, { menu = false; scan.clearMarks() })
                        DropdownMenuItem({ Text("Show diagnostics") }, { menu = false; expanded = true })
                        DropdownMenuItem({ Text("Start over", color = MaterialTheme.colorScheme.error) }, { menu = false; scan.reset(); match = null; note = null; relocNote = null; visitAt = null })
                    }
                }
            }
            if (!st.detector) Text("Object recognition isn't in this build, so things won't be labelled.", Modifier.align(UiAlign.TopEnd).padding(10.dp), color = Color(0xFFFF9E80), fontSize = 11.sp)
        }
    }

    /** One of the four actions under the viewer: an icon over a short label in a rounded tile. */
    @Composable
    private fun ActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val c = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp), horizontalAlignment = UiAlign.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(icon, null, tint = c.copy(alpha = if (enabled) 1f else 0.35f)); Text(label, fontSize = 11.sp, color = c.copy(alpha = if (enabled) 1f else 0.35f))
        }
    }

    /** The saved space from outside, filling the screen: drag to turn, pinch to zoom, with the actions in a card underneath. */
    @Composable
    private fun Viewer(room: SavedRoom, onScan: () -> Unit, onChanged: () -> Unit, onDeleted: () -> Unit) {
        var pts by remember(room.id) { mutableStateOf<List<P3>?>(null) }
        LaunchedEffect(room.id, room.updatedAt) { pts = withContext(Dispatchers.IO) { store.map(room.id)?.cleaned()?.let { if (it.size > 30_000) it.filterIndexed { i, _ -> i % (it.size / 30_000 + 1) == 0 } else it } ?: emptyList() } }
        var yaw by remember { mutableFloatStateOf(35f) }; var pitch by remember { mutableFloatStateOf(35f) }; var zoom by remember { mutableFloatStateOf(0f) }
        var dialog by remember { mutableStateOf("") }
        var showMissing by remember { mutableStateOf(false) }
        val here = room.objects.filter { it.state == "here" }
        when (dialog) {
            "rename" -> { var n by remember { mutableStateOf(room.name) }; var kind by remember { mutableStateOf(room.kind) }; var place by remember { mutableStateOf(room.place) }
                AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Name and type") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(n, { n = it }, singleLine = true, label = { Text("Name") })
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("room", "vehicle", "shelving", "other").forEach { k -> if (k == kind) Button({}) { Text("${kindEmoji(k)} $k") } else FilledTonalButton({ kind = k }) { Text("${kindEmoji(k)} $k") } } }
                    OutlinedTextField(place, { place = it }, singleLine = true, label = { Text("Part of (home, office, garage…)") }) } },
                    confirmButton = { TextButton({ store.rename(room.id, n); store.describe(room.id, kind, place); dialog = ""; onChanged() }, enabled = n.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton({ dialog = "" }) { Text("Cancel") } }) }
            "forget" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Forget ${room.name}?") }, text = { Text("Its shape and what was found in it are deleted from this phone.") }, confirmButton = { TextButton({ store.delete(room.id); dialog = ""; onDeleted() }) { Text("Forget", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ dialog = "" }) { Text("Cancel") } })
            "log" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Scan log") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                room.sessions.reversed().forEachIndexed { i, x -> Column { Text((if (i == room.sessions.size - 1) "First scan" else "Scan ${room.sessions.size - i}") + " · ${app.ultimate.core.relativeTime(x.at)}", fontWeight = FontWeight.SemiBold)
                    Text(if (i == room.sessions.size - 1) "${"%,d".format(x.voxels)} points" else "fit ±${"%.0f".format(x.rmsCm)} cm · ${"%,d".format(x.reinforced)} confirmed, ${"%,d".format(x.added)} new, ${"%,d".format(x.removed)} cleared", style = MaterialTheme.typography.bodySmall) } }
                Text("${"%,d".format(room.stats.voxels)} points of 5 cm · ${app.ultimate.core.formatBytes(store.bytes(room.id))} on this phone only", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                confirmButton = { TextButton({ dialog = "" }) { Text("Close") } })
            "things" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Things in ${room.name}") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Wrong label? Tap it to change it, or remove it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                (if (showMissing) room.objects else here).sortedByDescending { it.seen }.forEach { o -> var open by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 6.dp), verticalAlignment = UiAlign.CenterVertically) {
                        Text("${RoomLabels.emoji(o.label)} ${o.label}" + if (o.state != "here") "  ·  not found last scan" else "  ·  seen ${o.seen}×", Modifier.weight(1f))
                        IconButton({ store.editObjects(room.id) { l -> l.filter { it.id != o.id } }; onChanged() }) { Icon(Icons.Rounded.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                        DropdownMenu(open, { open = false }) { (listOf(RoomLabels.CABINET) + RoomLabels.KEEP.sorted()).forEach { l -> DropdownMenuItem({ Text("${RoomLabels.emoji(l)} $l") }, { open = false; store.editObjects(room.id) { list -> list.map { if (it.id == o.id) it.copy(label = l) else it } }; onChanged() }) } } } }
                if (here.isEmpty()) Text("Nothing recognised yet.")
                if (room.objects.size > here.size) TextButton({ showMissing = !showMissing }) { Text(if (showMissing) "Hide ones not found last scan" else "Show ${room.objects.size - here.size} not found last scan") } } },
                confirmButton = { TextButton({ dialog = "" }) { Text("Close") } })
            "notes" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("What's missing") }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { room.stats.notes.forEach { Text("• $it") } } }, confirmButton = { TextButton({ dialog = "" }) { Text("Close") } })
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0E1116)).pointerInput(Unit) { detectTransformGestures { _, pan, z, _ -> yaw += pan.x * 0.3f; pitch = (pitch + pan.y * 0.3f).coerceIn(-10f, 89f); zoom = (zoom * z).coerceIn(20f, 2000f) } }) {
                val list = pts
                if (list == null) Text("Loading…", Modifier.align(UiAlign.Center), color = Color.White)
                else if (list.isEmpty()) Text("This room's shape couldn't be read.", Modifier.align(UiAlign.Center), color = Color.White)
                else Canvas(Modifier.fillMaxSize()) {
                    // Turn about the middle of what was scanned (the median, so a stray cluster can't pull the pivot away).
                    fun mid(f: (P3) -> Float) = list.map(f).sorted()[list.size / 2]
                    val cx = mid { it.x }; val cz = mid { it.z }
                    val floorY = room.stats.floorY ?: list.minOf { it.y }
                    val centre = P3(cx, mid { it.y }, cz)
                    if (zoom == 0f) zoom = size.minDimension / (((room.stats.length ?: (list.maxOf { it.x } - list.minOf { it.x })).coerceAtLeast(1f)) * 1.6f)
                    fun at(p: P3): Offset { val (sx, sy, _) = Orbit.project(p, centre, yaw, pitch, zoom); return Offset(size.width / 2 + sx, size.height / 2 + sy) }
                    // A faint half-metre grid on the floor, so the shape sits on something and has a scale.
                    run { val x0 = kotlin.math.floor(list.minOf { it.x } * 2) / 2; val x1 = kotlin.math.ceil(list.maxOf { it.x } * 2) / 2; val z0 = kotlin.math.floor(list.minOf { it.z } * 2) / 2; val z1 = kotlin.math.ceil(list.maxOf { it.z } * 2) / 2
                        var gx = x0; while (gx <= x1 + 0.01f) { drawLine(Color.White.copy(alpha = 0.12f), at(P3(gx, floorY, z0)), at(P3(gx, floorY, z1)), 1f); gx += 0.5f }
                        var gz = z0; while (gz <= z1 + 0.01f) { drawLine(Color.White.copy(alpha = 0.12f), at(P3(x0, floorY, gz)), at(P3(x1, floorY, gz)), 1f); gz += 0.5f } }
                    val bands = Array(8) { ArrayList<Offset>() }
                    val span = (room.stats.height ?: 2.4f).coerceAtLeast(0.5f)
                    list.forEach { p -> bands[(((p.y - floorY) / span) * 8).toInt().coerceIn(0, 7)] += at(p) }
                    bands.forEachIndexed { i, b -> if (b.isNotEmpty()) drawPoints(b, PointMode.Points, Color.hsv(160f + i * 14f, 0.7f, 1f), strokeWidth = (zoom * 0.05f).coerceIn(2f, 9f), cap = StrokeCap.Round) }
                    // The measured outline, drawn on the floor with its sizes: only the sides that were really measured wall to wall.
                    val u = room.stats.u; val v = room.stats.v
                    if (u != null && v != null && u.complete && v.complete) {
                        val a = Math.toRadians(room.stats.headingDeg.toDouble()); val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
                        fun corner(uu: Float, vv: Float) = at(P3(uu * c - vv * s, floorY, uu * s + vv * c))
                        val q = listOf(corner(u.lo, v.lo), corner(u.hi, v.lo), corner(u.hi, v.hi), corner(u.lo, v.hi))
                        val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 13.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true; setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK) }
                        for (i in 0..3) { val p0 = q[i]; val p1 = q[(i + 1) % 4]; drawLine(Color.White, p0, p1, 3f)
                            if (i < 2) drawContext.canvas.nativeCanvas.drawText("%.2f m".format(if (i == 0) u.size else v.size), (p0.x + p1.x) / 2, (p0.y + p1.y) / 2 - 8f, paint) }
                    }
                    // Things: always a dot, and a name only where there is room for it (most-seen first), so they never pile into one blob.
                    val lp = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 13.sp.toPx(); setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK) }
                    val taken = ArrayList<androidx.compose.ui.geometry.Rect>()
                    here.sortedByDescending { it.seen }.forEach { o ->
                        val d = at(o.at); drawCircle(Color.White, 7f, d)
                        val text = "${RoomLabels.emoji(o.label)} ${o.label}"; val w = lp.measureText(text); val r = androidx.compose.ui.geometry.Rect(d.x + 10f, d.y - lp.textSize, d.x + 14f + w, d.y + 6f)
                        if (taken.none { it.overlaps(r) }) { taken += r; drawContext.canvas.nativeCanvas.drawText(text, d.x + 12f, d.y + 4f, lp) }
                    }
                }
                // Three honest numbers across the top; the middle one says plainly when the height is not known.
                Row(Modifier.align(UiAlign.TopCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fun chip(text: String, warn: Boolean = false, onClick: (() -> Unit)? = null) = @Composable { Text(text, Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).let { if (onClick != null) it.clickable { onClick() } else it }.padding(horizontal = 12.dp, vertical = 6.dp), color = if (warn) Color(0xFFFFD166) else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    val l = room.stats.length; val w = room.stats.width
                    chip(if (l != null && w != null) "📐 %.1f × %.1f m".format(l, w) else "📐 size not measured", l == null || w == null) { dialog = "notes" }()
                    chip(room.stats.height?.let { "↕ %.2f m".format(it) + when (room.stats.ceilingSource) { "walls", "plane" -> " ≈"; else -> "" } } ?: "↕ height not measured", room.stats.height == null) { dialog = "notes" }()
                    chip("${RoomLabels.emoji(here.firstOrNull()?.label ?: "")} ${here.size} thing${if (here.size == 1) "" else "s"}") { dialog = "things" }()
                }
                Row(Modifier.align(UiAlign.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton({ pitch = 89f; yaw = -room.stats.headingDeg }) { Text("Plan") }; FilledTonalButton({ pitch = 30f; yaw = 35f }) { Text("3D") }
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${kindEmoji(room.kind)} ${room.name}" + (if (room.place.isNotBlank()) "  ·  ${room.place}" else "") + "  ·  ${room.sessions.size} scan${if (room.sessions.size == 1) "" else "s"}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail(room.stats) + (room.stats.notes.firstOrNull()?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { dialog = "notes" })
                Button(onScan, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Scan again to improve") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile(Icons.Rounded.Edit, "Rename", modifier = Modifier.weight(1f)) { dialog = "rename" }
                    ActionTile(Icons.Rounded.Undo, "Undo scan", enabled = store.canUndo(room.id), modifier = Modifier.weight(1f)) { store.undo(room.id); onChanged() }
                    ActionTile(Icons.Rounded.History, "Scan log (${room.sessions.size})", modifier = Modifier.weight(1f)) { dialog = "log" }
                    ActionTile(Icons.Rounded.Delete, "Forget", danger = true, modifier = Modifier.weight(1f)) { dialog = "forget" }
                }
            }
        }
    }
}
