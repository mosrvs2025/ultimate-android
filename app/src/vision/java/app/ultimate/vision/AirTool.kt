package app.ultimate.vision

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioManager
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BackHand
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.air.AirActions
import app.ultimate.air.AirGestures
import app.ultimate.air.FaceExpressions
import app.ultimate.air.HandPose
import app.ultimate.core.LibraryItem
import app.ultimate.core.Signal
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.echo.Truth
import app.ultimate.echo.TruthTag
import app.ultimate.home.QuickActions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/**
 * Air control: the camera watches your hand from across the room (about 2-3 m) and turns gestures into actions: swipe
 * to skip a track, thumbs up for louder, an open palm to pause, a pinch for the flashlight, and anything else through
 * Rules, since every gesture is also a signal. It can also watch your face: eyebrows up or down, smile, mouth open,
 * pucker, puffed cheeks, wink and blink. On the phone only; frames are looked at and dropped.
 *
 * It always opens watching only: you see the tracking and what each gesture would do, and nothing happens on the phone
 * until you turn on "Gestures do things" for that visit.
 */
class AirTool(private val app: UltimateApp) : Tool {
    override val id = "air"
    override val title = "Air control"
    override val tagline = "Control your phone with hand gestures, from across the room"
    override val icon = Icons.Rounded.BackHand
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Gestures, through the camera")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = false
    override suspend fun refresh() = Unit

    private val pose = MutableStateFlow<HandPose?>(null)
    private val gesture = MutableStateFlow<Pair<AirGestures.Gesture, Long>?>(null)
    private val face = MutableStateFlow<FacePose?>(null)

    /** A face as the landmarker saw it: its points (0..1 of the picture) and its blendshape scores. */
    data class FacePose(val points: List<Pair<Float, Float>>, val scores: Map<String, Float>, val at: Long)

    private fun actionsPrefs(c: Context) = c.getSharedPreferences("air_actions", Context.MODE_PRIVATE)
    private fun action(c: Context, kind: String) = actionsPrefs(c).getString(kind, AirActions.DEFAULTS[kind] ?: "none") ?: "none"

    /** Carries out what [kind] is set to do. Media keys reach whatever is playing, in any app. */
    private fun perform(c: Context, kind: String) {
        val am = c.getSystemService(AudioManager::class.java)
        fun key(code: Int) { am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) }
        when (action(c, kind)) {
            "next" -> key(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "playpause" -> key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "volume_up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "torch" -> QuickActions.toggleTorch(c)
            "home" -> app.shell.exitTool()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        BackHandler(onBack = onExit)
        val context = LocalContext.current
        val haptics = LocalHapticFeedback.current
        var allowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
        var front by remember { mutableStateOf(true) }
        val hand by pose.collectAsStateWithLifecycle()
        val last by gesture.collectAsStateWithLifecycle()
        val chosen = remember { mutableStateMapOf<String, String>().apply { (AirGestures.ALL + FaceExpressions.ALL).forEach { (k, _) -> put(k, action(context, k)) } } }
        val trail = remember { ArrayDeque<Pair<Float, Float>>() }
        val detector = remember(front) { AirGestures(mirrored = front) }
        // Off on every visit: watching shows what would happen, and only this switch lets gestures act.
        var acting by remember { mutableStateOf(false) }
        var faceMode by remember { mutableStateOf(false) }
        val seenFace by face.collectAsStateWithLifecycle()
        val expressions = remember { FaceExpressions() }
        var levels by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
        fun fire(kind: String, label: String, now: Long) {
            gesture.value = AirGestures.Gesture(kind, label) to now
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (!acting) return
            app.senses.publish(Signal("air", kind, "You gestured: $label"))
            perform(context, kind)
        }
        // Keep the screen on while it watches, and show it under Live now with Stop.
        val view = LocalView.current
        DisposableEffect(allowed) {
            if (allowed) { view.keepScreenOn = true; app.senses.begin("air", "air", "Air control is watching your hand", sensitive = true) { onExit() } }
            onDispose { view.keepScreenOn = false; app.senses.end("air") }
        }
        LaunchedEffect(hand) {
            val now = System.currentTimeMillis()
            hand?.let { trail.addLast(it.palm); while (trail.size > 24) trail.removeFirst() } ?: run { if (trail.isNotEmpty()) trail.removeFirst() }
            detector.feed(hand, now)?.let { g -> fire(g.kind, g.label, now) }
        }
        LaunchedEffect(seenFace) {
            val f = seenFace ?: return@LaunchedEffect
            val reading = expressions.feed(f.scores, f.at)
            levels = reading.levels
            reading.fired?.let { fire("face_${it.kind}", it.label, f.at) }
        }

        Scaffold(topBar = { TopAppBar(title = { Text("Air control") }, navigationIcon = { IconButton(onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!allowed) {
                    Text("Prop the phone up facing you. The camera watches your hand and turns gestures into actions, from up to about 2-3 metres. Everything runs on the phone; frames are looked at and dropped, never saved.", style = MaterialTheme.typography.bodyLarge)
                    Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow the camera") }
                    return@Column
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(if (acting) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { acting = !acting }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Gestures do things", fontWeight = FontWeight.SemiBold)
                        Text(if (acting) "On: gestures control the phone and trigger Rules until you leave." else "Off: watching only. You'll see what each gesture would do; nothing happens.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(acting, { acting = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!faceMode, { faceMode = false; face.value = null }, { Text("Hand") })
                    FilterChip(faceMode, { faceMode = true; pose.value = null; expressions.reset() }, { Text("Face") })
                    Box(Modifier.weight(1f))
                    FilterChip(front, { front = true }, { Text("Front") })
                    FilterChip(!front, { front = false }, { Text("Back") })
                }
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
                    key(front, faceMode) { if (faceMode) FaceCamera(front, face) else GestureCamera(front, pose) }
                    val now = System.currentTimeMillis()
                    if (faceMode) seenFace?.takeIf { now - it.at < 400 }?.let { f ->
                        Canvas(Modifier.fillMaxSize()) {
                            val c = Color(0xFF7FD3FF).copy(alpha = 0.75f)
                            f.points.forEach { p -> drawCircle(c, 1.2.dp.toPx(), Offset((if (front) 1 - p.first else p.first) * size.width, p.second * size.height)) }
                        }
                    }
                    if (!faceMode) hand?.takeIf { now - it.at < 400 }?.let { h ->
                        Canvas(Modifier.fillMaxSize()) {
                            fun o(p: Pair<Float, Float>) = Offset((if (front) 1 - p.first else p.first) * size.width, p.second * size.height)
                            // The palm's trail, fading, then the hand's bones and joints, then the air pointer on the index tip.
                            trail.forEachIndexed { i, p -> drawCircle(Color(0xFF7FD3FF).copy(alpha = i / 24f), (3 + i * 0.4f).dp.toPx(), o(p)) }
                            BONES.forEach { (a, b) -> drawLine(Color.White.copy(alpha = 0.8f), o(h.points[a]), o(h.points[b]), strokeWidth = 3.dp.toPx()) }
                            h.points.forEach { drawCircle(Color(0xFF3DDC97), 4.dp.toPx(), o(it)) }
                            drawCircle(Color(0xFFFF8A5C), 12.dp.toPx(), o(h.points[8]))
                        }
                    }
                    TruthTag(Truth.Estimate, Modifier.align(Alignment.TopStart).padding(10.dp))
                    val pop = last?.takeIf { now - it.second < 1_200 }
                    val scale by animateFloatAsState(if (pop != null) 1f else 0.6f, spring(0.45f, 300f), label = "pop")
                    pop?.let { (g, _) ->
                        Column(Modifier.align(Alignment.Center).graphicsLayer { scaleX = scale; scaleY = scale }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(g.label, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
                            AirActions.CHOICES.firstOrNull { it.first == chosen[g.kind] }?.second?.takeIf { chosen[g.kind] != "none" }?.let {
                                Text(if (acting) "→ $it" else "would: $it (watching only)", color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp)
                            }
                        }
                    }
                    Text(if (faceMode) (if (seenFace == null) "Look at the camera" else "Tracking your face") else if (hand == null) "Show the camera your hand" else hand?.sign?.takeIf { it != "None" }?.let { AirGestures.signLabel(it) } ?: "Tracking your hand",
                        Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White)
                }
                if (faceMode) {
                    // Live levels against your own resting face, so you can see what the camera reads as you move.
                    Text("Your expressions, live", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FaceExpressions.LABELS.forEach { (kind, label) ->
                        val level = levels[kind] ?: 0f
                        val on = FaceExpressions.THRESHOLDS.getValue(kind).first
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.fillMaxWidth(0.34f), style = MaterialTheme.typography.labelLarge, fontWeight = if (level >= on) FontWeight.Bold else null)
                            LinearProgressIndicator({ level }, Modifier.weight(1f).padding(start = 8.dp),
                                color = if (level >= on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        }
                    }
                    Text("Each bar is measured against your face at rest, which it learns in the first seconds; it counts once a bar passes about halfway.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!faceMode) app.tools.firstOrNull { it.id == "echo-teach" }?.let { teach ->
                    androidx.compose.material3.OutlinedButton({ app.shell.open(teach) }, Modifier.fillMaxWidth()) { Text("Teach Echo these gestures, to use them with the camera off") }
                }
                Text("What each gesture does (tap to change)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (if (faceMode) FaceExpressions.ALL else AirGestures.ALL).forEach { (kind, label) ->
                        val act = chosen[kind] ?: "none"
                        val name = AirActions.CHOICES.firstOrNull { it.first == act }?.second ?: "Nothing"
                        Text("$label → $name", Modifier.clip(RoundedCornerShape(12.dp)).background(if (act == "none") MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer)
                            .clickable {
                                val next = AirActions.CHOICES[(AirActions.CHOICES.indexOfFirst { it.first == act } + 1) % AirActions.CHOICES.size].first
                                chosen[kind] = next; actionsPrefs(context).edit().putString(kind, next).apply()
                            }.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
                Text("With \"Gestures do things\" on, every gesture is also a signal for Rules, like \"When I give a thumbs up, …\". Works best with good light and your whole hand in view, up to about 2-3 m. The screen stays on while Air control watches; it stops when you leave.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    companion object {
        /** The hand's bones, as pairs of MediaPipe landmark numbers. */
        val BONES = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11, 11 to 12, 9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20)
    }
}

/** A camera analysed by MediaPipe's gesture recognizer on its own thread; hand poses go to [out]. */
@Composable
internal fun GestureCamera(front: Boolean, out: MutableStateFlow<HandPose?>) {
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    val recognizer = remember {
        runCatching {
            GestureRecognizer.createFromOptions(context, GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                .setRunningMode(RunningMode.LIVE_STREAM).setNumHands(1)
                .setResultListener { result, _ ->
                    val lm = result.landmarks().firstOrNull()
                    if (lm == null || lm.size < 21) { out.value = null; return@setResultListener }
                    val top = result.gestures().firstOrNull()?.firstOrNull()
                    out.value = HandPose(lm.map { it.x() to it.y() }, top?.categoryName(), top?.score() ?: 0f, System.currentTimeMillis())
                }
                .setErrorListener { e -> out.value = null; problem = e.message }.build())
        }.onFailure { e ->
            // Show the cause underneath wrappers like ExceptionInInitializerError, so a report says what really failed.
            val root = generateSequence(e) { it.cause }.last()
            problem = "${root.javaClass.simpleName}: ${root.message ?: e.message ?: ""}".trim().trimEnd(':')
        }.getOrNull()
    }
    DisposableEffect(Unit) { onDispose { runCatching { recognizer?.close() }; out.value = null } }
    AnalysedCamera(front) { bmp, t -> recognizer?.recognizeAsync(BitmapImageBuilder(bmp).build(), t) }
    // The camera shows even when the gesture model can't start, and the reason is on screen, so it can be reported.
    problem?.let { Text("Gesture tracking couldn't start: $it", Modifier.padding(16.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(10.dp), color = Color(0xFFFFC857)) }
}

/** A camera analysed by MediaPipe's face landmarker (with blendshapes) on its own thread; faces go to [out]. */
@Composable
private fun FaceCamera(front: Boolean, out: MutableStateFlow<AirTool.FacePose?>) {
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    val landmarker = remember {
        runCatching {
            FaceLandmarker.createFromOptions(context, FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                .setRunningMode(RunningMode.LIVE_STREAM).setNumFaces(1).setOutputFaceBlendshapes(true)
                .setResultListener { result, _ ->
                    val lm = result.faceLandmarks().firstOrNull()
                    if (lm == null) { out.value = null; return@setResultListener }
                    val scores = result.faceBlendshapes().orElse(null)?.firstOrNull()?.associate { it.categoryName() to it.score() }.orEmpty()
                    out.value = AirTool.FacePose(lm.map { it.x() to it.y() }, scores, System.currentTimeMillis())
                }
                .setErrorListener { e -> out.value = null; problem = e.message }.build())
        }.onFailure { e ->
            val root = generateSequence(e) { it.cause }.last()
            problem = "${root.javaClass.simpleName}: ${root.message ?: e.message ?: ""}".trim().trimEnd(':')
        }.getOrNull()
    }
    DisposableEffect(Unit) { onDispose { runCatching { landmarker?.close() }; out.value = null } }
    AnalysedCamera(front) { bmp, t -> landmarker?.detectAsync(BitmapImageBuilder(bmp).build(), t) }
    problem?.let { Text("Face tracking couldn't start: $it", Modifier.padding(16.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(10.dp), color = Color(0xFFFFC857)) }
}

/** A camera preview whose frames, upright, go to [analyse] on a background thread; the newest frame wins. */
@Composable
private fun AnalysedCamera(front: Boolean, analyse: (Bitmap, Long) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView({ ctx ->
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                analysis.setAnalyzer(executor) { proxy ->
                    runCatching {
                        val rot = proxy.imageInfo.rotationDegrees
                        val bmp = proxy.toBitmap().let { b -> if (rot == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true) }
                        analyse(bmp, System.currentTimeMillis())
                    }
                    proxy.close()
                }
                runCatching { provider.unbindAll(); provider.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
            }, ContextCompat.getMainExecutor(ctx))
        }
    }, Modifier.fillMaxSize())
}
