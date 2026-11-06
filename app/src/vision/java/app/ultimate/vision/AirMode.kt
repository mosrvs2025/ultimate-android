package app.ultimate.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.provider.Settings
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import app.ultimate.R
import app.ultimate.air.AirPointer
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import kotlin.math.hypot

/** What Air mode is doing, shared between the camera service and the cursor drawn over every app. */
object AirBridge {
    /** The cursor: where it is on screen, whether a hand is seen, and whether it's pinched. */
    data class Cursor(val x: Float, val y: Float, val visible: Boolean, val pinched: Boolean)
    val running = MutableStateFlow(false)
    val cursor = MutableStateFlow(Cursor(0f, 0f, false, false))
    /** Things to do: taps and drags at screen positions, and Back, Home, Recents. */
    val actions = MutableSharedFlow<Any>(extraBufferCapacity = 16)
    object Back; object Home; object Recents

    fun accessibilityOn(c: Context): Boolean {
        val me = ComponentName(c, AirCursorService::class.java).flattenToString()
        return Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any { it.equals(me, true) }
    }
    fun start(c: Context) = ContextCompat.startForegroundService(c, Intent(c, AirModeService::class.java))
    fun stop(c: Context) = c.startService(Intent(c, AirModeService::class.java).setAction(AirModeService.STOP))
}

/**
 * Air mode's eyes: the front camera watches your hand while you use any app, and turns it into a cursor and taps.
 * It runs as a foreground service (Android shows its camera indicator and a notification with Stop), started only
 * from Air control, and stops when you tap Stop. Frames are looked at and dropped.
 */
class AirModeService : LifecycleService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var recognizer: GestureRecognizer? = null
    private var pointer: AirPointer? = null
    private var sign: String? = null
    private var signSince = 0L
    private var lastShortcut = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Air mode", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, AirModeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("Air mode is on")
            .setContentText("Point to move, pinch to tap. Open palm: Back. Fist: Home.").setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) else startForeground(ID, n)
        if (recognizer == null) begin()
        return START_NOT_STICKY
    }

    private fun begin() {
        val metrics = resources.displayMetrics
        pointer = AirPointer(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
        recognizer = runCatching {
            GestureRecognizer.createFromOptions(this, GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                .setRunningMode(RunningMode.LIVE_STREAM).setNumHands(1)
                .setResultListener { result, _ -> onHand(result) }.setErrorListener { }.build())
        }.getOrNull() ?: run { stopSelf(); return }
        AirBridge.running.value = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            analysis.setAnalyzer(executor) { proxy ->
                runCatching {
                    val rot = proxy.imageInfo.rotationDegrees
                    val bmp = proxy.toBitmap().let { b -> if (rot == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true) }
                    recognizer?.recognizeAsync(BitmapImageBuilder(bmp).build(), System.currentTimeMillis())
                }
                proxy.close()
            }
            runCatching { provider.unbindAll(); provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onHand(result: com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult) {
        val p = pointer ?: return
        val lm = result.landmarks().firstOrNull()
        val now = System.currentTimeMillis()
        if (lm == null || lm.size < 21) { p.lost(); AirBridge.cursor.value = AirBridge.cursor.value.copy(visible = false, pinched = false); sign = null; return }
        // The hand's size (wrist to middle knuckle) makes the pinch the same near the camera and far from it.
        val size = hypot(lm[0].x() - lm[9].x(), lm[0].y() - lm[9].y()).coerceAtLeast(1e-3f)
        val pinch = hypot(lm[4].x() - lm[8].x(), lm[4].y() - lm[8].y()) / size
        // Front camera: mirror so moving your hand right moves the cursor right.
        p.feed(1f - lm[8].x(), lm[8].y(), pinch, now)?.let { AirBridge.actions.tryEmit(it) }
        AirBridge.cursor.value = AirBridge.Cursor(p.x, p.y, true, p.pinched)
        // Held hand signs are shortcuts: open palm Back, fist Home, peace sign Recents.
        val top = result.gestures().firstOrNull()?.firstOrNull()?.takeIf { it.score() > 0.6f }?.categoryName()
        if (top != sign) { sign = top; signSince = now }
        if (top != null && !p.pinched && now - signSince > 600 && now - lastShortcut > 1_500) {
            when (top) { "Open_Palm" -> AirBridge.Back; "Closed_Fist" -> AirBridge.Home; "Victory" -> AirBridge.Recents; else -> null }
                ?.let { lastShortcut = now; AirBridge.actions.tryEmit(it) }
        }
    }

    override fun onDestroy() {
        AirBridge.running.value = false
        AirBridge.cursor.value = AirBridge.Cursor(0f, 0f, false, false)
        runCatching { recognizer?.close() }; recognizer = null
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val STOP = "app.ultimate.air.STOP"
        private const val CHANNEL = "air_mode"
        private const val ID = 4401
    }
}
