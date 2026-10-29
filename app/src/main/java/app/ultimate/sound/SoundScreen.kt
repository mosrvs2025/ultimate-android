package app.ultimate.sound

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.ToolLaunch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundScreen(tool: SoundTool, onExit: () -> Unit, launch: ToolLaunch) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val state by tool.meter.state.collectAsStateWithLifecycle()
    val sessions by tool.sessions.collectAsStateWithLifecycle()
    val storeError by tool.error.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(launch.openItemId) }
    var compare by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val prefs = remember { context.getSharedPreferences("sound-calibration", 0) }
    var useOffset by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var offsetText by remember { mutableStateOf(prefs.getString("offset", "") ?: "") }
    val offset = offsetText.toDoubleOrNull()?.takeIf { it.isFinite() && it in -200.0..200.0 }
    var exportSession by remember { mutableStateOf<SoundSession?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val session = exportSession
        if (uri != null && session != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri) ?: error("Cannot open export destination")
                    stream.bufferedWriter().use { it.write(session.csv()) }
                }
                Toast.makeText(context, "Session exported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { notice = "Export failed: ${e.message}" }
        }
    }
    fun start() {
        notice = null
        if (useOffset && offset == null) { notice = "Enter a finite calibration offset between −200 and 200 dB."; return }
        prefs.edit().putBoolean("enabled", useOffset).putString("offset", offsetText).apply()
        tool.meter.start(if (useOffset) offset else null)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        else notice = "Microphone access is needed. You can allow it in Android Settings → Apps → Ultimate → Permissions."
    }
    DisposableEffect(lifecycle, tool) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) tool.meter.stop("App left the foreground")
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            tool.meter.stop("Left sound meter")
        }
    }
    BackHandler { if (selected != null) { selected = null; compare = null } else onExit() }
    val opened = sessions.firstOrNull { it.id == selected }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (selected == null) "Sound meter" else "Sound session") }, navigationIcon = {
            TextButton(onClick = { if (selected != null) { selected = null; compare = null } else onExit() }) { Text("Back") }
        })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (opened != null) {
                item {
                    var name by remember(opened.id) { mutableStateOf(opened.title) }
                    OutlinedTextField(name, { name = it }, label = { Text("Session name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = name.isNotBlank() && name != opened.title,
                        onClick = { scope.launch { tool.save(opened.copy(title = name.trim())) } }) { Text("Save name") }
                }
                item { SessionSummary(opened) }
                item { LevelGraph(opened.points.takeLast(120), opened.offset) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { exportSession = opened; export.launch("sound-${opened.createdAt}.csv") }) { Text("Export CSV") }
                        OutlinedButton(onClick = { scope.launch {
                            try { if (tool.remove(opened.id, true)) selected = null else notice = "Session could not be deleted" }
                            catch (e: Exception) { notice = "Delete failed: ${e.message}" }
                        } }) { Text("Delete") }
                    }
                }
                item { Text("Compare sessions", style = MaterialTheme.typography.titleMedium) }
                items(sessions.filter { it.id != opened.id }, key = { "compare-${it.id}" }) { other ->
                    TextButton(onClick = { compare = other.id }) { Text("${other.title} · ${DateFormat.getDateTimeInstance().format(Date(other.createdAt))}") }
                }
                sessions.firstOrNull { it.id == compare }?.let { other ->
                    item {
                        if (other.offset == opened.offset && other.route == opened.route) {
                            Text("Average difference: ${level(opened.averageDb - other.averageDb)} dB. Positive means this session was louder.")
                        } else Text("Different microphone routes or calibration. Values are shown separately and may not be directly comparable.")
                        SessionSummary(other)
                    }
                }
            } else if (selected != null) {
                item { Text("Session unavailable. Return to the meter or retry loading your library.") }
            } else {
                val shownOffset = if (state.elapsedMs > 0 || state.running) state.offset else if (useOffset) offset else null
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (state.running) "LISTENING" else "YOUR SOUND, IN VIEW", style = MaterialTheme.typography.labelLarge)
                            Text(if (state.elapsedMs == 0L) "—" else level(state.currentDb + (shownOffset ?: 0.0)), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                            Text(if (shownOffset == null) "dBFS · digital microphone level" else "dB SPL · calibrated estimate")
                            Text("${state.elapsedMs / 1000}s captured · ${state.clipped} clipped samples", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { LevelGraph(state.graph, shownOffset) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("Energy avg", if (state.elapsedMs > 0) level(state.averageDb + (shownOffset ?: 0.0)) else "—")
                        Stat("Minimum", if (state.elapsedMs > 0) level(state.minDb + (shownOffset ?: 0.0)) else "—")
                        Stat("Maximum", if (state.elapsedMs > 0) level(state.maxDb + (shownOffset ?: 0.0)) else "—")
                    }
                }
                item {
                    Button(modifier = Modifier.fillMaxWidth().height(56.dp), enabled = state.running || storeError == null,
                        onClick = {
                            if (state.running) tool.meter.stop()
                            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                            else permission.launch(Manifest.permission.RECORD_AUDIO)
                        }) { Text(if (state.running) "Stop & save session" else "Start measuring") }
                    Text("Captures only while this screen is open. Saves levels, never raw audio.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                if (state.clipped > 0) item { Text("Clipping detected. The microphone reached its digital limit; louder sound may not produce higher readings.", color = MaterialTheme.colorScheme.error) }
                if (state.route.isNotBlank()) item { Text(state.route, style = MaterialTheme.typography.bodySmall) }
                state.message?.let { item { Text(it) } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(useOffset, { useOffset = it }, enabled = !state.running)
                        Text("Use reference calibration", modifier = Modifier.padding(top = 12.dp))
                    }
                    if (useOffset) OutlinedTextField(offsetText, { offsetText = it }, enabled = !state.running,
                        label = { Text("Reference SPL minus measured dBFS") }, suffix = { Text("dB") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    Text("Without a reference, this shows dBFS, not room loudness in acoustic decibels. Calibration needs a reference meter on the same microphone route. Results are unweighted estimates, not dBA.", style = MaterialTheme.typography.bodySmall)
                }
                item { Text("Saved sessions", style = MaterialTheme.typography.titleLarge) }
                if (sessions.isEmpty()) item { Text("Your first session appears here when you stop.") }
                items(sessions, key = { it.id }) { session ->
                    OutlinedCard(onClick = { selected = session.id }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(DateFormat.getDateTimeInstance().format(Date(session.createdAt)), fontWeight = FontWeight.SemiBold)
                            Text("${session.durationMs / 1000}s · ${level(session.display(session.averageDb))} ${session.unit} average")
                        }
                    }
                }
            }
            notice?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            storeError?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { state.finished?.let { tool.save(it) } ?: tool.refresh() } }) { Text("Retry storage") }
            } }
        }
    }
}

private fun level(value: Double) = String.format(Locale.US, "%.1f", value)
@Composable private fun Stat(title: String, value: String) {
    Column { Text(title, style = MaterialTheme.typography.labelMedium); Text(value, style = MaterialTheme.typography.titleLarge) }
}
@Composable private fun SessionSummary(s: SoundSession) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${s.durationMs / 1000}s · ${s.unit}")
        Text("Energy average ${level(s.display(s.averageDb))} · min ${level(s.display(s.minDb))} · max ${level(s.display(s.maxDb))}")
        Text("Sample peak ${level(s.peakDb)} dBFS · ${s.clipped} clipped samples")
        Text(s.route, style = MaterialTheme.typography.bodySmall)
        Text("Ended: ${s.endReason}", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun LevelGraph(points: List<SoundPoint>, offset: Double?) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column {
        Text("Level timeline · ${if (offset == null) "dBFS" else "estimated SPL"} · last 120 intervals", style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.fillMaxWidth().height(150.dp).padding(vertical = 12.dp)) {
            repeat(5) { i -> drawLine(grid, androidx.compose.ui.geometry.Offset(0f, size.height * i / 4), androidx.compose.ui.geometry.Offset(size.width, size.height * i / 4)) }
            if (points.isNotEmpty()) {
                val path = Path()
                points.forEachIndexed { i, point ->
                    val x = if (points.size == 1) size.width / 2 else size.width * i / (points.size - 1)
                    val y = size.height * (1f - ((point.rmsDb + 120) / 120).toFloat().coerceIn(0f, 1f))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (points.size == 1) drawCircle(color, 4.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width / 2, size.height * (1f - ((points[0].rmsDb + 120) / 120).toFloat().coerceIn(0f, 1f))))
                else drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
            }
        }
        Text("${level(-120.0 + (offset ?: 0.0))} to ${level(offset ?: 0.0)} · numeric readings above update live", style = MaterialTheme.typography.labelSmall)
    }
}
