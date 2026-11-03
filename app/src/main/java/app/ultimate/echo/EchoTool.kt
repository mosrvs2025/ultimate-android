package app.ultimate.echo

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.runtime.setValue
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.Signal
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.motionOn
import app.ultimate.rules.TriggerType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Echo: the phone's sonar. It senses a hand or a body moving near it, and which way, with no camera and no touch. */
class EchoTool(private val app: UltimateApp) : Tool {
    override val id = "echo"
    override val title = "Echo"
    override val tagline = "Feel movement near the phone, with sound you can't hear"
    override val icon = Icons.Rounded.Waves
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Sonar for your phone")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = false
    override suspend fun refresh() = Unit

    val engine by lazy { EchoEngine(app, app.senses) }

    val sonar by lazy { SonarEngine(app, app.senses) }
    val bench by lazy { EchoBench(sonar) }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        BackHandler(onBack = onExit)
        var distanceMode by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
        val recent by app.senses.recent.collectAsStateWithLifecycle()
        val echoes = recent.filter { it.source == "echo" }
        val running = engine.running || sonar.running
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) { if (distanceMode) sonar.start() else engine.start() } }
        val hardware = remember { EchoProbe.read(app) }
        DisposableEffect(Unit) { onDispose { if (!app.rules.rules.value.any { it.enabled && it.trigger.type == TriggerType.Sensed }) { engine.stop(); sonar.stop() } } }
        Scaffold(topBar = {
            TopAppBar(title = { Text("Echo") }, navigationIcon = { IconButton(onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
        }) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(true to "Distance", false to "Motion").forEach { (d, label) ->
                            androidx.compose.material3.FilterChip(distanceMode == d, { if (distanceMode != d) { engine.stop(); sonar.stop(); distanceMode = d } }, { Text(label) })
                        }
                    }
                }
                if (distanceMode) item { DistanceView(this@EchoTool) }
                else {
                    item { MotionView(engine) }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (running) FilledTonalButton({ engine.stop(); sonar.stop() }) { Text("Stop") }
                        else Button({ ask.launch(Manifest.permission.RECORD_AUDIO) }) { Text(if (distanceMode) "Start measuring" else "Start sensing") }
                        TextButton({ app.tools.firstOrNull { it.id == "rules" }?.let { app.shell.open(it, ToolLaunch(query = "When something comes closer to the phone, ")) } }) { Text("Make it do something") }
                    }
                }
                item {
                    Text(
                        "Private by design: nothing is recorded or kept. The mic is used only while Echo runs, and it stops when you leave unless a rule is listening. You can stop it from the notification shade at any time. Works best with the media volume at half or more.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (distanceMode) item { BenchPanel(bench, running) }
                item { HardwarePanel(hardware) }
                if (echoes.isNotEmpty()) {
                    item { Text("Just sensed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                    items(echoes.take(12), key = { "${it.at}${it.kind}" }) { SignalRow(it) }
                }
            }
        }
    }
}

@Composable
private fun SignalRow(s: Signal) {
    val ago = ((System.currentTimeMillis() - s.at) / 1000).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (s.kind == "approach") "↓" else "↑", style = MaterialTheme.typography.titleLarge, color = if (s.kind == "approach") ApproachColor else RecedeColor, modifier = Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.label, style = MaterialTheme.typography.bodyMedium)
            Text(if (ago < 60) "${ago}s ago" else "${ago / 60} min ago", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val ApproachColor = Color(0xFFFF8A5C)
private val RecedeColor = Color(0xFF5CC8FF)

/**
 * The sonar itself: rings leave the top of the screen (where the speaker is) like the real tone. Movement swells them;
 * something approaching warms and squeezes them together, something leaving cools and spreads them out.
 */
@Composable
private fun FieldView(state: EchoState, modifier: Modifier) {
    val reading = (state as? EchoState.Sensing)?.reading
    val on = state is EchoState.Sensing || state is EchoState.Calibrating
    val motion by animateFloatAsState(reading?.motion ?: 0f, tween(180), label = "m")
    val dir = reading?.direction ?: EchoReading.Direction.Still
    val warm by animateFloatAsState(when (dir) { EchoReading.Direction.Approach -> 1f; EchoReading.Direction.Recede -> -1f; else -> 0f }, tween(300), label = "w")
    val t = if (on && motionOn()) rememberInfiniteTransition(label = "sonar").animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "t").value else 0f
    val base = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(modifier) {
        drawRect(bg)
        val origin = Offset(size.width / 2, 0f)
        val reach = size.height * 1.1f
        val tint = when {
            warm > 0 -> androidx.compose.ui.graphics.lerp(base, ApproachColor, warm)
            warm < 0 -> androidx.compose.ui.graphics.lerp(base, RecedeColor, -warm)
            else -> base
        }
        val rings = 6
        for (i in 0 until rings) {
            // Approaching: rings bunch up; receding: they spread.
            val phase = ((i.toFloat() / rings) + t) % 1f
            val r = reach * phase * (1f - 0.25f * warm.coerceAtLeast(0f)) + reach * 0.15f * (-warm).coerceAtLeast(0f) * phase
            val alpha = if (on) (1f - phase) * (0.35f + 0.65f * motion) else 0.12f * (1f - i / rings.toFloat())
            drawCircle(tint.copy(alpha = alpha.coerceIn(0f, 1f)), r.coerceAtLeast(1f), origin, style = Stroke(width = (2f + 10f * motion) * density))
        }
        drawCircle(tint, 10.dp.toPx() * (1f + motion), origin)
    }
}


/** Motion mode: the original Doppler sense, kept exactly as it worked. The rings are a drawing driven by measured motion. */
@Composable
private fun MotionView(engine: EchoEngine) {
    val state by engine.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box {
            FieldView(state, Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(28.dp)))
            TruthTag(Truth.Illustration, Modifier.padding(10.dp))
        }
        Text(
            when (val s = state) {
                EchoState.Off -> "Motion mode plays one steady tone and feels movement through the Doppler shift of its echo: which way, not how far."
                EchoState.Calibrating -> "Listening to the room for a second. Keep still…"
                is EchoState.Sensing -> when (s.reading.direction) {
                    EchoReading.Direction.Approach -> "Something is coming closer"
                    EchoReading.Direction.Recede -> "Something is moving away"
                    EchoReading.Direction.Still -> "Still. Move a hand near the top of the phone"
                }
                is EchoState.Failed -> s.why
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (state is EchoState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/** Distance mode: the ranging sonar, its echogram, a map of the space in front of the phone, and the raw spectrum. */
@Composable
private fun DistanceView(tool: EchoTool) {
    val state by tool.sonar.state.collectAsStateWithLifecycle()
    val echoFall = remember { Waterfall(160, 140) }
    val specFall = remember { Waterfall(160, 128) }
    val trail = remember { Trail() }
    val (tick, bump) = rememberTick()
    var view by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        var last: SonarFrame? = null
        tool.sonar.state.collect { s ->
            val f = (s as? SonarState.Running)?.frame ?: return@collect
            if (f === last) return@collect
            last = f
            echoFall.push(f.fix.motion, ::heat)
            specFall.push(f.spectrum, ::heat)
            if (!f.fix.rangeM.isNaN() && !f.phoneMoving) trail.add(Trail.Point(f.fix.rangeM, f.bearingDeg ?: f.lean?.let { it * 35.0 }, f.fix.confidence, System.currentTimeMillis()))
            bump()
        }
    }
    val frame = (state as? SonarState.Running)?.frame
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.material3.SecondaryTabRow(view) {
            listOf("Echogram", "Space", "Spectrum").forEachIndexed { i, t -> androidx.compose.material3.Tab(view == i, { view = i }, text = { Text(t) }) }
        }
        val mod = Modifier.fillMaxWidth().height(280.dp)
        when (view) {
            0 -> Echogram(echoFall, tick, frame?.fix?.rangeM ?: Double.NaN, mod)
            1 -> SpaceMap(trail, tick, frame?.stereo == true, mod)
            else -> Spectrogram(specFall, tick, mod)
        }
        when (val s = state) {
            SonarState.Off -> Text("Distance mode repeats a short chirp too high to hear and times its echoes, like a bat. Each sample of delay is 3.6 mm of distance.", style = MaterialTheme.typography.titleSmall)
            SonarState.Warming -> Text("Learning the room for half a second. Keep still…", style = MaterialTheme.typography.titleSmall)
            is SonarState.Failed -> Text(s.why, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            is SonarState.Running -> {
                val f = s.frame
                if (f.phoneMoving) Text("The phone is moving, so readings are paused: everything seems to move when the phone does.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Readout("distance", if (f.fix.rangeM.isNaN()) "—" else "%.0f cm".format(f.fix.rangeM * 100), Truth.Measured)
                    Readout("speed", if (f.fix.velocity.isNaN()) "—" else "%+.2f m/s".format(f.fix.velocity), Truth.Estimate)
                    Readout("direction", f.bearingDeg?.let { "%+.0f°".format(it) } ?: f.lean?.let { when (it) { -1 -> "left"; 1 -> "right"; else -> "centre" } } ?: if (f.stereo) "—" else "1 mic", Truth.Estimate)
                }
                Text(
                    "Link %.0f× above noise · confidence %.1f · %.1f ms a frame%s".format(f.fix.linkSnr, f.fix.confidence, f.processMs, if (f.stereo) " · two microphones" else ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What Android says about this phone's audio and motion hardware, so results can be read in context. */
@Composable
private fun HardwarePanel(h: EchoHardware) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("This phone's hardware", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton({ open = !open }) { Text(if (open) "Hide" else "Show") }
        }
        if (open) {
            TruthTag(Truth.Reported)
            fun yn(v: String?) = when (v) { "true" -> "yes"; "false" -> "no"; null -> "doesn't say"; else -> v }
            listOf(
                "Speaker reaches near-ultrasound" to yn(h.speakerUltrasound),
                "Microphone reaches near-ultrasound" to yn(h.micUltrasound),
                "Unprocessed microphone (no filtering)" to if (h.unprocessed) "yes" else "no",
                "Output sample rate" to (h.outputRate?.let { "$it Hz" } ?: "doesn't say"),
                "Gyroscope (pauses readings while the phone moves)" to if (h.gyroscope) "yes" else "no",
                "Microphone spacing" to (h.micSpacingM?.let { "%.1f cm".format(it * 100) } ?: "not stated, so direction can only be a lean"),
            ).forEach { (k, v) -> Row { Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text(v, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) } }
            Text("Built-in microphones: ${h.mics.size.takeIf { it > 0 } ?: "not listed"}", style = MaterialTheme.typography.bodySmall)
            h.mics.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Runs the repeatable bench and shows the report, ready to copy. */
@Composable
private fun BenchPanel(bench: EchoBench, sensing: Boolean) {
    val step by bench.step.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Bench test", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
        when (val s = step) {
            EchoBench.Step.Idle -> {
                Text("About 30 seconds: false alarms in a still room, accuracy with your hand at 10, 20 and 30 cm, processing time and battery draw. Lay the phone flat, screen up.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Button({ scope.launch { bench.run(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL) } }, enabled = !sensing) { Text("Run the bench") }
            }
            is EchoBench.Step.Prompt -> Text("${s.text}  ${s.secondsLeft}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            is EchoBench.Step.Failed -> { Text(s.why, color = MaterialTheme.colorScheme.error); TextButton({ bench.reset() }) { Text("Try again") } }
            is EchoBench.Step.Done -> {
                Text(s.report.text, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Row {
                    TextButton({ app.ultimate.core.copyText(context, "Echo bench", s.report.text) }) { Text("Copy results") }
                    TextButton({ bench.reset() }) { Text("Run again") }
                }
            }
        }
    }
}
