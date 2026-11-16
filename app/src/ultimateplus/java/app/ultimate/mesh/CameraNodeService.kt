package app.ultimate.mesh

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import app.ultimate.MainActivity
import app.ultimate.R
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Holds this device's camera open while the owner shares it with their other devices. A pull-based design keeps it cheap: a new
 * picture is made about four times a second only while someone is looking, and about every two seconds otherwise (so the first
 * look has something to show). The notification stays up the whole time, says who is watching, and has Stop on it.
 */
class CameraNodeService : LifecycleService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var lastFrame = 0L
    private val refresh = object : Runnable { override fun run() { notifyNow(); main.postDelayed(this, 2_000) } }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Camera shared with my devices", NotificationManager.IMPORTANCE_LOW))
        runCatching { ServiceCompat.startForeground(this, ID, notification(), if (android.os.Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) }
            .onFailure { stopSelf(); return START_NOT_STICKY }
        bind()
        main.postDelayed(refresh, 2_000)
        return START_NOT_STICKY
    }

    private fun bind() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get(); provider = p
                val analysis = ImageAnalysis.Builder().setTargetResolution(Size(640, 480)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastFrame >= (if (CameraNode.watched()) 250 else 2_000)) {
                            lastFrame = now
                            val plane = image.planes[0]
                            val pixels = ByteArray(image.width * image.height * 4)
                            val src = plane.buffer.duplicate()
                            for (row in 0 until image.height) { src.position(row * plane.rowStride); src.get(pixels, row * image.width * 4, image.width * 4) }
                            val raw = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixels)) }
                            val turned = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }, true)
                            val small = Bitmap.createScaledBitmap(turned, 480, (480f * turned.height / turned.width).toInt().coerceAtLeast(1), true)
                            CameraNode.latest.put(jpeg(small))
                            if (small !== turned) small.recycle(); if (turned !== raw) turned.recycle(); raw.recycle()
                        }
                    } catch (_: Exception) { /* one dropped picture is fine */ } finally { image.close() }
                }
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                CameraNode.setArmed(true)
            } catch (_: Exception) { stopSelf() }
        }, ContextCompat.getMainExecutor(this))
    }

    /** A JPEG small enough to send in a few pieces: the quality drops until it fits. */
    private fun jpeg(b: Bitmap): ByteArray {
        var q = 55
        while (true) {
            val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, q, out)
            val bytes = out.toByteArray()
            if (bytes.size <= CamFrames.CHUNK * CamFrames.MAX_PARTS - 1_000 || q <= 25) return bytes
            q -= 10
        }
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat).setOngoing(true)
        .setContentTitle(CameraNode.viewer?.takeIf { CameraNode.watched() }?.let { "$it is watching through this camera" } ?: "This camera is shared with your other devices")
        .setContentText("Only devices paired with your mesh code can look. Tap Stop to turn it off.")
        .setContentIntent(PendingIntent.getActivity(this, 81, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "mesh").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Stop", PendingIntent.getService(this, 82, Intent(this, CameraNodeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)).build()

    private fun notifyNow() { getSystemService(NotificationManager::class.java)?.notify(ID, notification()) }

    override fun onDestroy() {
        main.removeCallbacks(refresh)
        runCatching { provider?.unbindAll() }
        CameraNode.setArmed(false)
        executor.shutdown()
        super.onDestroy()
    }

    companion object { private const val CHANNEL = "camera-node"; private const val ID = 83; const val ACTION_STOP = "app.ultimate.mesh.CAMERA_STOP" }
}
