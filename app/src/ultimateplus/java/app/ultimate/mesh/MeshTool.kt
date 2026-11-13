package app.ultimate.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Registered reflectively from main so offline editions, which have no INTERNET permission, never include it. */
class MeshTool(context: Context) : Tool {
    private val manager = MeshManager.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val id = "mesh"
    override val title = "Octopus Mesh"
    override val tagline = "Your devices find each other, share sensors and work"
    override val icon = Icons.Rounded.Hub
    override val headline = manager.state.map { s -> if (s.running) "LIVE · ${s.alive.size} arm(s) connected" else "Off · pair devices with a code" }
        .stateIn(scope, SharingStarted.Eagerly, "Off · pair devices with a code")
    override val library = MutableStateFlow<List<LibraryItem>>(emptyList())
    override val acceptedMimePrefixes = emptyList<String>()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = false
    override suspend fun refresh() = Unit

    init { scope.launch { manager.resumeIfWasOn() } }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        val s by manager.state.collectAsState()
        val context = LocalContext.current
        var code by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var showCode by remember { mutableStateOf(false) }
        var pendingRange by remember { mutableStateOf<String?>(null) }
        val askAudioForRange = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            pendingRange?.let { id -> if (ok) manager.measureDistance(id) }; pendingRange = null
        }
        val askAudioForLend = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { manager.setLends(true) }
        val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { manager.startBluetooth() }
        val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { manager.start(code) }
        fun hasAudio() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Scaffold(topBar = {
            TopAppBar(title = { Text("Octopus Mesh") }, navigationIcon = {
                IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            })
        }) { pad ->
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                if (s.running) item { LiveBar(s) { manager.stop() } }
                item { OctopusCanvas(s, Modifier.fillMaxWidth().height(260.dp)) }
                items(s.notices) { n -> NoticeCard(n) { manager.dismissNotice(n) } }
                if (!s.running) item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Type the same pairing code on each of your devices. Only devices with the code can see this phone or send it anything, and everything they exchange is encrypted with it. Nothing listens until you press Start.",
                            style = MaterialTheme.typography.bodySmall)
                        if (code.trim().length >= 6) { val bits = MeshWire.codeBits(code.trim()); Text(if (bits < 40) "This code is short enough to be guessed by someone who records your Wi-Fi. A longer one (a few words) is much safer." else "Code strength: good.", style = MaterialTheme.typography.labelSmall, color = if (bits < 40) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(code, { code = it }, label = { Text("Pairing code (6+)") }, singleLine = true, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else manager.start(code)
                            }, enabled = code.trim().length >= 6) { Text("Start") }
                        }
                        s.code?.let { Text("This phone remembers a code. Start with it by typing it again, or forget it.", style = MaterialTheme.typography.labelSmall) }
                        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (s.known.isNotEmpty()) TextButton({ manager.forgetEverything() }) { Text("Forget the code and ${s.known.size} known device(s)") }
                        Text("Works best when your phones are on the same Wi-Fi. No Wi-Fi? Turn on a hotspot on one phone and join it from the other.", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    item { ThisPhone(s, showCode, { showCode = !showCode },
                        onLend = { on -> if (on && !hasAudio()) askAudioForLend.launch(Manifest.permission.RECORD_AUDIO) else manager.setLends(on) },
                        onStopRecording = { manager.stopLocalRecording() },
                        onBluetooth = { if (Build.VERSION.SDK_INT >= 31) askBluetooth.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)) else askBluetooth.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) }) }
                    item { MeshSummary(s) }
                    if (s.peers.isEmpty()) item { Text("No other devices yet. Start the mesh with the same code on your other phone.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
                    items(s.peers) { p ->
                        PeerCard(p, p.id in s.alive, s.bleBands[p.id],
                            onRecord = { manager.recordOn(p.id) },
                            onRange = { if (hasAudio()) manager.measureDistance(p.id) else { pendingRange = p.id; askAudioForRange.launch(Manifest.permission.RECORD_AUDIO) } },
                            onPing = { manager.ping(p.id) })
                    }
                    if (s.strangersNearby.isNotEmpty()) item {
                        Text("${s.strangersNearby.size} device(s) with your code are nearby over Bluetooth (${s.strangersNearby.first().label}) but not reachable over Wi-Fi. Put both phones on the same Wi-Fi, or turn on a hotspot on one and join it from the other.",
                            Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    s.remote?.let { r -> item { RemotePanel(r, onStop = { manager.stopRemoteRecording() }, onSave = {
                        val name = manager.saveRecordingCsv()
                        android.widget.Toast.makeText(context, if (name != null) "Saved $name to Downloads/Ultimate" else "Couldn't save the recording", android.widget.Toast.LENGTH_SHORT).show()
                    }, onDismiss = { manager.dismissRemote() }) } }
                    if (s.range !is MeshManager.RangeState.Idle) item { RangePanel(s.range) { manager.clearRange() } }
                    item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Share work", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { manager.run(20_000) }) { Text("Reflex task") }
                                FilledTonalButton(onClick = { manager.run(3_000_000) }) { Text("Heavy task") }
                                FilledTonalButton(onClick = { manager.run(1_500_000, 12) }) { Text("Burst ×12") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(address, { address = it }, label = { Text("Add by address (ip:port)") }, singleLine = true, modifier = Modifier.weight(1f))
                                TextButton(onClick = { manager.addAddress(address); address = "" }, enabled = address.isNotBlank()) { Text("Add") }
                            }
                            Text("Key ${s.fingerprint} · this phone ${"%.1f".format(s.capacity)} units/ms · port ${s.port}" +
                                (s.stats?.let { " · sent ${it.sent} recv ${it.received} refused ${it.rejected}" } ?: ""),
                                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                items(s.log) { Text(it, Modifier.padding(horizontal = 16.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
            }
        }
    }

    @Composable private fun NoticeCard(text: String, onDismiss: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            TextButton(onDismiss) { Text("OK") }
        }
    }

    @Composable private fun ThisPhone(s: MeshManager.State, showCode: Boolean, onToggleCode: () -> Unit, onLend: (Boolean) -> Unit, onStopRecording: () -> Unit, onBluetooth: () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("This phone: ${s.selfName}", style = MaterialTheme.typography.titleSmall)
            Text(s.profile.describe(), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lend this phone to my other devices", style = MaterialTheme.typography.bodyMedium)
                    Text("Lets your paired devices record this phone's motion sensors and measure distance to it with sound. Off, it is never asked. Every use shows here, in the notification and in Live now.",
                        style = MaterialTheme.typography.labelSmall)
                }
                Switch(s.lends, onLend)
            }
            s.recordingFor?.let { who ->
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recording this phone's motion for $who", Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onStopRecording) { Text("Stop") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.bleNote ?: if (s.bleBands.isNotEmpty() || s.strangersNearby.isNotEmpty()) "Bluetooth: listening and advertising" else "Bluetooth: on", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                if (s.bleNote != null) TextButton(onBluetooth) { Text("Allow Bluetooth") }
            }
            s.code?.let { c -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pairing code: " + if (showCode) c else "••••••", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                TextButton(onToggleCode) { Text(if (showCode) "Hide" else "Show") }
            } }
        }
    }

    @Composable private fun MeshSummary(s: MeshManager.State) {
        val live = s.peers.filter { it.id in s.alive }
        val text = when {
            !s.running -> ""
            live.isEmpty() -> "Only this phone so far. Each phone you add brings its own sensors, microphone, speaker and processor."
            else -> "${live.size + 1} devices working together · about ${"%.1f".format(s.meshPower)}× this phone's speed on shared tasks · " +
                "${live.count { it.profile.canRecordMotion } + (if (s.lends && s.profile.canRecordMotion) 1 else 0)} can record motion, ${live.count { it.profile.canRange }} can be measured by sound"
        }
        if (text.isNotEmpty()) Text(text, Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall)
    }

    @Composable private fun PeerCard(p: MeshNode.Peer, alive: Boolean, band: MeshBleCodec.Band?, onRecord: () -> Unit, onRange: () -> Unit, onPing: () -> Unit) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(if (alive) Color(0xFF4CAF50) else Color(0xFF9E9E9E), RoundedCornerShape(5.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(if (alive) "connected · ${p.rttMs.toInt()} ms" else "out of reach", style = MaterialTheme.typography.labelSmall)
                }
                Text(p.profile.describe(), style = MaterialTheme.typography.bodySmall)
                band?.let { Text("Bluetooth: ${it.label} (estimate from signal strength)", style = MaterialTheme.typography.labelSmall) }
                if (alive && !p.profile.lends) Text("Not lending its sensors. On that phone, turn on \"Lend this phone to my other devices\".", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (alive && p.profile.canRecordMotion) FilledTonalButton(onRecord) { Text("Record motion") }
                    if (alive && p.profile.canRange) FilledTonalButton(onRange) { Text("Distance") }
                    if (alive) TextButton(onPing) { Text("Find") }
                }
            }
        }
    }

    private fun clock(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

    @Composable private fun RemotePanel(r: MeshManager.RemoteRecording, onStop: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Motion recording on ${r.peerName}", style = MaterialTheme.typography.titleSmall)
                Text(when (r.phase) {
                    MeshManager.RemoteRecording.Phase.Starting -> "Starting…"
                    MeshManager.RemoteRecording.Phase.Recording -> "Recording · ${clock(r.ms)} · ${r.rows} readings"
                    MeshManager.RemoteRecording.Phase.Stopping -> "Stopping…"
                    MeshManager.RemoteRecording.Phase.Fetching -> "Receiving the recording · ${r.fetched} of ${r.total}"
                    MeshManager.RemoteRecording.Phase.Done -> "Done · ${clock(r.ms)} · ${r.rows} readings"
                    MeshManager.RemoteRecording.Phase.Failed -> "Failed"
                }, style = MaterialTheme.typography.bodyMedium)
                if (r.phase == MeshManager.RemoteRecording.Phase.Fetching && r.total > 0) LinearProgressIndicator(progress = { r.fetched / r.total.toFloat() }, Modifier.fillMaxWidth())
                if (r.phase == MeshManager.RemoteRecording.Phase.Recording || r.phase == MeshManager.RemoteRecording.Phase.Done) {
                    Text("ESTIMATE · ${r.reps} repetitions in ${r.sets.size} set(s) · active ${clock((r.activeSeconds * 1000).toLong())} · intensity ${"%.1f".format(r.rms)} m/s²", style = MaterialTheme.typography.bodySmall)
                    r.sets.forEachIndexed { i, st ->
                        Text("Set ${i + 1}: ${st.reps} reps" + (if (st.perMinute > 0) " at about ${st.perMinute.toInt()} a minute" else "") + " · from ${clock((st.startS * 1000).toLong())} for ${st.endS.toInt() - st.startS.toInt()} s", style = MaterialTheme.typography.labelSmall)
                    }
                    if (r.keptInMovement) Text("Kept in Movement, where it can be analysed further and asked about.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("Counted from how the phone moved, not which exercise it was; it depends on where the phone sat and has not been checked against a human count.", style = MaterialTheme.typography.labelSmall)
                }
                r.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.phase == MeshManager.RemoteRecording.Phase.Recording) Button(onStop) { Text("Stop and save") }
                    if (r.phase == MeshManager.RemoteRecording.Phase.Done) Button(onSave) { Text("Save as CSV") }
                    if (r.phase != MeshManager.RemoteRecording.Phase.Fetching && r.phase != MeshManager.RemoteRecording.Phase.Stopping) TextButton(onDismiss) { Text(if (r.phase == MeshManager.RemoteRecording.Phase.Recording) "Hide" else "Close") }
                }
            }
        }
    }

    @Composable private fun RangePanel(state: MeshManager.RangeState, onClose: () -> Unit) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (state) {
                    is MeshManager.RangeState.Measuring -> { Text("Measuring distance to ${state.peerName}…", style = MaterialTheme.typography.titleSmall); Text("Run ${state.run} of ${state.of}. Both phones play a high chirp you should barely hear. Keep them still and the media volume up.", style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    is MeshManager.RangeState.Result -> {
                        Text("${"%.2f".format(state.metres)} m to ${state.peerName}", style = MaterialTheme.typography.headlineSmall)
                        Text("MEASURED with sound · ${state.runs} run(s)" + (if (state.runs > 1) " · spread ${(state.spreadMetres * 100).toInt()} cm" else "") + " · ${if (state.confidence == PeerRange.Confidence.Good) "clear" else "rough"}", style = MaterialTheme.typography.labelMedium)
                        Text(state.note + ". Timing resolves a few millimetres; real accuracy depends on the room and on how far each phone's speaker and microphone reach, and has not been verified on these phones.", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClose) { Text("Close") }
                    }
                    is MeshManager.RangeState.Failed -> {
                        Text("Couldn't measure the distance to ${state.peerName}", style = MaterialTheme.typography.titleSmall)
                        Text(state.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClose) { Text("Close") }
                    }
                    MeshManager.RangeState.Idle -> Unit
                }
            }
        }
    }

    @Composable private fun LiveBar(s: MeshManager.State, onStop: () -> Unit) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF3A0D2E)).padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { contentDescription = "Mesh is live and listening on the local network" },
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Color(0xFFFF4F9A), RoundedCornerShape(5.dp)))
            Spacer(Modifier.width(8.dp))
            Text("MESH LIVE · listening on Wi-Fi · ${s.alive.size} connected" + if (s.busy > 0) " · ${s.busy} running" else "",
                color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onStop) { Text("Disconnect", color = Color(0xFFFFB3D1)) }
        }
    }

    /** The phone is the mantle; each trusted device is an arm. Live arms sway, cut arms droop and fade, work travels as light. */
    @Composable private fun OctopusCanvas(s: MeshManager.State, modifier: Modifier) {
        val t by rememberInfiniteTransition(label = "sway").animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(6_000, easing = LinearEasing)), label = "t")
        val skin = if (s.running) Color(0xFFFF6FA8) else Color(0xFF6B6B7A)
        Canvas(modifier.background(Color(0xFF0D0B14))) {
            val c = Offset(size.width / 2, size.height * 0.42f)
            val arms = s.peers.ifEmpty { null }
            val count = maxOf(arms?.size ?: 0, 8)
            val now = System.currentTimeMillis()
            for (i in 0 until count) {
                val peer = arms?.getOrNull(i)
                val alive = peer != null && peer.id in s.alive
                val angle = (PI * (0.15 + 0.7 * (i + 0.5) / count)).toFloat()
                val len = if (peer != null) size.height * 0.5f else size.height * 0.22f
                val sway = sin(t + i * 0.9f) * (if (alive) 18f else 6f)
                val droop = if (peer != null && !alive) size.height * 0.15f else 0f
                val end = Offset(c.x + cos(angle) * len * 1.4f + sway, c.y + sin(angle) * len + droop)
                val ctrl = Offset(c.x + cos(angle) * len * 0.5f - sway, c.y + sin(angle) * len * 0.2f + 30f)
                val color = when { peer == null -> skin.copy(alpha = 0.18f); alive -> skin; else -> Color(0xFF55505E) }
                tentacle(c, ctrl, end, color, base = 16f + (peer?.capacity?.toFloat()?.coerceIn(0f, 30f) ?: 0f) * 0.4f)
                if (peer != null) {
                    drawCircle(color, 10f, end)
                    s.flows.filter { it.to == peer.id }.forEach { f ->
                        val k = ((now - f.at) / 900f).coerceIn(0f, 1f)
                        drawCircle(Color(0xFFFFF2A8).copy(alpha = 1 - k), 9f, bez(c, ctrl, end, k))
                    }
                }
            }
            val pulse = 1f + 0.04f * sin(t * 2)
            drawCircle(skin.copy(alpha = 0.25f), 70f * pulse, c)
            drawCircle(skin, 52f * pulse, c)
            if (s.flows.any { it.to == s.selfId }) drawCircle(Color(0xFFFFF2A8), 18f, c)
            drawCircle(Color(0xFF0D0B14), 7f, Offset(c.x - 18f, c.y - 10f)); drawCircle(Color(0xFF0D0B14), 7f, Offset(c.x + 18f, c.y - 10f))
        }
    }

    private fun bez(a: Offset, c: Offset, b: Offset, k: Float): Offset {
        val u = 1 - k
        return Offset(u * u * a.x + 2 * u * k * c.x + k * k * b.x, u * u * a.y + 2 * u * k * c.y + k * k * b.y)
    }

    /** A tapering arm drawn as overlapping discs: cheap, and reads as flesh rather than a network edge. */
    private fun DrawScope.tentacle(a: Offset, c: Offset, b: Offset, color: Color, base: Float) {
        val steps = 28
        for (j in 0..steps) {
            val k = j / steps.toFloat()
            drawCircle(color, base * (1 - k * 0.8f), bez(a, c, b, k))
        }
    }
}
