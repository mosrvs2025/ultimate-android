package app.ultimate.spatial

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.ultimate.R
import app.ultimate.UltimateApp

/**
 * "Keep sensing": spatial sensing with the screen off, so place and watch rules work all day. Shows a notification
 * with Stop; stops itself when turned off. Uses Bluetooth (your watch's beacon) and Wi-Fi scans, both on the phone.
 */
class SpatialService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as UltimateApp
        if (intent?.action == STOP) { set(this, false); app.spatial.stop(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Spatial sensing", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 3, Intent(this, SpatialService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("Sensing your surroundings")
            .setContentText("Your watch's distance and which place you're in, for your Rules.").setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ID, n)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        app.spatial.start()
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "spatial"; private const val ID = 5101; private const val STOP = "app.ultimate.spatial.STOP"
        fun on(c: Context) = c.getSharedPreferences("spatial", Context.MODE_PRIVATE).getBoolean("keep", false)
        fun set(c: Context, on: Boolean) {
            c.getSharedPreferences("spatial", Context.MODE_PRIVATE).edit().putBoolean("keep", on).apply()
            val i = Intent(c, SpatialService::class.java)
            if (on) runCatching { c.startForegroundService(i) } else c.stopService(i)
        }
    }
}
