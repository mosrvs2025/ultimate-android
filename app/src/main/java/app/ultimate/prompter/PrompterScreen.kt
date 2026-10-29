package app.ultimate.prompter

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.RecentResults
import app.ultimate.core.ShellActions
import app.ultimate.core.ToolBackButton
import app.ultimate.core.formatDuration
import app.ultimate.core.pressScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrompterScreen(tool: PrompterTool, onExit: () -> Unit) {
    val store = tool.store
    var shooting by remember { mutableStateOf(false) }
    var script by remember { mutableStateOf(store.script) }
    var wpm by remember { mutableIntStateOf(store.wpm) }
    var textSize by remember { mutableIntStateOf(store.textSize) }
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> if (r[Manifest.permission.CAMERA] == true) shooting = true }
    BackHandler { if (shooting) shooting = false else onExit() }

    AnimatedContent(shooting, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "prompter") { shoot ->
        if (shoot) Shoot(tool, script, wpm, textSize, onClose = { shooting = false })
        else Scaffold(topBar = { TopAppBar(title = { Text("Teleprompter") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() }) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    script, { script = it; store.script = it }, Modifier.fillMaxWidth().height(220.dp),
                    label = { Text("Your script") }, placeholder = { Text("Paste or write what you want to say. It scrolls by next to the camera while you record.") },
                )
                Text(
                    "${PrompterMath.words(script)} words · about ${formatDuration(PrompterMath.readingMs(script, wpm))} at $wpm words a minute",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Pace · $wpm words a minute", style = MaterialTheme.typography.labelLarge)
                Slider(wpm.toFloat(), { wpm = it.roundToInt(); store.wpm = wpm }, valueRange = 80f..220f)
                Text("Text size · $textSize", style = MaterialTheme.typography.labelLarge)
                Slider(textSize.toFloat(), { textSize = it.roundToInt(); store.textSize = textSize }, valueRange = 20f..48f)
                Button(
                    {
                        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                        if (need.isEmpty()) shooting = true else ask.launch(need.toTypedArray())
                    },
                    Modifier.fillMaxWidth().height(56.dp), enabled = script.isNotBlank(), shape = RoundedCornerShape(18.dp),
                ) { Text("Start filming", style = MaterialTheme.typography.titleMedium) }
                RecentResults(tool, "Takes", onOpenOwn = {}, limit = 6)
                Text("Tap a take to send it to Studio for cutting, captions and music.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** Camera full screen, script scrolling over the top near the lens, a 3-2-1 count before each take. */
// RECORD_AUDIO is declared only by the editions that ship this tool, and audio is enabled only after a runtime check.
@android.annotation.SuppressLint("MissingPermission")
@Composable
private fun Shoot(tool: PrompterTool, script: String, wpm: Int, textSize: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var front by remember { mutableStateOf(true) }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val capture = remember { VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var countdown by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val total = PrompterMath.readingMs(script, wpm)
    var rolling by remember { mutableStateOf(false) }

    DisposableEffect(front) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val provider = future.get()
                provider.unbindAll()
                val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                provider.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, p, capture)
            }.onFailure { error = "Camera unavailable: ${it.message}" }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    DisposableEffect(Unit) { onDispose { recording?.stop() } }
    // Scroll in step with the pace, resuming from wherever it is after a pause.
    LaunchedEffect(rolling) {
        if (rolling) scroll.animateScrollTo(scroll.maxValue, tween(PrompterMath.remainingMs(scroll.value, scroll.maxValue, total).toInt().coerceAtLeast(1), easing = LinearEasing))
    }
    LaunchedEffect(recording) { val start = System.currentTimeMillis(); while (recording != null) { elapsed = System.currentTimeMillis() - start; delay(250) } }

    fun startTake() = scope.launch {
        for (n in 3 downTo 1) { countdown = n; delay(800) }
        countdown = 0
        scroll.scrollTo(0)
        val name = "Take " + java.text.SimpleDateFormat("MMM d HH.mm.ss", java.util.Locale.US).format(java.util.Date())
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/Ultimate Prompter")
        }
        val out = MediaStoreOutputOptions.Builder(context.contentResolver, android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        val pending = capture.output.prepareRecording(context, out).let {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) it.withAudioEnabled() else it
        }
        recording = pending.start(ContextCompat.getMainExecutor(context)) { e ->
            if (e is VideoRecordEvent.Finalize) {
                rolling = false
                if (e.hasError() && e.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) error = "The take couldn't be saved (${e.error})"
                else scope.launch {
                    tool.store.add(Take(UUID.randomUUID().toString(), name, e.outputResults.outputUri.toString(), System.currentTimeMillis(), e.recordingStats.recordedDurationNanos / 1_000_000, e.recordingStats.numBytesRecorded))
                }
            }
        }
        rolling = true
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ preview }, Modifier.fillMaxSize())
        // The script sits at the top, close to the front camera, so your eyes stay near the lens.
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(0.42f).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                .clickable { if (recording != null) rolling = !rolling },
        ) {
            Text(
                script + "\n\n\n", Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp).verticalScroll(scroll, enabled = !rolling),
                color = Color.White, fontSize = textSize.sp, lineHeight = (textSize * 1.35f).sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
        }
        Row(Modifier.statusBarsPadding().padding(8.dp)) {
            IconButton({ recording?.stop(); onClose() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
        }
        AnimatedVisibility(countdown > 0, Modifier.align(Alignment.Center), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            AnimatedContent(countdown, transitionSpec = { (scaleIn(initialScale = 1.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) }, label = "count") { n ->
                Text("$n", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Black)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            error?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(bottom = 8.dp)) }
            if (recording != null) Text(
                formatDuration(elapsed) + if (!rolling) " · script paused, tap it to go on" else "",
                color = Color.White, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xCCE53935)).padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                IconButton({ onClose() }, enabled = recording == null) { Icon(Icons.Rounded.Edit, "Edit script", tint = Color.White) }
                val corner by animateDpAsState(if (recording != null) 10.dp else 32.dp, spring(Spring.DampingRatioMediumBouncy), label = "rec")
                val inner by animateDpAsState(if (recording != null) 30.dp else 60.dp, spring(Spring.DampingRatioMediumBouncy), label = "recSize")
                Box(
                    Modifier.size(76.dp).pressScale(0.9f).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))
                        .clickable(enabled = countdown == 0) { val r = recording; if (r != null) { r.stop(); recording = null } else startTake() },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(Color(0xFFE53935))) }
                IconButton({ front = !front }, enabled = recording == null) { Icon(Icons.Rounded.Cameraswitch, "Switch camera", tint = Color.White) }
            }
        }
    }
}
