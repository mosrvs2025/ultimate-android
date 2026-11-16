package app.ultimate.mesh

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.ultimate.MainActivity
import app.ultimate.R
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaConstraints
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Holds this device's camera (and microphone, if allowed) open while the owner shares it with their other devices. One camera
 * capture feeds two things: the live video sent to whoever is watching ([CameraRtc]), and a still picture kept up to date for
 * viewers that can't do live video ([CamFrames]), made about four times a second while watched and every two seconds otherwise.
 * The notification stays up the whole time, says who is watching, and has Stop on it.
 */
class CameraNodeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var lastStill = 0L
    private val stills = VideoSink { frame ->
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStill >= (if (CameraNode.watched()) 250 else 2_000)) {
            lastStill = now
            LiveRtc.jpeg(frame)?.let { jpeg -> if (jpeg.size <= CamFrames.CHUNK * CamFrames.MAX_PARTS - 1_000) CameraNode.latest.put(jpeg) }
        }
    }
    private val refresh = object : Runnable { override fun run() { notifyNow(); CameraRtc.sweep(); main.postDelayed(this, 2_000) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return START_NOT_STICKY }
        val withMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Camera shared with my devices", NotificationManager.IMPORTANCE_LOW))
        val type = if (android.os.Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or (if (withMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) else 0
        runCatching { ServiceCompat.startForeground(this, ID, notification(), type) }.onFailure { stopSelf(); return START_NOT_STICKY }
        Thread { start(withMic) }.start()
        main.postDelayed(refresh, 2_000)
        return START_NOT_STICKY
    }

    /** Opens the back camera and (if allowed) the microphone and makes them available to calls. */
    private fun start(withMic: Boolean) {
        try {
            val factory = LiveRtc.factory(this)
            val enumerator = Camera2Enumerator(this)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) } ?: enumerator.deviceNames.firstOrNull() ?: throw IllegalStateException("no camera")
            val cap = enumerator.createCapturer(name, null) ?: throw IllegalStateException("the camera would not open")
            val h = SurfaceTextureHelper.create("camera-node", LiveRtc.egl.eglBaseContext)
            val source = factory.createVideoSource(false)
            cap.initialize(h, this, source.capturerObserver)
            cap.startCapture(640, 480, 20)
            capturer = cap; helper = h; videoSource = source
            videoTrack = factory.createVideoTrack("camera", source).also { it.addSink(stills); CameraRtc.video = it }
            if (withMic) {
                audioSource = factory.createAudioSource(MediaConstraints())
                audioTrack = factory.createAudioTrack("mic", audioSource).also { CameraRtc.audio = it }
            }
            CameraNode.setArmed(true)
        } catch (e: Exception) { main.post { stopSelf() } }
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat).setOngoing(true)
        .setContentTitle(CameraNode.viewer?.takeIf { CameraNode.watched() || CameraRtc.calls.active().isNotEmpty() }?.let { "$it is watching through this camera" } ?: "This camera is shared with your other devices")
        .setContentText("Only devices paired with your mesh code can look and listen. Tap Stop to turn it off.")
        .setContentIntent(PendingIntent.getActivity(this, 81, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "mesh").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Stop", PendingIntent.getService(this, 82, Intent(this, CameraNodeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)).build()

    private fun notifyNow() { getSystemService(NotificationManager::class.java)?.notify(ID, notification()) }

    override fun onDestroy() {
        main.removeCallbacks(refresh)
        CameraNode.setArmed(false)
        CameraRtc.closeAll(); CameraRtc.video = null; CameraRtc.audio = null
        runCatching { capturer?.stopCapture() }; runCatching { capturer?.dispose() }
        runCatching { videoTrack?.removeSink(stills) }; runCatching { videoTrack?.dispose() }; runCatching { audioTrack?.dispose() }
        runCatching { videoSource?.dispose() }; runCatching { audioSource?.dispose() }; runCatching { helper?.dispose() }
        super.onDestroy()
    }

    companion object { private const val CHANNEL = "camera-node"; private const val ID = 83; const val ACTION_STOP = "app.ultimate.mesh.CAMERA_STOP" }
}
