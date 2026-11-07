package app.ultimate.spatial

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Spatial: what's around you, measured. Your watch's distance from the phone (by Bluetooth, in calibrated zones,
 * never fake metres), the Wi-Fi you're on and every network you can hear, a signal map you build by walking, places
 * recognised by their Wi-Fi fingerprint, and how sure each sense is right now. Opt-in, on the phone, easy to stop.
 */
class SpatialTool(private val app: UltimateApp) : Tool {
    override val id = "spatial"
    override val title = "Spatial"
    override val tagline = "Your watch's distance, Wi-Fi map, and the places you're in"
    override val icon = Icons.Rounded.Radar
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Watch distance · Wi-Fi map · places")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = false
    override suspend fun refresh() = Unit

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        BackHandler(onBack = onExit)
        val c = LocalContext.current
        val eng = app.spatial
        val s by eng.state.collectAsStateWithLifecycle()
        val needed = remember { listOfNotNull(Manifest.permission.ACCESS_FINE_LOCATION, if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else null, Manifest.permission.ACTIVITY_RECOGNITION) }
        var granted by remember { mutableStateOf(needed.all { eng.ok(it) }) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = needed.all { eng.ok(it) }; eng.stop(); eng.start() }
        var keep by remember { mutableStateOf(SpatialService.on(c)) }
        // Sensing runs while this screen is open, unless "Keep sensing" keeps it going.
        DisposableEffect(Unit) { eng.start(); onDispose { if (!SpatialService.on(c)) eng.stop() } }
        Scaffold(topBar = { TopAppBar(title = { Text("Spatial") }, navigationIcon = { ToolBackButton(onExit) }) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!granted) Card("Let Spatial sense") {
                    Text("Spatial needs Location (Android requires it to list nearby Wi-Fi networks), Nearby devices (to hear your watch's Bluetooth beacon) and Physical activity (to count your steps for the map). Nothing leaves the phone.", style = MaterialTheme.typography.bodySmall)
                    Button({ ask.launch(needed.toTypedArray()) }) { Text("Allow") }
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (s.running) "Sensing" else "Not sensing", fontWeight = FontWeight.SemiBold)
                        Text(if (keep) "Keeps going with the screen off, for your Rules (Stop in the notification)" else "Only while this screen is open", style = MaterialTheme.typography.labelSmall)
                    }
                    Text("Keep sensing", style = MaterialTheme.typography.labelMedium)
                    Switch(keep, { keep = it; SpatialService.set(c, it) }, Modifier.padding(start = 6.dp))
                }
                Overall(s)
                WatchCard(app, s)
                WifiCard(s)
                MapCard(eng, s)
                PlacesCard(eng, s)
                Card("What this phone can sense") {
                    val k = eng.caps
                    Cap("Bluetooth Low Energy", k.ble, "your watch's beacon: distance in zones")
                    Cap("Bluetooth channel sounding", k.channelSounding, if (k.channelSounding) "true distance over Bluetooth" else "needs Android 16 on both phone and watch, and supported chips")
                    Cap("Ultra-wideband (UWB)", k.uwb, if (k.uwb) "the phone has it; the Galaxy Watch Ultra doesn't, so it can't range to the watch" else "not on this phone")
                    Cap("Wi-Fi round-trip time", k.wifiRtt, if (k.wifiRtt) "works only with routers that support it" else "not on this phone")
                    Cap("Step counter", k.stepDetector, "walks the map")
                    Cap("Compass (rotation)", k.rotation, "which way you're walking")
                }
            }
        }
    }
}

@Composable
private fun Card(title: String, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        body()
    }
}

@Composable
private fun Cap(name: String, ok: Boolean, note: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "–", Modifier.width(22.dp), color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Bold)
        Column { Text(name, style = MaterialTheme.typography.bodyMedium); Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The fused picture: every sense with how sure it is right now. */
@Composable
private fun Overall(s: SpatialEngine.State) = Card("Around you now") {
    if (s.senses.isEmpty()) Text("Starting up…", style = MaterialTheme.typography.bodySmall)
    s.senses.entries.sortedByDescending { it.value.confidence }.forEach { (name, r) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.width(120.dp), style = MaterialTheme.typography.labelLarge)
            Text(r.value, Modifier.width(90.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator({ r.confidence.toFloat() }, Modifier.weight(1f))
        }
    }
    Text("Bars show how sure each sense is; a sense that goes quiet fades out instead of keeping an old value.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WatchCard(app: UltimateApp, s: SpatialEngine.State) = Card("Your watch") {
    val c = LocalContext.current
    val prefs = remember { c.getSharedPreferences("spatial", android.content.Context.MODE_PRIVATE) }
    var orb by remember { mutableStateOf(prefs.getBoolean("watch_orb", false)) }
    var step by remember { mutableIntStateOf(0) }
    val atPhone = remember { ArrayList<Double>() }; val atArm = remember { ArrayList<Double>() }
    var result by remember { mutableStateOf<String?>(null) }
    // Calibration: 5 seconds with the watch at the phone, then 5 at arm's length.
    LaunchedEffect(step) {
        if (step == 1 || step == 2) {
            val into = if (step == 1) atPhone else atArm; into.clear()
            repeat(10) { kotlinx.coroutines.delay(500); app.spatial.state.value.watchDbm?.let(into::add) }
            if (step == 1) step = 2 else { result = if (app.spatial.calibrate(atPhone, atArm)) "Calibrated: at the phone %.0f dBm, at arm's length %.0f dBm".format(atPhone.sorted()[atPhone.size / 2], atArm.sorted()[atArm.size / 2]) else "Couldn't tell the two apart. Try again, with the watch right on the phone, then your arm straight out."; step = 0 }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Rings: the phone in the middle, the watch's dot in its zone.
        val zoneR = when (s.watchZone) { ProximityZones.Zone.Near -> 0.22f; ProximityZones.Zone.Mid -> 0.55f; ProximityZones.Zone.Far -> 0.88f; ProximityZones.Zone.Gone -> -1f }
        val accent = MaterialTheme.colorScheme.primary; val ring = MaterialTheme.colorScheme.outline
        Canvas(Modifier.size(120.dp)) {
            val m = size.minDimension / 2; val o = center
            listOf(0.38f, 0.72f, 1f).forEach { drawCircle(ring.copy(alpha = 0.5f), it * m, o, style = Stroke(2f)) }
            drawRoundRect(accent, Offset(o.x - 7, o.y - 12), Size(14f, 24f), androidx.compose.ui.geometry.CornerRadius(4f))
            if (zoneR > 0) drawCircle(accent.copy(alpha = 0.25f + 0.75f * s.watchConfidence.toFloat()), 10f, Offset(o.x + zoneR * m * 0.7f, o.y - zoneR * m * 0.7f))
        }
        Column {
            Text(when (s.watchZone) { ProximityZones.Zone.Near -> "Right by the phone"; ProximityZones.Zone.Mid -> "Within reach"; ProximityZones.Zone.Far -> "Away from the phone"; ProximityZones.Zone.Gone -> "Not heard" },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            s.watchDbm?.let { Text("%.0f dBm · %.0f%% sure".format(it, s.watchConfidence * 100), style = MaterialTheme.typography.bodySmall) }
            Text("Zones, not metres: Bluetooth strength changes with your body, pockets and walls.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (s.watchZone == ProximityZones.Zone.Gone) Text("On the watch, open Ultimate and turn on \"Proximity beacon\". Update the watch from Watch in Ultimate+ first if you don't see it.", style = MaterialTheme.typography.bodySmall)
    when (step) {
        1 -> Text("Hold the watch right against the phone… (5 s)", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        2 -> Text("Now hold your arm straight out, away from the phone… (5 s)", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        else -> OutlinedButton({ result = null; step = 1 }, enabled = s.watchDbm != null) { Text("Calibrate for my watch") }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Bring the watch close to open the Orb", style = MaterialTheme.typography.bodyMedium)
            Text("Hold it by the phone for a moment; move it away to close. Needs the Orb on.", style = MaterialTheme.typography.labelSmall)
        }
        Switch(orb, { orb = it; prefs.edit().putBoolean("watch_orb", it).apply() })
    }
}

@Composable
private fun WifiCard(s: SpatialEngine.State) = Card("Wi-Fi") {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(s.wifiDbm?.let { "$it dBm" } ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("  " + (s.wifiName ?: if (s.wifiDbm != null) "connected" else "not connected"), style = MaterialTheme.typography.bodyMedium)
    }
    s.wifiDbm?.let { LinearProgressIndicator({ ((it + 95) / 60f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = quality(it.toDouble())) }
    Text(s.wifiDbm?.let { when { it >= -55 -> "Excellent"; it >= -67 -> "Good"; it >= -75 -> "Weak: video may stutter"; else -> "Very weak: a dead zone" } } ?: "", style = MaterialTheme.typography.labelLarge)
    if (s.networks.isNotEmpty()) {
        Text("${s.networks.size} networks heard", style = MaterialTheme.typography.labelLarge)
        s.networks.take(6).forEach { n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.ssid.ifBlank { "(hidden)" }, Modifier.width(140.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Text(if (n.mhz > 4900) "5 GHz" else "2.4", Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator({ ((n.dbm + 95) / 60f).coerceIn(0f, 1f) }, Modifier.weight(1f), color = quality(n.dbm.toDouble()))
            }
        }
    } else if (!s.scansAllowed) Text("Allow Location to see the other networks around you (and recognise places).", style = MaterialTheme.typography.bodySmall)
}

private fun quality(dbm: Double) = when { dbm >= -60 -> Color(0xFF3DDC97); dbm >= -70 -> Color(0xFFFFC857); else -> Color(0xFFFF6B6B) }

@Composable
private fun MapCard(eng: SpatialEngine, s: SpatialEngine.State) = Card("Signal map") {
    Text("Walk around with the phone in front of you. Each step is placed by your steps and the compass, coloured by Wi-Fi strength there. Positions drift over long walks; it shows where signal is weak, not your rooms' real shape.",
        style = MaterialTheme.typography.bodySmall)
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh; val ink = MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(18.dp)).background(bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val pts = s.path
            if (pts.isEmpty()) return@Canvas
            val xs = pts.map { it.first } + s.cells.flatMap { listOf(it.cx * 0.75, (it.cx + 1) * 0.75) }
            val ys = pts.map { it.second } + s.cells.flatMap { listOf(it.cy * 0.75, (it.cy + 1) * 0.75) }
            val minX = xs.min() - 1; val maxX = xs.max() + 1; val minY = ys.min() - 1; val maxY = ys.max() + 1
            val k = minOf(size.width / (maxX - minX), size.height / (maxY - minY)).toFloat()
            fun o(x: Double, y: Double) = Offset(((x - minX) * k).toFloat(), ((y - minY) * k).toFloat())
            s.cells.forEach { cell -> drawRect(quality(cell.meanDbm).copy(alpha = 0.55f), o(cell.cx * 0.75, cell.cy * 0.75), Size(0.75f * k, 0.75f * k)) }
            val path = Path().apply { pts.forEachIndexed { i, (x, y) -> val p = o(x, y); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
            drawPath(path, ink.copy(alpha = 0.7f), style = Stroke(3f))
            pts.lastOrNull()?.let { (x, y) -> drawCircle(Color(0xFF7FD3FF), 9f, o(x, y)) }
        }
        if (s.path.size <= 1) Text(if (s.mapping) "Start walking…" else "Tap Start a map, then walk", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!s.mapping) Button({ eng.startMap() }) { Text("Start a map") } else FilledTonalButton({ eng.stopMap() }) { Text("Stop") }
        val dead = s.cells.count { it.dead }
        Text("${s.path.size - 1} steps · ${s.cells.size} spots" + if (dead > 0) " · $dead dead zone${if (dead == 1) "" else "s"}" else "", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PlacesCard(eng: SpatialEngine, s: SpatialEngine.State) = Card("Places") {
    var name by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(eng.places().keys.toList()) }
    Text(s.place?.let { "You're probably at ${it.place} (%.0f%% sure)".format(it.confidence * 100) } ?: if (saved.isEmpty()) "Save the places you spend time in, like your desk, and Ultimate recognises them by their Wi-Fi." else "Not at a saved place right now",
        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, Modifier.weight(1f), placeholder = { Text("Desk, Kitchen, Bed…") }, singleLine = true)
        Button({ if (name.isNotBlank() && eng.savePlace(name.trim())) { saved = eng.places().keys.toList(); name = "" } }, enabled = name.isNotBlank() && s.networks.isNotEmpty()) { Text("Save here") }
    }
    Text("Save the same place a couple of times from different spots in it to make it surer. Then Rules can use it: \"When I'm at my desk, …\".", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    saved.forEach { p -> Row(verticalAlignment = Alignment.CenterVertically) { Text(p, Modifier.weight(1f)); TextButton({ eng.forgetPlace(p); saved = eng.places().keys.toList() }) { Text("Forget") } } }
}
