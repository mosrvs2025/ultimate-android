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
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
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
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
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
        // From the engines' live states, so Start and Stop always show what is really happening.
        val sonarState by sonar.state.collectAsStateWithLifecycle()
        val motionState by engine.state.collectAsStateWithLifecycle()
        val running = sonarState is SonarState.Running || sonarState is SonarState.Warming || motionState is EchoState.Sensing || motionState is EchoState.Calibrating
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
                // The next step, in plain sight: teach Echo your gestures so they work with the camera off.
                app.tools.firstOrNull { it.id == "echo-teach" }?.let { teach ->
                    item {
                        val known = sonar.learned
                        androidx.compose.material3.Card(Modifier.fillMaxWidth().clickable { engine.stop(); sonar.stop(); app.shell.open(teach) },
                            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Icon(teach.icon, null, Modifier.padding(end = 14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(if (known == null) "Teach Echo your gestures" else "Teach Echo more gestures", fontWeight = FontWeight.SemiBold)
                                    Text(if (known == null) "About a minute with the camera; then swipes, push and pull work by sound alone."
                                        else "${known.examples.samples.size} examples so far. ${known.report.note}", style = MaterialTheme.typography.bodySmall)
                                }
                                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowForward, null)
                            }
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
                        "Private by design: no audio is ever recorded or kept, only the numbers on screen (and a session log if you record one). The mic is used only while Echo runs, and it stops when you leave unless a rule is listening. You can stop it from the notification shade at any time. Works best with the media volume at half or more.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (distanceMode) {
                    app.tools.firstOrNull { it.id == "echo-camera" }?.let { cam ->
                        item {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).clickable { sonar.stop(); app.shell.open(cam) }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Calibrate with the camera", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                    Text("The camera sees your hand while Echo hears it: 20 seconds gives a correction fitted to hundreds of readings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                }
                                Icon(cam.icon, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                    }
                    item { BenchCard(bench, sonar, running) }
                    item { LogCard(sonar) }
                }
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
        // Talking can look like movement; say when Echo is ignoring it.
        val talking by engine.voicePaused.collectAsStateWithLifecycle()
        if (talking) Text("🎤 You're talking: movement paused for a moment", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
    val gesture by tool.sonar.lastGesture.collectAsStateWithLifecycle()
    // The farthest distance detected while sensing this time: Echo's real reach on this phone, right now.
    var farthest by remember { androidx.compose.runtime.mutableDoubleStateOf(0.0) }
    frame?.fix?.rangeM?.let { if (!it.isNaN() && !frame.phoneMoving && it > farthest) farthest = it }
    if (state is SonarState.Off) farthest = 0.0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.material3.SecondaryTabRow(view) {
            listOf("Reach", "Echogram", "Space", "Spectrum").forEachIndexed { i, t -> androidx.compose.material3.Tab(view == i, { view = i }, text = { Text(t, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }) }
        }
        val mod = Modifier.fillMaxWidth().height(280.dp)
        when (view) {
            0 -> ReachView(frame?.fix?.rangeM ?: Double.NaN, frame?.fix?.confidence ?: 0.0, frame?.fix?.velocity ?: Double.NaN, farthest, gesture, state is SonarState.Running || state is SonarState.Warming, Modifier.fillMaxWidth().height(340.dp))
            1 -> Echogram(echoFall, tick, frame?.fix?.rangeM ?: Double.NaN, mod)
            2 -> SpaceMap(trail, tick, frame?.stereo == true, mod)
            else -> Spectrogram(specFall, tick, mod)
        }
        when (val s = state) {
            SonarState.Off -> Text("Distance mode repeats a short chirp too high to hear and times its echoes, like a bat. Each sample of delay is 3.6 mm of distance.", style = MaterialTheme.typography.titleSmall)
            SonarState.Warming -> Text("Learning the room for half a second. Keep still…", style = MaterialTheme.typography.titleSmall)
            is SonarState.Failed -> Text(s.why, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            is SonarState.Running -> {
                val f = s.frame
                if (f.phoneMoving) Text("The phone is moving, so readings are paused: everything seems to move when the phone does.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                if (f.fix.interference) Text("Other sound in Echo's band (talking, music, a hiss), so this moment is ignored rather than mistaken for a hand.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Readout(if (f.calibrated) "distance · calibrated" else "distance", if (f.fix.rangeM.isNaN()) "—" else "%.0f cm".format(f.fix.rangeM * 100), Truth.Measured)
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

/** The way into the bench: one inviting card. The bench itself runs full screen, one step at a time. */
@Composable
private fun BenchCard(bench: EchoBench, sonar: SonarEngine, sensing: Boolean) {
    val step by bench.step.collectAsStateWithLifecycle()
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.secondaryContainer).clickable(enabled = !sensing || step !is EchoBench.Step.Idle) { open = true }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Test Echo on this phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                when (step) { is EchoBench.Step.Done -> "Results ready. Tap to see them"; is EchoBench.Step.Prompt -> "Running…"; else -> "A 30-second guided test of accuracy, false alarms, speed and battery" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Icon(Icons.Rounded.Speed, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
    if (open) BenchDialog(bench, sonar) { open = false }
}

@Composable
private fun BenchDialog(bench: EchoBench, sonar: SonarEngine, onClose: () -> Unit) {
    // Measured here, in the app's own window, where Android reports the system bars; a dialog's window often reports
    // none, which hid the bottom buttons behind the navigation bar on the Fold's cover screen.
    val bars = androidx.compose.foundation.layout.WindowInsets.systemBars.asPaddingValues()
    val top = bars.calculateTopPadding(); val bottom = bars.calculateBottomPadding()
    val step by bench.step.collectAsStateWithLifecycle()
    val live by sonar.state.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var job by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var showRuler by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (showRuler) androidx.compose.ui.window.Dialog({ showRuler = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { androidx.compose.foundation.layout.Box(Modifier.padding(top = top, bottom = bottom)) { app.ultimate.ruler.RulerScreen { showRuler = false } } }
    fun close() { job?.cancel(); if (step is EchoBench.Step.Prompt) { sonar.stop(); bench.reset() }; onClose() }
    androidx.compose.ui.window.Dialog(::close, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            // Inside the safe area: on the Fold's cover screen the navigation bar otherwise hides the bottom buttons.
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(top = top, bottom = bottom).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Echo test", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(::close) { Icon(Icons.Rounded.Close, "Close") }
            }
            when (val s = step) {
                EchoBench.Step.Idle -> {
                    Text("How well does Echo hear on this phone?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    listOf(
                        "1" to "Lay the phone flat on a table, screen up, with the media volume at half or more.",
                        "2" to "For the first 12 seconds, keep your hands away. Echo learns the room and counts false alarms.",
                        "3" to "Then hold your palm over the bottom edge of the phone (where its speaker and microphone are) at 10, 20 and 30 cm, arm coming in from the side so your hand is the nearest thing. The screen shows what Echo reads.",
                        "4" to "For exact heights, measure a mug, a box or a stack of books with the Ruler, stand it beside the phone and hold your palm level with its top.",
                    ).forEach { (n, t) ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Text(n, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                            Text(t, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Button({ job = scope.launch { bench.run(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL) } }, Modifier.fillMaxWidth().height(56.dp)) { Text("Start the test") }
                    androidx.compose.material3.OutlinedButton({ showRuler = true }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Straighten, null); Text("  Open the ruler") }
                }
                is EchoBench.Step.Prompt -> {
                    androidx.compose.material3.LinearProgressIndicator(progress = { (s.index + 1f) / s.total }, Modifier.fillMaxWidth())
                    Text("Step ${s.index + 1} of ${s.total}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(s.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(s.text, style = MaterialTheme.typography.titleMedium)
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        HandPicture(s.targetCm, s.handAway, Modifier.matchParentSize())
                        TruthTag(Truth.Illustration, Modifier.align(Alignment.TopStart))
                        Text("${s.secondsLeft}", Modifier.align(Alignment.TopEnd), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    if (s.targetCm != null) {
                        val read = ((live as? SonarState.Running)?.frame?.fix?.rangeM ?: Double.NaN) * 100
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Echo reads", style = MaterialTheme.typography.labelLarge)
                                Text(if (read.isNaN()) "nothing yet" else "%.0f cm".format(read), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            }
                            TruthTag(Truth.Measured)
                        }
                    }
                }
                is EchoBench.Step.Failed -> {
                    Text("The test stopped", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(s.why, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    Box(Modifier.weight(1f))
                    Button({ bench.reset() }, Modifier.fillMaxWidth().height(56.dp)) { Text("Try again") }
                }
                is EchoBench.Step.Done -> {
                    val r = s.report
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Text("Results", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
                        item { ResultLine("False alarms in a still room", "%.0f%%".format(r.falseRate * 100), r.falseRate <= 0.05) }
                        r.rows.forEach { row ->
                            item { AccuracyRow(row) }
                        }
                        item { Text(r.text, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    CalibrateRow(r, sonar)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button({ app.ultimate.core.copyText(context, "Echo bench", r.text); android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show() }, Modifier.weight(1f)) { Text("Copy results") }
                        androidx.compose.material3.OutlinedButton({ bench.reset() }, Modifier.weight(1f)) { Text("Run again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultLine(label: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (good) Color(0xFF3DDC97) else Color(0xFFFF8A5C))
    }
}

/** Where your hand was, against where Echo put it, on one 0-40 cm ruler. */
@Composable
private fun AccuracyRow(r: BenchMath.Row) {
    val good = r.medianCm != null && kotlin.math.abs(r.medianCm - r.heldCm) <= 3
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hand at ${r.heldCm} cm", Modifier.weight(1f))
            Text(r.medianCm?.let { "read %.0f cm".format(it) } ?: "not detected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (good) Color(0xFF3DDC97) else Color(0xFFFF8A5C))
        }
        val line = MaterialTheme.colorScheme.outline
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            fun x(cm: Double) = (cm / 40.0).toFloat().coerceIn(0f, 1f) * size.width
            drawLine(line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx())
            drawCircle(Color.White, 7.dp.toPx(), Offset(x(r.heldCm.toDouble()), size.height / 2), style = Stroke(2.dp.toPx()))
            r.medianCm?.let { m -> drawCircle(if (good) Color(0xFF3DDC97) else Color(0xFFFF8A5C), 6.dp.toPx(), Offset(x(m), size.height / 2)) }
        }
        Text("Ring: your hand · dot: Echo · detected in %.0f%% of frames".format(r.detectRate * 100), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A side view of the phone lying flat with a hand above it at [targetCm], or away for the hands-off steps. A drawing, not a measurement. */
@Composable
private fun HandPicture(targetCm: Int?, away: Boolean, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val bob = if (targetCm != null && motionOn()) rememberInfiniteTransition(label = "bob").animateFloat(-1f, 1f, infiniteRepeatable(tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "b").value else 0f
    Canvas(modifier) {
        val base = size.height * 0.85f
        val cmPx = (size.height * 0.7f) / 35f
        // The phone, flat, screen up; the top edge (speaker and top mic) on the right.
        drawRoundRect(ink.copy(alpha = 0.85f), Offset(size.width * 0.2f, base), Size(size.width * 0.6f, 8.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        drawCircle(accent, 4.dp.toPx(), Offset(size.width * 0.76f, base - 3.dp.toPx()))
        listOf(10, 20, 30).forEach { cm ->
            val y = base - cm * cmPx
            drawLine(ink.copy(alpha = if (cm == targetCm) 0.6f else 0.15f), Offset(size.width * 0.1f, y), Offset(size.width * 0.95f, y), strokeWidth = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        }
        if (targetCm != null && !away) {
            val y = base - (targetCm + bob * 0.5f) * cmPx
            drawRoundRect(accent, Offset(size.width * 0.6f, y - 6.dp.toPx()), Size(size.width * 0.3f, 12.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            drawLine(accent.copy(alpha = 0.5f), Offset(size.width * 0.76f, base - 6.dp.toPx()), Offset(size.width * 0.76f, y + 8.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/** Records a session of Echo's numbers (no audio) to share for diagnosis, with markers you tap as you go. */
@Composable
private fun LogCard(sonar: SonarEngine) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val log = androidx.compose.runtime.remember { EchoLog() }
    var rec by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var count by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(rec) {
        if (!rec) return@LaunchedEffect
        var last: SonarFrame? = null
        sonar.state.collect { s -> (s as? SonarState.Running)?.frame?.let { f -> if (f !== last) { last = f; log.add(f); count = log.frames } } }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Record a session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (rec) "Recording what Echo measures: $count frames. Tap a marker whenever you change what you're doing."
            else "Saves every measurement (numbers only, never audio) with markers you tap, as a file you can share for a precise diagnosis.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (rec) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("hand away", "hand at 10 cm", "hand at 20 cm", "hand at 30 cm", "hand moving", "something else").forEach { m ->
                    androidx.compose.material3.AssistChip({ log.mark(m); android.widget.Toast.makeText(context, "Marked: $m", android.widget.Toast.LENGTH_SHORT).show() }, { Text(m) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!rec) Button({ if (!sonar.running) sonar.start(); log.start(); count = 0; rec = true }) { Text("Start recording") }
            else Button({
                rec = false
                log.finish(context)?.let { uri ->
                    context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/csv").putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share Echo session"))
                }
            }) { Text("Stop and share") }
        }
    }
}


/**
 * After a bench run: fit this phone's correction from the heights you held and what Echo read, and say how close the
 * fit gets, so a calibration is never mistaken for accuracy it doesn't have.
 */
@Composable
private fun CalibrateRow(r: BenchReport, sonar: SonarEngine) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val fit = androidx.compose.runtime.remember(r) { EchoCalibration.fit(r.rows.map { it.heldCm.toDouble() }, r.rows.map { it.medianCm ?: Double.NaN }) }
    var active by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(sonar.calibration) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Calibrate for this phone", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
        if (fit == null) Text("These results can't be turned into a correction: Echo read about the same at every height, or missed too many. Try again with the hand over the bottom edge.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        else {
            Text("Corrects Echo's readings to the heights you held. After correcting, it would still miss by about ±%.0f cm on these results.".format(fit.rmsErrorCm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ EchoCalibration.save(context, fit); sonar.calibration = fit; active = fit; android.widget.Toast.makeText(context, "Calibrated", android.widget.Toast.LENGTH_SHORT).show() }) { Text(if (active != null) "Calibrate again" else "Use this calibration") }
                if (active != null) TextButton({ EchoCalibration.save(context, null); sonar.calibration = null; active = null }) { Text("Remove") }
            }
        }
    }
}
