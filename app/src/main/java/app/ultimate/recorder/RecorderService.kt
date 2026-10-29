package app.ultimate.recorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.ultimate.MainActivity
import app.ultimate.R
import app.ultimate.UltimateApp

/** Keeps the microphone allowed while the screen is off or another app is open; it lives exactly as long as a recording. */
class RecorderService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    // Declared with foregroundServiceType="microphone" in the Ultimate and Ultimate+ manifests, the only editions with Recorder;
    // lint checks every edition's manifest, including ones that never start this service.
    @android.annotation.SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = (application as UltimateApp).recorder
        if (intent?.action == ACTION_STOP) { manager.stop(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "recorder").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 8, Intent(this, RecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recorder).setColor(0xFFD32F2F.toInt())
            .setContentTitle("Recording").setContentText("Tap to open · Stop saves it to Music/Ultimate")
            .setUsesChronometer(true).setOngoing(true).setContentIntent(open)
            .addAction(0, "Stop and save", stop).build()
        ServiceCompat.startForeground(this, 2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "recording"
        const val ACTION_STOP = "app.ultimate.recorder.STOP"
    }
}
