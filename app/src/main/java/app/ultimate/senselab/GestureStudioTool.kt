package app.ultimate.senselab

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.Signal
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.echo.EchoTool
import app.ultimate.echo.GestureModel
import app.ultimate.senselab.StudioMath.Lane
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Gesture Studio: every sense on one screen, taught together. Do a gesture a few times when asked, and the phone's
 * proximity, light and motion sensors, the microphone, your watch and Echo all learn it at once; each one's own score
 * shows which senses really notice it, and the combined model recognises it live. Nothing leaves the phone.
 */
class GestureStudioTool(private val app: UltimateApp) : Tool {
    override val id = "gesture-studio"
    override val title = "Gesture Studio"
    override val tagline = "Every sense on one screen: teach a gesture once and the phone, watch and Echo all learn it"
    override val icon = Icons.Rounded.Sensors
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Teach once, every sense learns")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = false
    override suspend fun refresh() = Unit

    private val file get() = File(app.filesDir, "gesture_studio.json")
    private fun load() = runCatching { GestureModel.fromJson(file.readText()) }.getOrNull()
    private fun save(m: GestureModel?) { if (m == null) file.delete() else file.writeText(m.toJson()) }

    private sealed interface Teach {
        data object Off : Teach
        data class Ready(val name: String) : Teach
        data class Doing(val name: String, val rep: Int, val still: Boolean) : Teach
        data class Done(val name: String, val reliability: Map<Lane, Double>) : Teach
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        BackHandler(onBack = onExit)
        val c = LocalContext.current
        val hub = remember { SenseHub(c) }
        val echo = app.tools.firstOrNull { it.id == "echo" } as? EchoTool
        var model by remember { mutableStateOf(load()) }
        var soundOn by remember { mutableStateOf(false) }
        var echoOn by remember { mutableStateOf(false) }
        var teach by remember { mutableStateOf<Teach>(Teach.Off) }
        var heard by remember { mutableStateOf<Pair<String, Long>?>(null) }
        val liveLanes by hub.live.collectAsStateWithLifecycle()
        val tick by hub.tick.collectAsStateWithLifecycle()
        val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) { soundOn = hub.sound(true); echoOn = false } }
        val view = LocalView.current

        DisposableEffect(Unit) { view.keepScreenOn = true; hub.start(); onDispose { view.keepScreenOn = false; hub.stop() } }
        // Echo shares the microphone with Sound, so only one listens at a time.
        DisposableEffect(echoOn) {
            var startedHere = false
            if (echoOn && echo != null) { echo.sonar.onFeatures = hub.echoListener(); if (!echo.sonar.running) { echo.sonar.start(); startedHere = true } }
            onDispose { if (echo != null && echoOn) { echo.sonar.onFeatures = null; if (startedHere) echo.sonar.stop() } }
        }
        // Live recognition from all senses together: the same answer twice in a row, then a pause.
        LaunchedEffect(model, teach) {
            val m = model?.takeIf { it.labels.any { l -> l != GestureModel.NONE } } ?: return@LaunchedEffect
            if (teach !is Teach.Off) return@LaunchedEffect
            var streak: String? = null; var last = 0L
            while (true) {
                delay(150)
                val now = System.currentTimeMillis()
                val g = m.predict(StudioMath.window(hub.points(), now))?.takeIf { it.votes >= 0.6f && it.label != GestureModel.NONE }?.label
                if (g != null && g == streak && now - last > 1_500) {
                    last = now; heard = g to now; streak = null
                    app.senses.publish(Signal("studio", g, "You did \"$g\" (Gesture Studio)"))
                } else streak = g
            }
        }
        // Teaching: five prompted repetitions and five still moments, every sense recorded at once.
        LaunchedEffect(teach is Teach.Ready) {
            val name = (teach as? Teach.Ready)?.name ?: return@LaunchedEffect
            val fresh = ArrayList<GestureModel.Sample>()
            delay(1_500)
            for (rep in 1..REPS) {
                teach = Teach.Doing(name, rep, still = false); delay(StudioMath.WINDOW_MS)
                fresh += GestureModel.Sample(name, StudioMath.window(hub.points(), System.currentTimeMillis()))
                teach = Teach.Doing(name, rep, still = true); delay(StudioMath.WINDOW_MS)
                fresh += GestureModel.Sample(GestureModel.NONE, StudioMath.window(hub.points(), System.currentTimeMillis()))
            }
            val merged = GestureModel(model?.samples.orEmpty().filter { it.label != name } + fresh)
            model = merged; save(merged)
            teach = Teach.Done(name, StudioMath.reliability(merged)[name].orEmpty())
        }

        Scaffold(topBar = { TopAppBar(title = { Text("Gesture Studio") }, navigationIcon = { IconButton(onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // What it just recognised, or what to do now while teaching.
                Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    val now = System.currentTimeMillis()
                    val text = when (val t = teach) {
                        is Teach.Ready -> "Get ready: ${t.name}"
                        is Teach.Doing -> if (t.still) "Stay still… (${t.rep} of $REPS)" else "Now: ${t.name}! (${t.rep} of $REPS)"
                        is Teach.Done -> "Learned \"${t.name}\""
                        Teach.Off -> heard?.takeIf { now - it.second < 2_000 }?.first ?: if (model?.labels?.any { it != GestureModel.NONE } == true) "Listening with every sense" else "Teach a gesture below"
                    }
                    val big = teach is Teach.Doing || (teach is Teach.Off && heard?.let { now - it.second < 2_000 } == true)
                    val sc by animateFloatAsState(if (big) 1f else 0.85f, label = "readout")
                    Text(text, Modifier.graphicsLayer { scaleX = sc; scaleY = sc }, fontSize = if (big) 26.sp else 17.sp, fontWeight = FontWeight.Bold)
                }

                // Every sense, live.
                Text("Senses", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Lane.entries.forEach { lane -> LaneRow(lane, lane in liveLanes, hub, tick,
                    switch = when (lane) {
                        Lane.Sound -> soundOn to { on: Boolean -> if (!on) { hub.sound(false); soundOn = false } else if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) { echoOn = false; soundOn = hub.sound(true) } else askMic.launch(Manifest.permission.RECORD_AUDIO) }
                        Lane.Echo -> if (echo != null) echoOn to { on: Boolean -> if (on) { hub.sound(false); soundOn = false }; echoOn = on } else null
                        else -> null
                    },
                    note = when (lane) { Lane.Watch -> "Open Ultimate+ on the watch"; Lane.Sound -> "Claps, snaps and knocks"; Lane.Echo -> "Sound shares the mic with Echo"; else -> null }) }

                // What you've taught, and which senses notice each.
                val taught = model?.labels?.filter { it != GestureModel.NONE }.orEmpty()
                if (taught.isNotEmpty()) {
                    Text("Your gestures", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val rel = remember(model) { model?.let { StudioMath.reliability(it) }.orEmpty() }
                    taught.forEach { name ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                TextButton({ val m = model?.let { GestureModel(it.samples.filter { s -> s.label != name }) }; model = m; save(m) }) { Text("Forget") }
                            }
                            Reliability(rel[name].orEmpty())
                        }
                    }
                }

                // Teaching.
                Text("Teach a gesture", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                when (val t = teach) {
                    Teach.Off -> {
                        var name by remember { mutableStateOf("") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SUGGESTIONS.forEach { s -> FilterChip(name == s, { name = s }, { Text(s) }) }
                        }
                        OutlinedTextField(name, { name = it.take(30) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Or name your own") })
                        Text("You'll do it $REPS times when asked, with a still moment after each. Turn on the senses you want it to learn first.", style = MaterialTheme.typography.bodySmall)
                        Button({ teach = Teach.Ready(name.trim()) }, enabled = name.isNotBlank()) { Text("Start teaching") }
                    }
                    is Teach.Ready, is Teach.Doing -> { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton({ teach = Teach.Off }) { Text("Cancel") } }
                    is Teach.Done -> {
                        Text("How well each sense alone recognises \"${t.name}\" (the combined model uses them all):", style = MaterialTheme.typography.bodySmall)
                        Reliability(t.reliability)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            app.tools.firstOrNull { it.id == "rules" }?.let { rules -> Button({ app.shell.open(rules, ToolLaunch(query = "When I do \"${t.name}\", ")) }) { Text("Make it do something") } }
                            OutlinedButton({ teach = Teach.Off }) { Text("Done") }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val REPS = 5
        val SUGGESTIONS = listOf("Wave over phone", "Clap", "Snap", "Knock twice", "Tap the phone", "Flick wrist", "Cover the phone")
    }
}

/** One sense: name, a dot when it's giving readings, a live line of its last four seconds, and its switch. */
@Composable
private fun LaneRow(lane: Lane, live: Boolean, hub: SenseHub, tick: Long, switch: Pair<Boolean, (Boolean) -> Unit>?, note: String?) {
    val channel = StudioMath.CHANNELS.indexOf(lane.channels.first())
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (live) Color(0xFF3DDC97) else MaterialTheme.colorScheme.outline))
        Column(Modifier.width(110.dp).padding(start = 8.dp)) {
            Text(lane.title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            if (!live && note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val color = MaterialTheme.colorScheme.primary
        Canvas(Modifier.weight(1f).height(34.dp)) {
            val now = tick; val pts = hub.points().filter { it.channel == channel && now - it.t <= 4_000 }
            if (pts.size < 2) return@Canvas
            val lo = pts.minOf { it.v }; val hi = pts.maxOf { it.v }.let { if (it - lo < 1e-3f) lo + 1f else it }
            val p = Path()
            pts.forEachIndexed { i, pt -> val x = size.width * (1 - (now - pt.t) / 4_000f); val y = size.height * (1 - (pt.v - lo) / (hi - lo)); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
            drawPath(p, color, style = Stroke(1.5.dp.toPx()))
            drawLine(color.copy(alpha = 0.15f), Offset(0f, size.height), Offset(size.width, size.height))
        }
        switch?.let { (on, set) -> Switch(on, set, Modifier.padding(start = 6.dp)) }
    }
}

/** Per-sense scores as small bars; senses that were off aren't listed. */
@Composable
private fun Reliability(r: Map<Lane, Double>) {
    if (r.isEmpty()) { Text("No sense noticed it yet: turn on more senses and teach it again.", style = MaterialTheme.typography.bodySmall); return }
    r.entries.sortedByDescending { it.value }.forEach { (lane, a) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(lane.title, Modifier.width(110.dp), style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator({ a.toFloat() }, Modifier.weight(1f), color = if (a >= 0.8) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Text("  ${(a * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        }
    }
}
