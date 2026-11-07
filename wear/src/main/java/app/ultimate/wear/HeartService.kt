package app.ultimate.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder

/**
 * Keeps sending your heart rate to Ultimate+ on the phone even when the watch app is closed or the screen is off,
 * so body rules ("when my heart rate stays high…") work all day. It runs only while "Share heart rate" is on, shows
 * a notification with Stop, and sends a reading at most every 5 seconds. The heart sensor costs battery, which the
 * notification says.
 */
class HeartService : Service() {
    private var link: PhoneLink? = null
    private var lastSent = 0L
    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(e: SensorEvent) {
            val bpm = e.values.firstOrNull()?.toInt() ?: return
            val now = System.currentTimeMillis()
            if (bpm > 0 && now - lastSent >= 5_000) { lastSent = now; link?.stream("hr:$bpm") }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            getSharedPreferences("watch", MODE_PRIVATE).edit().putBoolean("share_heart", false).apply()
            stopSelf(); return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Heart rate sharing", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, HeartService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Sharing heart rate with your phone")
            .setContentText("For your body rules in Ultimate+. Uses more battery.").setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH) else startForeground(ID, n)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        if (link == null) {
            link = PhoneLink(this)
            val sm = getSystemService(SensorManager::class.java)
            sm?.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        getSystemService(SensorManager::class.java)?.unregisterListener(listener)
        link = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "heart"
        private const val ID = 7
        private const val STOP = "app.ultimate.wear.HEART_STOP"
        fun start(c: Context) = runCatching { c.startForegroundService(Intent(c, HeartService::class.java)) }
        fun stop(c: Context) = c.stopService(Intent(c, HeartService::class.java))
    }
}
