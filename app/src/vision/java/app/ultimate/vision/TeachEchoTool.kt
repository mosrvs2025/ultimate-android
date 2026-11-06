package app.ultimate.vision

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.air.AirGestures
import app.ultimate.air.HandPose
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.echo.EchoTool
import app.ultimate.echo.GestureBrain
import app.ultimate.echo.GestureModel
import app.ultimate.echo.LearnedNames
import app.ultimate.echo.SonarHistory
import app.ultimate.echo.SonarState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Teach Echo: the camera watches your hand and labels the sonar, so Echo learns your gestures by sound and can then
 * recognise them with the camera off. Nothing is uploaded; the examples stay in the app.
 */
class TeachEchoTool(private val app: UltimateApp) : Tool {
    override val id = "echo-teach"
    override val title = "Teach Echo"
    override val tagline = "Show the camera a few gestures; Echo learns them by sound, for use with the camera off"
    override val icon = Icons.Rounded.School
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Gestures without the camera")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = false
    override suspend fun refresh() = Unit

    private sealed interface Step {
        data object Choose : Step
        data class Show(val index: Int) : Step
        data class Rest(val secondsLeft: Int) : Step
        data object Training : Step
        data class Result(val brain: GestureBrain) : Step
        data object Try : Step
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        val context = LocalContext.current
        val haptics = LocalHapticFeedback.current
        val echo = app.tools.firstOrNull { it.id == "echo" } as? EchoTool
        if (echo == null) { Text("Teach Echo needs Echo, which this edition doesn't have."); return }
        var step by remember { mutableStateOf<Step>(Step.Choose) }
        BackHandler { if (step is Step.Choose) onExit() else step = Step.Choose }
        var allowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
        var existing by remember { mutableStateOf(echo.sonar.learned) }
        val chosen = remember { mutableStateListOf<String>().apply { addAll(LearnedNames.DEFAULT) } }
        // New examples from this session; added to what Echo already knows when you keep them.
        val samples = remember { mutableStateListOf<GestureModel.Sample>() }
        val history = remember { SonarHistory() }
        val pose = remember { MutableStateFlow<HandPose?>(null) }
        val hand by pose.collectAsStateWithLifecycle()
        val detector = remember { AirGestures(mirrored = true) }
        var lastCamera by remember { mutableStateOf<Pair<String, Long>?>(null) }
        var added by remember { mutableIntStateOf(0) }
        val sonarState by echo.sonar.state.collectAsStateWithLifecycle()
        val learnedLast by echo.sonar.lastLearned.collectAsStateWithLifecycle()

        val sensing = step !is Step.Choose && step !is Step.Result && step !is Step.Training
        val view = LocalView.current
        // The sonar runs while teaching and trying; its frames go into the short history the camera labels from.
        DisposableEffect(sensing) {
            // Only stop the sonar on the way out if teaching started it; Echo may have been running already.
            var startedHere = false
            if (sensing) {
                view.keepScreenOn = true
                echo.sonar.onFeatures = { t, f -> history.add(t, f) }
                if (!echo.sonar.running) { echo.sonar.start(); startedHere = true }
            }
            onDispose { echo.sonar.onFeatures = null; view.keepScreenOn = false; if (startedHere) echo.sonar.stop() }
        }

        fun need(kind: String) = PER_GESTURE - samples.count { it.label == kind }
        // The camera labels the sound: a recognised gesture cuts out the sonar around it, a quiet moment becomes "none".
        LaunchedEffect(hand, step) {
            val s = step as? Step.Show ?: return@LaunchedEffect
            val now = System.currentTimeMillis()
            val g = detector.feed(hand, now) ?: return@LaunchedEffect
            lastCamera = g.kind to now
            val want = chosen.getOrNull(s.index) ?: return@LaunchedEffect
            if (g.kind != want) return@LaunchedEffect
            delay(150) // the sound of the gesture's end arrives a moment after the camera sees it
            history.window(System.currentTimeMillis())?.let { x ->
                samples += GestureModel.Sample(want, x); added++
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (need(want) <= 0) step = if (s.index + 1 < chosen.size) Step.Show(s.index + 1) else Step.Rest(REST_S)
            }
        }
        // Between gestures, moments the camera saw nothing deliberate teach Echo what "nothing" sounds like.
        val showing = step is Step.Show
        LaunchedEffect(showing) {
            while (showing) {
                delay(700)
                val now = System.currentTimeMillis()
                if (now - (lastCamera?.second ?: 0) > 1_200 && samples.count { it.label == GestureModel.NONE } < PER_GESTURE * chosen.size)
                    history.window(now)?.let { samples += GestureModel.Sample(GestureModel.NONE, it) }
            }
        }
        // The rest: a few seconds of stillness, sampled twice a second, then the score.
        val resting = step is Step.Rest
        LaunchedEffect(resting) {
            if (!resting) return@LaunchedEffect
            for (left in REST_S downTo 1) {
                step = Step.Rest(left)
                repeat(2) { delay(500); history.window(System.currentTimeMillis())?.let { samples += GestureModel.Sample(GestureModel.NONE, it) } }
            }
            // Your new examples replace the old ones for the gestures you just taught; the rest are kept.
            step = Step.Training
            val merged = GestureBrain.merge(existing?.examples, samples.toList(), chosen.toList())
            step = Step.Result(withContext(Dispatchers.Default) { GestureBrain.build(merged) })
        }

        Scaffold(topBar = { TopAppBar(title = { Text("Teach Echo") }, navigationIcon = { IconButton({ if (step is Step.Choose) onExit() else step = Step.Choose }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val s = step) {
                    Step.Choose -> {
                        Text("Echo hears your hand with sound the phone makes. Show the camera a few gestures and Echo learns what each one sounds like, so it can recognise them later with the camera off.", style = MaterialTheme.typography.bodyLarge)
                        Text("Prop the phone up facing you, its bottom edge toward your hand, and keep your hand about 15–40 cm away.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Gestures to teach", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LearnedNames.TEACHABLE.forEach { (kind, label) ->
                                FilterChip(kind in chosen, { if (kind in chosen) chosen -= kind else chosen += kind }, { Text(label) })
                            }
                        }
                        existing?.let { b ->
                            val m = b.examples
                            Text("Echo already knows: " + m.labels.filter { it != GestureModel.NONE }.joinToString { "${LearnedNames.label(it)} (${m.count(it)})" } +
                                ". Teaching a gesture again replaces its examples.", style = MaterialTheme.typography.bodySmall)
                            Text("${m.samples.size} examples. ${b.report.note}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!allowed) Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow the camera") }
                            else Button({ samples.clear(); added = 0; detector.reset(); history.clear(); step = Step.Show(0) }, enabled = chosen.isNotEmpty()) { Text("Start teaching") }
                            if (existing != null) OutlinedButton({ step = Step.Try }) { Text("Try with the camera off") }
                        }
                        if (existing != null) TextButton({ GestureBrain.forget(context); echo.sonar.learned = null; existing = null }) { Text("Forget what Echo learned") }
                    }
                    is Step.Show -> {
                        val kind = chosen[s.index]
                        Text("${s.index + 1} of ${chosen.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(LearnedNames.label(kind), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(HOW[kind] ?: "Do it clearly, then let your hand rest a moment.", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            val got = PER_GESTURE - need(kind).coerceAtLeast(0)
                            repeat(PER_GESTURE) { i ->
                                val on = i < got
                                val sc by animateFloatAsState(if (on) 1f else 0.7f, label = "dot")
                                Box(Modifier.size(22.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(CircleShape)
                                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest))
                            }
                            Text("$got of $PER_GESTURE", Modifier.padding(start = 6.dp))
                        }
                        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
                            GestureCamera(true, pose)
                            val now = System.currentTimeMillis()
                            lastCamera?.takeIf { now - it.second < 1_200 }?.let { (k, _) ->
                                val right = k == kind
                                Text(if (right) "✓ ${LearnedNames.label(k)}" else "Camera saw ${AirGestures.ALL.firstOrNull { it.first == k }?.second ?: k}",
                                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 8.dp),
                                    color = if (right) Color(0xFF3DDC97) else Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                            SonarStatus(sonarState, history, Modifier.align(Alignment.BottomCenter).padding(10.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ samples.removeAll { it.label == kind }; step = if (s.index + 1 < chosen.size) Step.Show(s.index + 1) else Step.Rest(REST_S) }) { Text("Skip this one") }
                        }
                    }
                    is Step.Rest -> {
                        Text("Now rest your hand", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Keep still for ${s.secondsLeft} s, so Echo learns what nothing sounds like and doesn't mistake it for a gesture.", style = MaterialTheme.typography.bodyLarge)
                        LinearProgressIndicator({ 1f - s.secondsLeft / REST_S.toFloat() }, Modifier.fillMaxWidth())
                    }
                    Step.Training -> {
                        Text("Learning…", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Echo is comparing its two ways of learning on your examples. This takes a few seconds.", style = MaterialTheme.typography.bodyLarge)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is Step.Result -> {
                        val acc = remember(s.brain) { s.brain.examples.accuracy() }
                        Text("How well Echo tells them apart", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Each example was hidden in turn and recognised from the others, so this is an honest score from your own gestures. Above about 80% works well; lower ones get mixed up.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        acc.entries.sortedBy { it.key == GestureModel.NONE }.forEach { (k, a) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (k == GestureModel.NONE) "Nothing" else LearnedNames.label(k), Modifier.fillMaxWidth(0.32f), fontWeight = FontWeight.SemiBold)
                                LinearProgressIndicator({ a.toFloat() }, Modifier.weight(1f).padding(horizontal = 8.dp),
                                    color = if (a >= 0.8) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                Text("%.0f%%".format(a * 100), Modifier.size(width = 44.dp, height = 20.dp))
                            }
                        }
                        Text(s.brain.report.note, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            color = if (s.brain.usesNet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ GestureBrain.save(context, s.brain); echo.sonar.learned = s.brain; existing = s.brain; step = Step.Try }) { Text("Keep and try it") }
                            OutlinedButton({ step = Step.Choose }) { Text("Teach again") }
                        }
                    }
                    Step.Try -> {
                        Text("Camera off", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Echo is listening on its own now. Do the gestures you taught; each one it hears shows up here, and can start a Rule (\"When Echo hears a swipe left…\").", style = MaterialTheme.typography.bodyLarge)
                        Box(Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            val now = System.currentTimeMillis()
                            val shown = learnedLast?.takeIf { now - it.second < 1_500 }
                            val sc by animateFloatAsState(if (shown != null) 1f else 0.8f, label = "heard")
                            Text(shown?.let { LearnedNames.label(it.first) } ?: "Listening…", Modifier.graphicsLayer { scaleX = sc; scaleY = sc },
                                fontSize = if (shown != null) 40.sp else 22.sp, fontWeight = FontWeight.Black)
                            SonarStatus(sonarState, history, Modifier.align(Alignment.BottomCenter).padding(10.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ step = Step.Choose }) { Text("Done") }
                            OutlinedButton({ samples.clear(); added = 0; detector.reset(); history.clear(); step = Step.Show(0) }) { Text("Teach more") }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val PER_GESTURE = 6
        const val REST_S = 5
        val HOW = mapOf(
            "swipe_left" to "Sweep your open hand from right to left across the phone, then rest.",
            "swipe_right" to "Sweep your open hand from left to right across the phone, then rest.",
            "swipe_up" to "Sweep your hand upward past the phone, then rest.",
            "swipe_down" to "Sweep your hand downward past the phone, then rest.",
            "push" to "Move your open palm toward the phone, about 15 cm, then rest.",
            "pull" to "Start close, then draw your palm away from the phone, then rest.",
            "hold" to "Hold your open palm still in front of the phone for a second.",
        )
    }
}

/** Whether the sonar is hearing anything, so a silent microphone is noticed while teaching. */
@Composable
private fun SonarStatus(state: SonarState, history: SonarHistory, modifier: Modifier) {
    val now = System.currentTimeMillis()
    val text = when (state) {
        is SonarState.Failed -> "Echo: ${state.why}"
        SonarState.Off, SonarState.Warming -> "Echo is starting…"
        is SonarState.Running -> if (history.lively(now)) "Echo hears your hand" else "Echo is listening"
    }
    Text(text, modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
}
