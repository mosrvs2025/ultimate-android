package app.ultimate.vision

import android.Manifest
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.air.BodyPose
import app.ultimate.health.Exercise
import app.ultimate.health.RepTracker
import app.ultimate.health.WorkoutCameraHook
import app.ultimate.health.WorkoutLog
import app.ultimate.health.WorkoutSet
import app.ultimate.health.WorkoutStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import java.util.Locale

/** Loaded by name (like the other camera tools) in builds that carry body tracking: it hands the Health screen its camera counter. */
object WorkoutCameraInstall {
    init { WorkoutCameraHook.content = { store, onExit -> WorkoutScreen(store, onExit) } }
}

/** Counts aloud, if asked: one number per rep, so you never have to look at the phone. */
private class Voice(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { s -> ready = s == TextToSpeech.SUCCESS; if (ready) runCatching { tts.language = Locale.getDefault() } }
    fun say(text: String) { if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "w") }
    fun close() { runCatching { tts.stop(); tts.shutdown() } }
}

private enum class Phase { SETUP, COUNTDOWN, RUNNING, DONE }
private val CLEAR = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

/**
 * Prop the phone up, pick an exercise, start the set and just do it: the camera finds your joints (on the phone; the picture is dropped, never
 * saved), counts each full repetition, says what to fix, and keeps the set in your Health screen. Half-reps are not counted.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun WorkoutScreen(store: WorkoutStore, onExit: () -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    BackHandler(onBack = onExit)
    if (!remember { hasPoseModel(context) }) { Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { Text("Body tracking isn't in this build, so the camera can't count reps. You can still log a set by hand.", style = MaterialTheme.typography.bodyLarge); TextButton(onExit) { Text("Back") } }; return }
    if (!allowed) { Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) { Text("Counting reps uses the camera. Pictures are looked at on the phone to find your joints and then dropped; nothing is saved or sent.", style = MaterialTheme.typography.bodyLarge); Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow the camera") }; TextButton(onExit) { Text("Back") } }; return }

    val body = remember { MutableStateFlow<BodyPose?>(null) }
    var front by remember { mutableStateOf(true) }
    var exercise by remember { mutableStateOf(Exercise.SQUAT) }
    var phase by remember { mutableStateOf(Phase.SETUP) }
    var voiceOn by remember { mutableStateOf(true) }
    val voice = remember { Voice(context) }
    DisposableEffect(Unit) { onDispose { voice.close() } }
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    val tracker = remember(exercise) { RepTracker(exercise) }
    var reps by remember { mutableIntStateOf(0) }; var holdMs by remember { mutableLongStateOf(0L) }
    var cue by remember { mutableStateOf<String?>(null) }; var progress by remember { mutableFloatStateOf(0f) }; var tracking by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(3) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var saved by remember { mutableStateOf<WorkoutSet?>(null) }
    val pose by body.collectAsState()

    fun say(t: String) { if (voiceOn) voice.say(t) }
    fun finish() {
        val secs = if (exercise.timed) (holdMs / 1000).toInt() else 0
        if (reps > 0 || secs >= 3) { val s = WorkoutSet(java.util.UUID.randomUUID().toString(), exercise.name, System.currentTimeMillis(), LocalDate.now().toEpochDay(), if (exercise.timed) 0 else reps, secs, tracker.averagePeak, true); store.add(s); saved = s
            say(if (exercise.timed) "Set done. ${WorkoutLog.mmss(secs)}." else "Set done. $reps reps.") }
        else { saved = null; say("No reps counted.") }
        phase = Phase.DONE
    }
    LaunchedEffect(phase) {
        if (phase == Phase.COUNTDOWN) { tracker.reset(); reps = 0; holdMs = 0; cue = null; progress = 0f; for (n in 3 downTo 1) { count = n; say("$n"); delay(1000) }; say("Go"); startedAt = System.currentTimeMillis(); phase = Phase.RUNNING }
        if (phase == Phase.RUNNING) {
            var lastSec = 0
            while (true) {
                val now = System.currentTimeMillis(); val p = body.value?.takeIf { now - it.at < 600 }
                val rep = tracker.feed(p, now)
                reps = tracker.reps; holdMs = tracker.holdMs; cue = tracker.cue; progress = tracker.progress ?: 0f; tracking = tracker.tracking
                if (rep && !exercise.timed) say(if (reps % 10 == 0) "$reps. Nice." else "$reps")
                if (exercise.timed) { val s = (holdMs / 1000).toInt(); if (s != lastSec && s % 10 == 0 && s > 0) say("$s seconds"); lastSec = s }
                delay(40)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.runtime.key(front) { BodyCamera(front, CLEAR, body, null) }
        // Your joints, so you can see the phone has you: green while it is counting, white while you're getting in position.
        Canvas(Modifier.fillMaxSize()) {
            val p = pose?.takeIf { System.currentTimeMillis() - it.at < 600 } ?: return@Canvas
            fun pt(i: Int) = p.at(i, front)?.let { Offset(it.first * size.width, it.second * size.height) }
            val c = if (phase == Phase.RUNNING && tracking) Color(0xFF3DDC97) else Color.White.copy(alpha = 0.75f)
            BodyPose.BONES.forEach { (a, b) -> val pa = pt(a); val pb = pt(b); if (pa != null && pb != null) drawLine(c, pa, pb, strokeWidth = 7f, cap = StrokeCap.Round) }
            (BodyPose.L_SHOULDER..BodyPose.R_ANKLE).forEach { i -> pt(i)?.let { drawCircle(c, 8f, it) } }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ if (phase == Phase.RUNNING) finish() else onExit() }) { Text(if (phase == Phase.RUNNING) "Finish" else "‹ Back", color = Color.White) }
            Spacer(Modifier.weight(1f))
            TextButton({ voiceOn = !voiceOn }) { Text(if (voiceOn) "🔊" else "🔇", fontSize = 20.sp) }
            TextButton({ front = !front }, enabled = phase != Phase.RUNNING) { Text("🔄", fontSize = 20.sp) }
        }
        when (phase) {
            Phase.SETUP -> Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Exercise.entries.forEach { e -> FilterChip(exercise == e, { exercise = e }, { Text("${e.emoji} ${e.label}", style = MaterialTheme.typography.labelMedium) }) } }
                Text(exercise.tip, style = MaterialTheme.typography.bodyMedium)
                Text(if (pose != null) "✅ I can see you." else "I can't see you yet: step back until your whole body is in the picture.", style = MaterialTheme.typography.bodySmall, color = if (pose != null) Color(0xFF2E9E6B) else MaterialTheme.colorScheme.error)
                Button({ phase = Phase.COUNTDOWN }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(27.dp)) { Text("Start set") }
            }
            Phase.COUNTDOWN -> Text("$count", Modifier.align(Alignment.Center), color = Color.White, fontSize = 140.sp, fontWeight = FontWeight.Bold)
            Phase.RUNNING -> {
                Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (exercise.timed) WorkoutLog.mmss((holdMs / 1000).toInt()) else "$reps", color = Color.White, fontSize = 110.sp, fontWeight = FontWeight.Bold)
                    Text("${exercise.emoji} ${exercise.label}", color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
                    val advice = cue ?: if (!tracking) "Get in position" else null
                    advice?.let { Text(it, Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).background(Color(0xCCFF9800)).padding(horizontal = 16.dp, vertical = 8.dp), color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
                }
                // How far through the movement you are: fills as you go down, empties as you come up.
                Box(Modifier.align(Alignment.CenterStart).padding(start = 10.dp).width(10.dp).fillMaxHeight(0.4f).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.25f))) { Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(progress.coerceIn(0.03f, 1f)).background(Color(0xFF3DDC97))) }
                Button({ finish() }, Modifier.align(Alignment.BottomCenter).padding(24.dp).fillMaxWidth().height(54.dp), shape = RoundedCornerShape(27.dp)) { Text("Finish set") }
            }
            Phase.DONE -> Column(Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val s = saved
                if (s == null) { Text("No reps counted", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Check the phone can see your whole body, side-on for push-ups and sit-ups, and try again.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
                else {
                    Text(if (exercise.timed) WorkoutLog.mmss(s.seconds) else "${s.reps} ${exercise.label.lowercase()}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    s.quality?.let { q -> Text("Average depth ${(q * 100).toInt()}%" + when { q >= 0.9f -> " · full range"; q >= 0.8f -> " · good"; else -> " · go deeper next time" }, style = MaterialTheme.typography.bodyMedium) }
                    val prev = WorkoutLog.best(store.all().filter { it.id != s.id }, exercise, s.at); val v = if (exercise.timed) s.seconds else s.reps
                    if (prev > 0 && v > prev) Text("🏅 New best (was $prev)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Saved to your Health screen.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilledTonalButton({ phase = Phase.SETUP }) { Text("Another set") }; Button(onExit) { Text("Done") } }
            }
        }
    }
}
