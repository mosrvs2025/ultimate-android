package app.ultimate.vision

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraFront
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.echo.CameraRange
import app.ultimate.echo.EchoCalibration
import app.ultimate.echo.EchoTool
import app.ultimate.echo.SonarState
import app.ultimate.echo.Truth
import app.ultimate.echo.TruthTag
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/** One frame of what the camera saw: hand points (0-1, in the rotated image) and its distance estimate. */
data class HandFrame(val points: List<Pair<Float, Float>>, val distanceCm: Double, val flat: Boolean, val at: Long)

/**
 * Echo camera calibration: the front camera finds your hand (MediaPipe Hand Landmarker, on the phone), estimates its
 * distance from the size of your palm, and Echo measures it by sound at the same time. Twenty seconds of both, moved
 * up and down, gives Echo a correction fitted to hundreds of matched readings. Nothing is recorded: frames are looked
 * at and dropped.
 */
class HandCalibrationTool(private val app: UltimateApp) : Tool {
    override val id = "echo-camera"
    override val title = "Echo camera calibration"
    override val tagline = "Let the camera teach Echo how far your hand really is"
    override val icon = Icons.Rounded.CameraFront
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Camera + sound, together")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = false
    override suspend fun refresh() = Unit

    private val hand = MutableStateFlow<HandFrame?>(null)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        BackHandler(onBack = onExit)
        val context = LocalContext.current
        val echo = remember { app.tools.firstOrNull { it.id == "echo" } as? EchoTool }
        var allowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> allowed = r.values.all { it } }
        val prefs = remember { context.getSharedPreferences("echo_camera", Context.MODE_PRIVATE) }
        var palmCm by remember { mutableFloatStateOf(prefs.getFloat("palm_cm", CameraRange.DEFAULT_PALM_CM.toFloat())) }
        val frame by hand.collectAsStateWithLifecycle()
        val sonarState by (echo?.sonar?.state ?: MutableStateFlow(SonarState.Off)).collectAsStateWithLifecycle()
        val pairs = remember { mutableListOf<Pair<Double, Double>>() }
        var recording by remember { mutableStateOf(false) }
        var secondsLeft by remember { mutableIntStateOf(0) }
        var fit by remember { mutableStateOf<EchoCalibration?>(null) }
        var tried by remember { mutableStateOf(false) }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

        DisposableEffect(Unit) { onDispose { echo?.sonar?.stop() } }
        // While a session runs: every time the camera and Echo both see the hand (palm flat), keep the pair.
        LaunchedEffect(frame, sonarState, recording) {
            val f = frame ?: return@LaunchedEffect
            val raw = (sonarState as? SonarState.Running)?.frame?.rawRangeM ?: return@LaunchedEffect
            if (recording && f.flat && !f.distanceCm.isNaN() && !raw.isNaN() && System.currentTimeMillis() - f.at < 200) {
                pairs += (raw * 100) to f.distanceCm
                // A tick every few matched readings, so you can feel it working without watching the number.
                if (pairs.size % 5 == 1) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
            }
        }
        LaunchedEffect(recording) {
            if (!recording) return@LaunchedEffect
            for (s in 20 downTo 1) { secondsLeft = s; kotlinx.coroutines.delay(1000) }
            recording = false; tried = true
            val (held, read) = CameraRange.binned(pairs.toList())
            fit = EchoCalibration.fit(held, read)
        }

        Scaffold(topBar = { TopAppBar(title = { Text("Camera calibration") }, navigationIcon = { IconButton(onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (echo == null) { Text("Echo isn't in this edition."); return@Column }
                if (!allowed) {
                    Text("The front camera finds your hand and estimates how far it is from the size of your palm, while Echo measures the same hand by sound. Comparing the two calibrates Echo for your phone. Frames are looked at and dropped; nothing is saved.", style = MaterialTheme.typography.bodyLarge)
                    Button({ ask.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) { Text("Allow the camera and microphone") }
                    return@Column
                }
                LaunchedEffect(Unit) { if (!echo.sonar.running) echo.sonar.start() }
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
                    CameraWithHands(app, palmCm.toDouble(), hand)
                    frame?.takeIf { System.currentTimeMillis() - it.at < 400 }?.let { f ->
                        Canvas(Modifier.fillMaxSize()) {
                            // The front camera is a mirror: flip x so the dots sit on your hand as you see it.
                            f.points.forEach { (x, y) -> drawCircle(if (f.flat) Color(0xFF3DDC97) else Color(0xFFFFC857), 5.dp.toPx(), Offset((1 - x) * size.width, y * size.height)) }
                        }
                    }
                    TruthTag(Truth.Estimate, Modifier.align(Alignment.TopStart).padding(10.dp))
                    // A live coach on the picture: the one thing to change right now, big enough to read at arm's length.
                    val rawNow = (sonarState as? SonarState.Running)?.frame?.rawRangeM ?: Double.NaN
                    val camNow = frame?.takeIf { System.currentTimeMillis() - it.at < 400 }
                    val tip = when {
                        camNow == null -> "Show the camera your open hand"
                        !camNow.flat -> "Turn your palm flat toward the screen"
                        rawNow.isNaN() -> "Echo can't hear your hand yet: bring it lower, over the bottom of the phone (where the speaker is). Tilt the phone back a little so the camera still sees it."
                        else -> if (recording) "✓ Both see your hand. Now move slowly closer and farther" else "✓ Both see your hand. Start the calibration"
                    }
                    Text(tip, Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (tip.startsWith("✓")) Color(0xCC1E7D4F) else Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 8.dp),
                        color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                val raw = (sonarState as? SonarState.Running)?.frame?.rawRangeM ?: Double.NaN
                val cam = frame?.takeIf { System.currentTimeMillis() - it.at < 400 }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Readout("camera", cam?.distanceCm?.takeIf { !it.isNaN() }?.let { "%.0f cm".format(it) } ?: "no hand", if (cam?.flat == false) "tilted: hold flat" else "from palm size", Truth.Estimate)
                    Readout("Echo (raw)", if (raw.isNaN()) "—" else "%.0f cm".format(raw * 100), "by sound", Truth.Measured)
                }
                // The camera works out distance from how big your hand looks, so it needs to know how big your hand really is.
                Text("Your hand size (the camera judges distance from it)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("Small" to 7.4f, "Medium" to 8.2f, "Large" to 9.0f).forEach { (name, cm) ->
                        androidx.compose.material3.FilterChip(kotlin.math.abs(palmCm - cm) < 0.05f, { palmCm = cm; prefs.edit().putFloat("palm_cm", cm).apply() }, { Text(name) })
                    }
                    androidx.compose.material3.TextButton({ palmCm = (palmCm - 0.1f).coerceAtLeast(6f); prefs.edit().putFloat("palm_cm", palmCm).apply() }) { Text("−") }
                    Text("%.1f cm".format(palmCm), style = MaterialTheme.typography.labelLarge)
                    androidx.compose.material3.TextButton({ palmCm = (palmCm + 0.1f).coerceAtMost(10.5f); prefs.edit().putFloat("palm_cm", palmCm).apply() }) { Text("+") }
                }
                Text("Across the knuckles, index to little finger. Most adults are Medium; if the camera's distance looks too far, pick a smaller size.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (recording) {
                    Text("Recording… $secondsLeft s · ${pairs.size} matched readings", style = MaterialTheme.typography.titleMedium)
                    Text("Palm flat, facing the screen, over the bottom half of the phone (Echo's speaker is there). Move it slowly from close to about 40 cm and back.", style = MaterialTheme.typography.bodyMedium)
                    if (pairs.isEmpty() && secondsLeft <= 16) Text("Nothing matched yet: follow the tip on the picture.", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                } else {
                    Button({ pairs.clear(); fit = null; tried = false; recording = true }, Modifier.fillMaxWidth().height(52.dp)) { Text("Start a 20-second calibration") }
                }
                if (pairs.isNotEmpty()) Scatter(pairs.toList(), fit, Modifier.fillMaxWidth().height(220.dp))
                if (tried) {
                    val f = fit
                    if (f == null) Text("Not enough matched readings at different heights to calibrate (${pairs.size} collected). Move your hand over a wider range, slowly, with the palm flat.", color = MaterialTheme.colorScheme.error)
                    else {
                        Text("Fitted from ${pairs.size} matched readings. After correcting, Echo agrees with the camera to about ±%.1f cm.".format(f.rmsErrorCm), style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton({ EchoCalibration.save(context, f); echo.sonar.calibration = f; android.widget.Toast.makeText(context, "Echo calibrated", android.widget.Toast.LENGTH_SHORT).show() }) { Text("Use this calibration") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Readout(label: String, value: String, note: String, truth: Truth) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("$label · $note", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TruthTag(truth, Modifier.padding(top = 2.dp))
    }
}

/** Echo's raw reading against the camera's distance, with the fitted correction once there is one. */
@Composable
private fun Scatter(pairs: List<Pair<Double, Double>>, fit: EchoCalibration?, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val max = 50f
            fun p(echoCm: Double, camCm: Double) = Offset((camCm.toFloat() / max).coerceIn(0f, 1f) * size.width, size.height - (echoCm.toFloat() / max).coerceIn(0f, 1f) * size.height)
            drawLine(ink.copy(alpha = 0.2f), p(0.0, 0.0), p(50.0, 50.0), strokeWidth = 1.dp.toPx())
            pairs.forEach { (e, c) -> drawCircle(Color(0xFF7FD3FF).copy(alpha = 0.6f), 2.5.dp.toPx(), p(e, c)) }
            fit?.let { f -> // read = (held - offset) / gain
                fun read(held: Double) = (held - f.offsetM * 100) / f.gain
                drawLine(Color(0xFFFF8A5C), p(read(5.0), 5.0), p(read(45.0), 45.0), strokeWidth = 3.dp.toPx())
            }
        }
        Text("camera →  ·  Echo ↑  ·  diagonal = perfect agreement", Modifier.align(Alignment.BottomCenter).padding(4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The front camera, analysed frame by frame by MediaPipe on its own thread; results go to [out]. */
@Composable
private fun CameraWithHands(app: UltimateApp, palmCm: Double, out: MutableStateFlow<HandFrame?>) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val palm = remember { mutableFloatStateOf(palmCm.toFloat()) }.also { it.floatValue = palmCm.toFloat() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val focal = remember { frontFocal(context) }
    val landmarker = remember {
        runCatching {
            HandLandmarker.createFromOptions(context, HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setRunningMode(RunningMode.LIVE_STREAM).setNumHands(1)
                .setResultListener { result, image ->
                    val lm = result.landmarks().firstOrNull()
                    if (lm == null || lm.size < 21) { out.value = null; return@setResultListener }
                    val w = image.width.toDouble(); val h = image.height.toDouble()
                    fun px(i: Int) = lm[i].x() * w to lm[i].y() * h
                    val (x5, y5) = px(5); val (x17, y17) = px(17); val (x0, y0) = px(0); val (x9, y9) = px(9)
                    val palmPx = CameraRange.palmPx(x5, y5, x17, y17)
                    val fPx = focal?.let { (mm, sensorLong) -> CameraRange.focalPx(mm, sensorLong, maxOf(image.width, image.height)) }
                    val d = if (fPx == null) Double.NaN else CameraRange.distanceCm(palmPx, fPx, palm.floatValue.toDouble())
                    out.value = HandFrame(lm.map { it.x() to it.y() }, d, CameraRange.isFlat(palmPx, kotlin.math.hypot(x0 - x9, y0 - y9) * 0.8), System.currentTimeMillis())
                }
                .setErrorListener { out.value = null }.build())
        }.onFailure { android.util.Log.w("EchoCamera", "hand landmarker failed to start", it) }.getOrNull()
    }
    DisposableEffect(Unit) { onDispose { runCatching { landmarker?.close() }; executor.shutdown(); out.value = null } }
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
                        landmarker?.detectAsync(BitmapImageBuilder(bmp).build(), System.currentTimeMillis())
                    }
                    proxy.close()
                }
                runCatching { provider.unbindAll(); provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis) }
            }, ContextCompat.getMainExecutor(ctx))
        }
    }, Modifier.fillMaxSize())
}

/** The front camera's focal length and its sensor's long side, in mm, as the phone reports them. */
private fun frontFocal(context: Context): Pair<Float, Float>? = runCatching {
    val cm = context.getSystemService(CameraManager::class.java)
    val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
    val ch = cm.getCameraCharacteristics(id)
    val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
    val s = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
    f to maxOf(s.width, s.height)
}.getOrNull()
