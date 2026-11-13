package app.ultimate.movement

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
 * Keeps a movement recording going with the screen off (a run with the phone in a pocket). It exists only while a recording does, and its
 * notification carries Stop and a running clock. Declared with the location type because the recording uses GPS.
 */
class MovementService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { MovementRecorder.get(application as UltimateApp).stop(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Movement recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 51, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "movement").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 52, Intent(this, MovementService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("Recording movement").setContentText("Using motion sensors and GPS. Stays on this phone.")
            .setOngoing(true).setUsesChronometer(true).setContentIntent(open).addAction(0, "Stop", stop).build()
        runCatching { ServiceCompat.startForeground(this, 9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }
        return START_NOT_STICKY
    }

    private companion object { const val CHANNEL = "movement"; const val ACTION_STOP = "app.ultimate.movement.STOP" }
}
