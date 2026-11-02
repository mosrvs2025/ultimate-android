package app.ultimate.focus

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.ultimate.UltimateApp
import app.ultimate.core.AppIcon
import app.ultimate.core.BlobFace
import app.ultimate.core.GooeyTabs
import app.ultimate.core.LibraryItem
import app.ultimate.core.Mood
import app.ultimate.core.ShellActions
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.pressScale
import app.ultimate.core.rememberNotificationConsent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** A focus companion: say what you are working on, and it keeps a gentle eye on whether you stay with it. */
class FocusTool(private val app: UltimateApp) : Tool {
    override val id = "focus"
    override val title = "Focus"
    override val tagline = "A little companion that keeps you on task"
    override val icon = Icons.Rounded.CenterFocusStrong
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Start a focus session").asStateFlow()
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = FocusScreen(app.focus, onExit)
}

private val OnTaskColor = Color(0xFF3FA66B); private val DriftColor = Color(0xFFE5704B)

@Composable
private fun verdictColor(v: Verdict?): Color = when (v) { Verdict.OnTask -> OnTaskColor; Verdict.Drift -> DriftColor; Verdict.Unsure -> MaterialTheme.colorScheme.outlineVariant; null -> MaterialTheme.colorScheme.surfaceVariant }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FocusScreen(store: FocusStore, onExit: () -> Unit) {
    val current by store.current.collectAsState()
    val history by store.history.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(current?.id, current?.paused) { while (current != null) { now = System.currentTimeMillis(); delay(1000) } }
    // Where the finger is, so the companion can look at it. Watching only: taps still reach the buttons.
    var finger by remember { mutableStateOf<Offset?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    Scaffold(topBar = { TopAppBar(title = { Text("Focus") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() }) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().onGloballyPositioned { origin = it.boundsInRoot().topLeft }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                finger = origin + down.position
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val p = e.changes.firstOrNull() ?: break
                    finger = origin + p.position
                    if (!p.pressed) break
                }
            }
        }) {
            LaunchedEffect(finger) { if (finger != null) { delay(2500); finger = null } }
            AnimatedContent(current?.id, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() }, label = "focus") { id ->
                val s = current
                if (id != null && s != null) Running(store, s, now, finger) else Start(store, history, finger)
            }
        }
    }
}

/** A speech bubble with a little tail, the way the companion talks. */
@Composable
private fun Bubble(text: String, modifier: Modifier = Modifier) {
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, Modifier.clip(RoundedCornerShape(20.dp)).background(fill).padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
        Canvas(Modifier.size(18.dp, 9.dp)) { drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }, fill) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Start(store: FocusStore, history: List<Session>, finger: Offset?) {
    val context = LocalContext.current
    var task by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf(store.lastMinutes.toFloat()) }
    var allowed by remember { mutableStateOf(store.lastAllowed) }
    var picking by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(app.ultimate.footprint.FootprintReaders.hasUsageAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { usage = app.ultimate.footprint.FootprintReaders.hasUsageAccess(context) }
    val consent = rememberNotificationConsent()
    val presets = listOf(15, 25, 30, 45, 60, 90)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Bubble(if (task.isBlank()) "What are we working on?" else "“${task.trim()}” for ${minutes.toInt()} minutes. Ready when you are.")
                BlobFace(if (task.isBlank()) Mood.Calm else Mood.Happy, finger, size = 170.dp)
            }
        }
        item {
            OutlinedTextField(task, { task = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(24.dp), label = { Text("The task") }, placeholder = { Text("Write the intro to my essay") })
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("For ${minutes.toInt()} minutes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                GooeyTabs(presets.map { "$it" }, presets.indexOf(minutes.toInt()).let { if (it < 0) -1 else it }, { minutes = presets[it].toFloat() })
                Slider(minutes, { minutes = it.coerceIn(1f, 240f) }, valueRange = 1f..240f)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Apps that count as working", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Everything else is judged by what kind of app it is: games, social, video and news count as drifting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    allowed.forEach { pkg -> InputChip(true, { allowed = allowed - pkg }, { Text(app.ultimate.footprint.FootprintReaders.label(context, pkg)) }, leadingIcon = { AppIcon(pkg, size = 18.dp) }) }
                    OutlinedButton({ picking = true }) { Text(if (allowed.isEmpty()) "Pick apps" else "Change") }
                }
            }
        }
        if (!usage) item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Let it notice when you drift", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text("With usage access it can see which app is in front, every 30 seconds, on this phone only. Without it, Focus is just a timer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                TextButton({ context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Allow usage access") }
            }
        }
        item { Button({ consent { store.start(task, minutes.toInt(), allowed) } }, Modifier.fillMaxWidth().height(52.dp), enabled = task.isNotBlank()) { Text("Start focusing", fontSize = 16.sp) } }
        if (history.isNotEmpty()) {
            item { Text("Earlier sessions", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(history.take(20), key = { it.id }) { s -> Past(s) { store.forget(s.id) } }
        }
    }
    if (picking) AppPicker(allowed, { allowed = it }) { picking = false }
}

/** The session itself, laid out like Bluey's: the task top left, the companion in the middle, the countdown big at the bottom right. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Running(store: FocusStore, s: Session, now: Long, finger: Offset?) {
    val context = LocalContext.current
    var checks by remember { mutableStateOf(false) }
    val last = s.checks.lastOrNull()
    val recentNudge = s.nudges.lastOrNull()?.takeIf { now - it < 120_000 }
    val mood = when { s.paused -> Mood.Sleepy; recentNudge != null || last?.verdict == Verdict.Drift -> Mood.Worried; last?.verdict == Verdict.OnTask -> Mood.Happy; else -> Mood.Calm }
    fun label(pkg: String) = app.ultimate.footprint.FootprintReaders.label(context, pkg)
    val said = when {
        s.paused -> "Paused. I'll wait."
        recentNudge != null && last != null -> FocusMath.nudge(s.task, label(last.pkg), s.nudges.size - 1)
        last == null -> "Off you go. I'll keep an eye out."
        last.verdict == Verdict.OnTask -> "Looking good."
        last.verdict == Verdict.Drift -> "Is ${label(last.pkg)} part of this?"
        else -> "Mm-hm."
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Working on", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(s.task.ifBlank { "your task" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalIconButton({ if (s.paused) store.resume() else store.pause() }) { Icon(if (s.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (s.paused) "Resume" else "Pause") }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton({ store.end() }) { Icon(Icons.Rounded.Stop, "End session") }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(said, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith fadeOut() }, label = "said") { Bubble(it) }
            BlobFace(mood, finger, size = 200.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Timeline(s, now, Modifier.fillMaxWidth().height(22.dp).pressScale(0.98f).clip(RoundedCornerShape(11.dp)).clickable { checks = true })
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    FocusMath.onTaskShare(s)?.let { Text("On task ${Math.round(it * 100)}%", fontWeight = FontWeight.SemiBold, color = OnTaskColor) }
                    Text("${s.checks.size} checks · ${s.nudges.size} nudge${if (s.nudges.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(FocusMath.countdown(s.left(now)), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (checks) ModalBottomSheet(onDismissRequest = { checks = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Every check so far", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Which app was in front, every 30 seconds. Nothing else is looked at.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.height(380.dp)) {
                items(s.checks.reversed(), key = { it.at }) { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(verdictColor(c.verdict))); Spacer(Modifier.width(10.dp))
                        Text(SimpleDateFormat("h:mm:ss", Locale.getDefault()).format(c.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(10.dp))
                        AppIcon(c.pkg, size = 20.dp); Spacer(Modifier.width(8.dp))
                        Text(label(c.pkg), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when (c.verdict) { Verdict.OnTask -> "on task"; Verdict.Drift -> "drifting"; Verdict.Unsure -> "not sure" }, style = MaterialTheme.typography.labelMedium, color = verdictColor(c.verdict))
                    }
                }
            }
        }
    }
}

/** The session as one bar: green on task, orange drifting, grey not sure or not checked. */
@Composable
private fun Timeline(s: Session, now: Long, modifier: Modifier) {
    val segs = FocusMath.timeline(s, now)
    val colors = segs.map { verdictColor(it.verdict) }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        val total = s.plannedMs.coerceAtLeast(1)
        segs.forEachIndexed { i, seg ->
            val x0 = size.width * ((seg.start - s.startedAt).toFloat() / total).coerceIn(0f, 1f)
            val x1 = size.width * ((seg.end - s.startedAt).toFloat() / total).coerceIn(0f, 1f)
            if (x1 > x0) drawRect(colors[i], Offset(x0, 0f), Size(x1 - x0, size.height))
        }
    }
}

@Composable
private fun Past(s: Session, onForget: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable { open = !open }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.task.ifBlank { "A session" }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FocusMath.onTaskShare(s)?.let { Text("${Math.round(it * 100)}% on task", style = MaterialTheme.typography.labelLarge, color = OnTaskColor) }
        }
        Text(SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(s.startedAt) + " · " + FocusMath.countdown(s.elapsed(s.endedAt)) + " of " + (s.plannedMs / 60_000) + " min" + if (s.nudges.isNotEmpty()) " · ${s.nudges.size} nudge${if (s.nudges.size == 1) "" else "s"}" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Timeline(s, s.endedAt, Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)))
        if (open) TextButton(onForget) { Text("Forget this session", color = MaterialTheme.colorScheme.error) }
    }
}

/** Pick the apps that count as working for this kind of task. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPicker(chosen: Set<String>, onChange: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }
                .distinct().filter { it != context.packageName }.map { it to app.ultimate.footprint.FootprintReaders.label(context, it) }.sortedBy { it.second.lowercase() }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Apps that count as working", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
            LazyColumn(Modifier.height(420.dp)) {
                items(apps, key = { it.first }) { (pkg, name) ->
                    val on = pkg in chosen
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(if (on) chosen - pkg else chosen + pkg) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(pkg, size = 32.dp); Spacer(Modifier.width(12.dp)); Text(name, Modifier.weight(1f)); Checkbox(on, { onChange(if (on) chosen - pkg else chosen + pkg) })
                    }
                }
            }
        }
    }
}
