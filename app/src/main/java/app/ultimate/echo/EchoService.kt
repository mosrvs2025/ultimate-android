package app.ultimate.echo

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

/**
 * Keeps Echo's microphone allowed while you are in another app, for rules that listen for it, and keeps a notification
 * with Stop on screen the whole time it runs. It lives exactly as long as Echo does.
 */
class EchoService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    // Declared with foregroundServiceType="microphone" in the Ultimate and Ultimate+ manifests, the only editions with Echo.
    @android.annotation.SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { (application as UltimateApp).senses.stop(EchoEngine.SESSION); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Echo sensing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 31, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "echo").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 32, Intent(this, EchoService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("Echo is sensing movement")
            .setContentText("Using the speaker and mic. Nothing is recorded.")
            .setOngoing(true).setUsesChronometer(true).setContentIntent(open)
            .addAction(0, "Stop", stop).build()
        ServiceCompat.startForeground(this, 6, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "echo"
        private const val ACTION_STOP = "app.ultimate.echo.STOP"
    }
}
