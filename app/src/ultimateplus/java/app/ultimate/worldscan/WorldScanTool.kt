package app.ultimate.worldscan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.formatBytes
import app.ultimate.core.relativeTime
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The palette of the World Scan screens: dark and calm, with one accent per kind of information. */
internal object WorldColors {
    val bg = Color(0xFF0B0F14); val card = Color(0xFF151B23); val line = Color(0xFF263040)
    val teal = Color(0xFF3DDCD0); val green = Color(0xFF6CE59A); val amber = Color(0xFFFFC857); val red = Color(0xFFFF6B6B); val violet = Color(0xFF9D8CFF); val dim = Color(0xFF8A97A8)
}

/**
 * World Scan: walk down a street (or round a building, a park, a campus) with the phone held up, and it builds a 3D map of what it sees from
 * ARCore's motion tracking and depth, notes where on Earth the walk was from GPS, keeps every walk's raw record, and lets you come back
 * and walk it again to make the map better. Local only: nothing is uploaded, and exports happen only when you share one.
 *
 * See docs/WORLD_SCAN.md for what it can and cannot do (it is a research instrument, not a survey tool).
 */
class WorldScanTool(private val host: UltimateApp) : Tool {
    internal val store = WalkStore(File(host.filesDir, "worldscan")).also { runCatching { it.tidyIncoming() } }
    internal val places = MutableStateFlow(store.places())
    override val id = "worldscan"
    override val title = "World Scan"
    override val tagline = "Walk a street, map it in 3D, and improve it every time you return"
    override val icon = Icons.Rounded.Public
    private val _headline = MutableStateFlow(headlineOf(places.value))
    override val headline: StateFlow<String> = _headline
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override val acceptedMimePrefixes: List<String> = emptyList()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    private fun headlineOf(l: List<Place>): String {
        if (l.isEmpty()) return "No walks yet"
        val km = l.sumOf { p -> p.walks.sumOf { it.lengthM } } / 1000.0
        return "${l.size} place${if (l.size == 1) "" else "s"} · ${"%.1f".format(km)} km walked"
    }
    internal fun reload() { places.value = store.places(); _headline.value = headlineOf(places.value) }

    private sealed class Mode { object List : Mode(); class Capture(val placeId: String?) : Mode(); class View(val placeId: String) : Mode() }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        var mode by remember { mutableStateOf<Mode>(Mode.List) }
        val list by places.collectAsState()
        val m = mode
        // While capturing, the capture screen owns "back" (it asks before throwing a walk away).
        BackHandler(m !is Mode.Capture) { if (m is Mode.View) mode = Mode.List else onExit() }
        Scaffold(containerColor = WorldColors.bg, topBar = {
            if (m !is Mode.Capture) TopAppBar(title = { Text(if (m is Mode.View) list.firstOrNull { it.id == m.placeId }?.name ?: "World Scan" else "World Scan", color = Color.White) },
                navigationIcon = { IconButton({ if (m is Mode.View) mode = Mode.List else onExit() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) } },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = WorldColors.bg))
        }) { pad ->
            Box(Modifier.fillMaxSize().padding(if (m is Mode.Capture) androidx.compose.foundation.layout.PaddingValues(0.dp) else pad)) {
                when (m) {
                    is Mode.List -> WorldHome(list, onWalk = { mode = Mode.Capture(null) }, onOpen = { mode = Mode.View(it) })
                    is Mode.Capture -> CaptureScreen(m.placeId, list, onSaved = { reload(); mode = Mode.View(it) }, onCancel = { reload(); mode = if (m.placeId != null) Mode.View(m.placeId) else Mode.List })
                    is Mode.View -> list.firstOrNull { it.id == m.placeId }?.let { p -> WorldViewer(this@WorldScanTool, p, onWalk = { mode = Mode.Capture(p.id) }, onChanged = { reload() }, onDeleted = { reload(); mode = Mode.List }) } ?: run { mode = Mode.List }
                }
            }
        }
    }

    // ------------------------------------------------------------------ home
    @Composable
    private fun WorldHome(list: List<Place>, onWalk: () -> Unit, onOpen: (String) -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF12323A), Color(0xFF1B1F3A)))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Walk. Map. Come back and make it better.", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
                Text("The camera, depth sensor, motion tracking and GPS build a 3D map of the walk. Each time you walk it again, the new walk is lined up with the old one and only added if it truly fits.", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, lineHeight = 18.sp)
                Button(onWalk, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(27.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Start a walk", fontWeight = FontWeight.Bold) }
                Text("Stays on this phone · nothing is uploaded · you choose what to share", color = WorldColors.teal, fontSize = 11.sp)
            }
            if (list.isEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(WorldColors.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("How to get a good walk", color = Color.White, fontWeight = FontWeight.Bold)
                    listOf("Hold the phone upright at chest height and look ahead at buildings, fences and walls. The pavement alone gives the map nothing to hold on to.",
                        "Walk at a normal pace and keep the camera steady: depth blurs when the phone swings.",
                        "Walk outside in the open for the first minute with location on, so the walk can be placed on a map. Indoors or between tall buildings it still maps, but can't be placed.",
                        "To improve a place later, start near where you started and walk the same route the same way. Walking it backwards shows the other side of the street, which doesn't overlap.").forEach { Text("• $it", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, lineHeight = 17.sp) }
                }
            } else list.forEach { p -> PlaceCard(p) { onOpen(p.id) } }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(WorldColors.card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What it can't do (yet)", color = WorldColors.amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("It is a research instrument, not a survey tool. Motion tracking drifts about 1% of the distance walked and GPS is good to metres, so a map is accurate locally (centimetres to tens of centimetres at the range depth reaches, about 5 m) and only roughly placed on Earth. It does not close loops, so a long walk that returns to its start will not meet itself exactly. Details are in the Details tab of every walk.", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }

    @Composable
    private fun PlaceCard(p: Place, onClick: () -> Unit) {
        val base = p.walks.firstOrNull { it.id == p.baseWalkId }
        var thumb by remember(p.id, p.updatedAt) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(p.id, p.updatedAt) { thumb = withContext(Dispatchers.Default) { store.baseMap(p.id)?.let { m -> TopDown.render(listOf(m), cell = 0.5f, maxCells = 40_000)?.let { bitmapOf(it) } } } }
        val merged = p.walks.count { it.merge != null }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(WorldColors.card).clickable { onClick() }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B0F14)), contentAlignment = UiAlign.Center) {
                thumb?.let { Canvas(Modifier.fillMaxSize()) { val s = min(size.width / it.width, size.height / it.height); drawImage(it, dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - it.width * s) / 2).roundToInt(), ((size.height - it.height * s) / 2).roundToInt()), dstSize = androidx.compose.ui.unit.IntSize((it.width * s).roundToInt(), (it.height * s).roundToInt())) } } ?: CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(p.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text("${p.walks.size} walk${if (p.walks.size == 1) "" else "s"} · ${"%,.0f".format(p.walks.sumOf { it.lengthM })} m" + if (merged > 0) " · $merged added" else "", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                Text(base?.geo?.let { g -> if (g.usable) "📍 On the map ±${"%.0f".format(g.yawSigmaDeg)}° heading" else "Not placed on the map" } ?: "No GPS with this walk", color = if (base?.geo?.usable == true) WorldColors.green else WorldColors.dim, fontSize = 11.sp)
                Text("${relativeTime(p.updatedAt)} · ${formatBytes(store.bytes(p.id))} on this phone", color = WorldColors.dim, fontSize = 11.sp)
            }
        }
    }

    // ------------------------------------------------------------------ capture
    private fun Context.activity(): Activity? { var c: Context? = this; while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }

    @Composable
    private fun CaptureScreen(placeId: String?, places: List<Place>, onSaved: (String) -> Unit, onCancel: () -> Unit) {
        val context = LocalContext.current; val scope = rememberCoroutineScope()
        fun has(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        var camera by remember { mutableStateOf(has(Manifest.permission.CAMERA)) }
        var location by remember { mutableStateOf(has(Manifest.permission.ACCESS_FINE_LOCATION)) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> camera = has(Manifest.permission.CAMERA); location = has(Manifest.permission.ACCESS_FINE_LOCATION) }
        LaunchedEffect(Unit) { if (!camera || !location) ask.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        if (!camera) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = UiAlign.CenterHorizontally) {
                Text("World Scan uses the camera. Pictures are looked at on the phone and dropped; only depth, shape and the path are kept.", color = Color.White, lineHeight = 20.sp)
                Spacer(Modifier.height(12.dp)); Button({ ask.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Allow the camera") }
                TextButton(onCancel) { Text("Back", color = WorldColors.dim) }
            }
            return
        }
        var session by remember { mutableStateOf<Session?>(null) }
        var problem by remember { mutableStateOf<String?>(null) }
        var retry by remember { mutableIntStateOf(0) }
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(retry) {
            if (session != null) return@LaunchedEffect
            val act = context.activity() ?: run { problem = "World Scan can't start from here."; return@LaunchedEffect }
            try {
                if (ArCoreApk.getInstance().requestInstall(act, true) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) { problem = "Install or update \"Google Play Services for AR\", then come back."; return@LaunchedEffect }
                session = Session(act); problem = null
            } catch (e: com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException) { problem = "This phone doesn't support ARCore, so it can't map a walk."
            } catch (e: com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException) { problem = "World Scan needs \"Google Play Services for AR\" from the Play Store."
            } catch (e: Exception) { problem = "ARCore couldn't start: ${e.message ?: e.javaClass.simpleName}" }
        }
        val s = session
        if (s == null) { Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { Text(problem ?: "Starting the camera…", color = Color.White); if (problem != null) { Spacer(Modifier.height(10.dp)); Button({ retry++ }) { Text("Try again") }; TextButton(onCancel) { Text("Back", color = WorldColors.dim) } } }; return }

        val scan = remember(s) { WalkScan(context, s, store) }
        val st by scan.state.collectAsState()
        val view = LocalView.current
        var gl by remember { mutableStateOf<GLSurfaceView?>(null) }
        var saving by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<SaveResult?>(null) }
        var tooShort by remember { mutableStateOf(false) }
        var leave by remember { mutableStateOf(false) }
        var startError by remember { mutableStateOf<String?>(null) }
        var failure by remember { mutableStateOf<String?>(null) }
        DisposableEffect(s, owner) {
            view.keepScreenOn = true
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) { runCatching { s.resume() }.onFailure { problem = "The camera is in use by something else." }; gl?.onResume() }
                if (e == Lifecycle.Event.ON_PAUSE) { scan.endForBackground(); gl?.onPause(); runCatching { s.pause() } }
            }
            owner.lifecycle.addObserver(obs)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) runCatching { s.resume() }
            onDispose { view.keepScreenOn = false; owner.lifecycle.removeObserver(obs); gl?.onPause(); runCatching { s.pause() }; scan.close(); Thread { runCatching { s.close() } }.start() }
        }
        fun finish() {
            if (saving) return; saving = true
            scope.launch {
                val input = withContext(Dispatchers.Default) { scan.finish() }
                if (input == null || input.track.lengthM < 5.0) { tooShort = true; saving = false; withContext(Dispatchers.IO) { input?.rawLog?.delete() }; return@launch }
                val r = try { withContext(Dispatchers.IO) { store.save(input, placeId) } } catch (e: Throwable) { failure = "The walk couldn't be stored: ${e.message ?: e.javaClass.simpleName}. Free some space and try another walk."; null }
                result = r; saving = false
            }
        }
        // A walk that ended by itself (tracking lost for good) is saved at once: what was captured is worth keeping.
        LaunchedEffect(st.phase, st.endedBecause) { if (st.phase == WalkPhase.ENDED && st.endedBecause != null && !saving && result == null && !tooShort) finish() }
        BackHandler { if (st.phase == WalkPhase.RECORDING) leave = true else { scan.discard(); onCancel() } }
        val target = places.firstOrNull { it.id == placeId }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView({ c -> GLSurfaceView(c).apply { setEGLContextClientVersion(2); preserveEGLContextOnPause = true; setRenderer(scan); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY; gl = this } }, Modifier.fillMaxSize())
            // --- top: what is happening
            Row(Modifier.align(UiAlign.TopStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = UiAlign.CenterVertically) {
                IconButton({ if (st.phase == WalkPhase.RECORDING) leave = true else { scan.discard(); onCancel() } }, Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                if (st.phase == WalkPhase.RECORDING) Glass { Text("● ${clockText(st.seconds)}", color = WorldColors.red, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
            Column(Modifier.align(UiAlign.TopEnd).padding(12.dp), horizontalAlignment = UiAlign.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(if (st.tracking) "Tracking ✓" else "Tracking ✗", if (st.tracking) WorldColors.green else WorldColors.red)
                val g = st.gnss
                Chip(when { !location -> "GPS off"; st.phase == WalkPhase.READY && !(has(Manifest.permission.ACCESS_FINE_LOCATION)) -> "GPS off"; g.accuracyM == null -> "GPS searching…"; else -> "GPS ±${g.accuracyM.roundToInt()} m" }, when { !location -> WorldColors.dim; g.accuracyM == null -> WorldColors.amber; g.accuracyM <= 8f -> WorldColors.green; g.accuracyM <= 20f -> WorldColors.amber; else -> WorldColors.red })
                if (st.phase == WalkPhase.RECORDING) Chip("${"%,d".format(st.cubes)} cubes · ${st.pieces} piece${if (st.pieces == 1) "" else "s"}", WorldColors.teal)
            }
            // --- minimap
            if (st.phase != WalkPhase.READY) MiniMap(st, Modifier.align(UiAlign.BottomStart).padding(start = 12.dp, bottom = 96.dp).size(150.dp))
            // --- coach / message
            (st.why ?: st.endedBecause ?: startError ?: st.coach)?.let { Text(it, Modifier.align(UiAlign.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 168.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 14.dp, vertical = 9.dp), color = Color.White, fontSize = 13.sp, lineHeight = 18.sp) }
            // --- bottom controls
            when (st.phase) {
                WalkPhase.READY -> Column(Modifier.align(UiAlign.BottomCenter).fillMaxWidth().padding(14.dp).clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.72f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (target != null) "Add a walk to ${target.name}" else "New walk", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(if (target != null) "Start near where the first walk started, facing the same way, and walk the same route. The new walk is only added if it fits." else "Hold the phone upright at chest height, look ahead at buildings and walls, and walk at a normal pace.", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 17.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Chip(if (st.depth) "Depth ✓" else "No depth", if (st.depth) WorldColors.green else WorldColors.red); Chip(if (location) "Location ✓" else "Location: not allowed (optional)", if (location) WorldColors.green else WorldColors.amber) }
                    Button({ startError = scan.start(location) }, Modifier.fillMaxWidth().height(52.dp), enabled = st.tracking && st.depth, shape = RoundedCornerShape(26.dp)) { Text(if (st.tracking) "Start walking" else "Waiting for tracking…", fontWeight = FontWeight.Bold) }
                }
                WalkPhase.RECORDING -> Row(Modifier.align(UiAlign.BottomCenter).fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = UiAlign.CenterVertically) {
                    Spacer(Modifier.width(162.dp))
                    Glass(Modifier.weight(1f)) { Column { Text(if (st.distanceM < 1000) "${st.distanceM.roundToInt()} m" else "%.2f km".format(st.distanceM / 1000), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp); Text("%.1f m/s".format(st.speed), color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp) } }
                    Button({ finish() }, Modifier.height(56.dp), enabled = !saving, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = WorldColors.red), shape = RoundedCornerShape(28.dp)) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(6.dp)); Text(if (saving) "Saving…" else "Stop", fontWeight = FontWeight.Bold) }
                }
                WalkPhase.ENDED -> if (saving) Row(Modifier.align(UiAlign.BottomCenter).padding(24.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = UiAlign.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White); Text(if (placeId != null) "Saving and lining it up…" else "Saving…", color = Color.White) }
            }
        }
        if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text("Leave this walk?") }, text = { Text("You can stop and keep what you have, or throw it away.") },
            confirmButton = { TextButton({ leave = false; finish() }) { Text("Stop and keep") } }, dismissButton = { Row { TextButton({ leave = false }) { Text("Keep walking") }; TextButton({ leave = false; scan.discard(); onCancel() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } } })
        if (tooShort) AlertDialog(onDismissRequest = {}, title = { Text("Too short to keep") }, text = { Text("A walk needs at least 5 metres of tracked path. Nothing was saved.") }, confirmButton = { TextButton({ tooShort = false; scan.discard(); onCancel() }) { Text("OK") } })
        result?.let { r -> SavedDialog(r, placeId != null) { result = null; onSaved(r.place.id) } }
        failure?.let { f -> AlertDialog(onDismissRequest = {}, title = { Text("Not saved") }, text = { Text(f) }, confirmButton = { TextButton({ failure = null; scan.discard(); onCancel() }) { Text("OK") } }) }
    }

    @Composable
    private fun SavedDialog(r: SaveResult, adding: Boolean, onOk: () -> Unit) {
        val w = r.walk; val g = w.geo
        AlertDialog(onDismissRequest = onOk, confirmButton = { TextButton(onOk) { Text("Open the map") } }, title = { Text(if (adding) (if (r.merge?.accepted == true) "Added to ${r.place.name}" else "Saved, but not added") else "Walk saved") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${"%,.0f".format(w.lengthM)} m in ${clockText(w.durationS)} · ${"%,d".format(w.cubes)} cubes in ${w.pieces.size} pieces")
                Text(when { g == null -> "No GPS: mapped, but not placed on Earth."; g.usable -> g.summary(); else -> g.summary() }, color = if (g?.usable == true) WorldColors.green else WorldColors.amber)
                if (w.longestLossS > 0.5) Text("Tracking was lost for up to ${"%.1f".format(w.longestLossS)} s.")
                r.merge?.let { m -> Text(m.summary(), color = if (m.accepted) WorldColors.green else WorldColors.amber); if (!m.accepted) Text("The walk is kept, with everything it recorded. Nothing in the saved map changed. You can try adding it again from its place later.", fontSize = 12.sp) }
            }
        })
    }

    // ------------------------------------------------------------------ small parts
    @Composable private fun Glass(modifier: Modifier = Modifier, content: @Composable () -> Unit) { Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 8.dp)) { content() } }
    @Composable private fun Chip(text: String, colour: Color) { Text(text, Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 11.dp, vertical = 6.dp), color = colour, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }

    /** The live map: the surfaces mapped so far seen from above, turned so that straight ahead is up, with the path behind you. */
    @Composable
    private fun MiniMap(st: WalkState, modifier: Modifier) {
        val img = remember(st.mini) { st.mini?.let { bitmapOf(it) } }
        Canvas(modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.6f))) {
            val zoom = size.width / 44f          // 44 m across
            val h = st.headingDeg; val phi = Math.toRadians(-h.toDouble()); val cs = cos(phi).toFloat(); val sn = sin(phi).toFloat()
            fun at(x: Float, z: Float): Offset { val dx = x - st.camX; val dz = z - st.camZ; return Offset(size.width / 2 + zoom * (dx * cs - dz * sn), size.height * 0.62f + zoom * (dx * sn + dz * cs)) }
            st.mini?.let { r -> img?.let { b ->
                withTransform({ translate(size.width / 2, size.height * 0.62f); rotate(-h, Offset.Zero); scale(zoom, zoom, Offset.Zero); translate(-st.camX, -st.camZ); translate(r.x0, r.z0); scale(r.cell, r.cell, Offset.Zero) }) { drawImage(b, filterQuality = androidx.compose.ui.graphics.FilterQuality.None) }
            } }
            val t = st.trail
            if (t.size >= 4) { val p = Path(); p.moveTo(at(t[0], t[1]).x, at(t[0], t[1]).y); var i = 2; while (i + 1 < t.size) { val o = at(t[i], t[i + 1]); p.lineTo(o.x, o.y); i += 2 }; drawPath(p, WorldColors.amber, style = Stroke(3f, cap = StrokeCap.Round)) }
            val c = Offset(size.width / 2, size.height * 0.62f)
            val tri = Path().apply { moveTo(c.x, c.y - 12f); lineTo(c.x - 8f, c.y + 8f); lineTo(c.x + 8f, c.y + 8f); close() }
            drawPath(tri, Color.White); drawPath(tri, Color.Black, style = Stroke(2f))
        }
    }

    companion object {
        internal fun clockText(seconds: Double): String { val s = seconds.toInt(); return "%d:%02d".format(s / 60, s % 60) }
        /** A raster as a picture (transparent where nothing was seen). */
        internal fun bitmapOf(r: Raster): ImageBitmap = Bitmap.createBitmap(r.argb, r.w, r.h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
