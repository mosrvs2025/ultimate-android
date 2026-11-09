package app.ultimate.vision

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import app.ultimate.air.HandPose
import app.ultimate.air.TongueDetector
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One camera, both trackers: your hand and your face at the same time, from the same frames, so hand gestures and
 * face moves can work together (and air drawing can stick to your face). [dark] goes true when the picture is too
 * dark to track well, so the screen can offer to light your face.
 */
@Composable
internal fun HolisticCamera(front: Boolean, hand: MutableStateFlow<HandPose?>?, face: MutableStateFlow<AirTool.FacePose?>?) {
    val context = LocalContext.current
    val tongue = remember { TongueDetector() }
    val recognizer = remember {
        if (hand == null) null else runCatching {
            GestureRecognizer.createFromOptions(context, GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                .setRunningMode(RunningMode.LIVE_STREAM).setNumHands(1)
                .setResultListener { result, _ ->
                    val lm = result.landmarks().firstOrNull()
                    if (lm == null || lm.size < 21) { hand.value = null; return@setResultListener }
                    val top = result.gestures().firstOrNull()?.firstOrNull()
                    hand.value = HandPose(lm.map { it.x() to it.y() }, top?.categoryName(), top?.score() ?: 0f, System.currentTimeMillis())
                }.setErrorListener { hand.value = null }.build())
        }.getOrNull()
    }
    val landmarker = remember {
        if (face == null) null else runCatching {
            FaceLandmarker.createFromOptions(context, FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                .setRunningMode(RunningMode.LIVE_STREAM).setNumFaces(1).setOutputFaceBlendshapes(true)
                .setResultListener { result, image ->
                    val lm = result.faceLandmarks().firstOrNull()
                    if (lm == null) { face.value = null; return@setResultListener }
                    var scores = result.faceBlendshapes().orElse(null)?.firstOrNull()?.associate { it.categoryName() to it.score() }.orEmpty()
                    val pts = lm.map { it.x() to it.y() }
                    runCatching {
                        val bmp = BitmapExtractor.extract(image)
                        scores = scores + ("tongueOut" to tongue.feed(pts) { x, y -> bmp.getPixel((x * bmp.width).toInt().coerceIn(0, bmp.width - 1), (y * bmp.height).toInt().coerceIn(0, bmp.height - 1)) })
                    }
                    face.value = AirTool.FacePose(pts, scores, System.currentTimeMillis())
                }.setErrorListener { face.value = null }.build())
        }.getOrNull()
    }
    DisposableEffect(Unit) { onDispose { runCatching { recognizer?.close() }; runCatching { landmarker?.close() }; hand?.value = null; face?.value = null } }
    AnalysedCamera(front) { bmp, t ->
        val img = BitmapImageBuilder(bmp).build()
        recognizer?.recognizeAsync(img, t)
        // MediaPipe wants increasing times per task; the same frame time is fine for two different tasks.
        landmarker?.detectAsync(BitmapImageBuilder(bmp).build(), t)
    }
}

/** Whether the camera's picture is too dark to track well; every analysed camera updates it now and then. */
internal object CameraLight {
    val dark = MutableStateFlow(false)
    @Volatile var tick = 0
    fun check(b: Bitmap) { val v = brightness(b); if (dark.value) { if (v > 0.24f) dark.value = false } else if (v < 0.16f) dark.value = true }
}

/**
 * A ring light made of the screen: a warm white glow around the edges, at full brightness, so your face and hands
 * are lit for the camera in the dark. Offered when the picture is dark; tap to turn on or off.
 */
@Composable
internal fun RingLight(on: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(on) {
        val w = (view.context as? android.app.Activity)?.window
        val before = w?.attributes?.screenBrightness
        if (on) w?.let { it.attributes = it.attributes.apply { screenBrightness = 1f } }
        onDispose { if (on && before != null) w.let { it.attributes = it.attributes.apply { screenBrightness = before } } }
    }
    if (on) androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().border(36.dp, androidx.compose.ui.graphics.Color(0xFFFFF3E0)))
}

/** The "it's dark" chip: offers the ring light. */
@Composable
internal fun DarkHint(on: Boolean, onToggle: () -> Unit) {
    val dark by CameraLight.dark.collectAsState()
    if (dark || on) androidx.compose.material3.FilledTonalButton(onToggle) { androidx.compose.material3.Text(if (on) "💡 Ring light on" else "🌙 It's dark: light my face") }
}

/** Average brightness (0..1) of a sparse grid of the picture's pixels. */
private fun brightness(b: Bitmap): Float {
    var sum = 0f; var n = 0
    for (yi in 1..8) for (xi in 1..8) {
        val c = b.getPixel(b.width * xi / 9, b.height * yi / 9)
        sum += (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f; n++
    }
    return sum / n
}

/**
 * Your watch's motion as a thin live strip along the bottom of a camera view: turn about x, y, z, so what your hand does
 * on camera and what your wrist feels can be seen together. Asks the watch to stream while it's on screen; shows
 * nothing when no watch is linked.
 */
@Composable
internal fun WatchRibbon(modifier: androidx.compose.ui.Modifier) {
    val samples by app.ultimate.air.WatchFeed.samples.collectAsState()
    DisposableEffect(Unit) { onDispose { app.ultimate.air.WatchFeed.request(false) } }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { app.ultimate.air.WatchFeed.request(true); kotlinx.coroutines.delay(4_000) } }
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { androidx.compose.runtime.withFrameMillis { now = System.currentTimeMillis() } } }
    val recent = samples.filter { now - it.t <= 3_000 }
    if (recent.size < 2) return
    androidx.compose.foundation.Canvas(modifier.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))) {
        val mid = size.height / 2
        fun line(c: androidx.compose.ui.graphics.Color, v: (app.ultimate.air.WristCalibration.Sample) -> Float) {
            val p = androidx.compose.ui.graphics.Path()
            recent.forEachIndexed { i, s -> val x = size.width * (1f - (now - s.t) / 3_000f); val y = mid - v(s) / 12f * mid; if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
            drawPath(p, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        }
        line(androidx.compose.ui.graphics.Color(0xFFFF6B6B)) { it.gx }; line(androidx.compose.ui.graphics.Color(0xFF3DDC97)) { it.gy }; line(androidx.compose.ui.graphics.Color(0xFF7FD3FF)) { it.gz }
    }
}
