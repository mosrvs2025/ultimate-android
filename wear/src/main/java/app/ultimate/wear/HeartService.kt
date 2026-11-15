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
 * so body rules ("when my heart rate stays high…") work all day. It runs only while "Share heart rate" is on and shows
 * a notification with Stop. To spare the battery it measures in short bursts (see [startWindow]).
 */
class HeartService : Service() {
    private var link: PhoneLink? = null
    private var lastSent = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var listening = false
    private var window = ArrayList<Int>()
    /** While your heart rate is high, keep measuring so a stress spike or a workout is followed closely. */
    private var raised = false
    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(e: SensorEvent) {
            val bpm = e.values.firstOrNull()?.toInt() ?: return
            if (bpm <= 0) return
            window += bpm
            val now = System.currentTimeMillis()
            if (raised && now - lastSent >= 15_000) { lastSent = now; link?.stream("hr:$bpm") }
        }
    }

    // Saves battery: the heart sensor runs for about 25 seconds every 5 minutes, and only stays on while your heart
    // rate is high (110 or more), until it settles under 100.
    private val measure = Runnable { startWindow() }
    private val finish = Runnable { endWindow() }
    private fun startWindow() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        window = ArrayList()
        if (!listening) sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let { listening = sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        handler.postDelayed(finish, 25_000)
    }
    private fun endWindow() {
        // The first seconds are the sensor settling; use the steadier rest.
        val steady = window.drop(window.size / 3).sorted()
        val bpm = steady.getOrNull(steady.size / 2)
        if (bpm != null) { lastSent = System.currentTimeMillis(); link?.stream("hr:$bpm") }
        raised = if (raised) (bpm ?: 0) >= 100 else (bpm ?: 0) >= 110
        if (raised) { handler.postDelayed(finish, 60_000); window = ArrayList(); return }
        getSystemService(SensorManager::class.java)?.unregisterListener(listener); listening = false
        handler.postDelayed(measure, 5 * 60_000L)
    }

    // Walking: the step counter, delivered in hardware batches every 30 s (the watch stays asleep in between).
    private val steps = StepActivity()
    private var stepsOn = false
    private val stepListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(e: SensorEvent) {
            val count = e.values.firstOrNull()?.toLong() ?: return
            val now = System.currentTimeMillis()
            val change = steps.feed(count, now)
            // A change of state is sent reliably (the phone may offer to record); while moving, a count goes every batch.
            change?.let { link?.send("activity:${it.state.key}:${it.spm}:${it.steps}:${it.since}") }
            if (steps.state != StepActivity.State.Still || StepActivity.phoneRecording.value != null) link?.stream("steps:$count:${steps.spm.toInt()}")
        }
    }
    private fun startSteps() {
        if (stepsOn || !getSharedPreferences("watch", MODE_PRIVATE).getBoolean("share_walking", false)) return
        if (checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val sm = getSystemService(SensorManager::class.java) ?: return
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        stepsOn = sm.registerListener(stepListener, s, SensorManager.SENSOR_DELAY_NORMAL, 30_000_000)
    }

    // While the phone is recording a walk or run, heart rate is measured every 15 s instead of every 5 minutes.
    private val follow = Runnable { followPhone() }
    private fun followPhone() {
        val recording = StepActivity.phoneRecording.value?.let { System.currentTimeMillis() - it.at < 120_000 } == true
        if (recording && !raised) { raised = true; handler.removeCallbacks(measure); handler.removeCallbacks(finish); startWindow() }
        handler.postDelayed(follow, 10_000)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            getSharedPreferences("watch", MODE_PRIVATE).edit().putBoolean("share_heart", false).putBoolean("share_walking", false).apply()
            stopSelf(); return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Heart rate sharing", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, HeartService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Sharing with your phone")
            .setContentText(listOfNotNull(if (getSharedPreferences("watch", MODE_PRIVATE).getBoolean("share_heart", false)) "heart rate" else null, if (getSharedPreferences("watch", MODE_PRIVATE).getBoolean("share_walking", false)) "walking" else null).joinToString(" and ").replaceFirstChar { it.uppercase() }).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH) else startForeground(ID, n)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        if (link == null) { link = PhoneLink(this); if (getSharedPreferences("watch", MODE_PRIVATE).getBoolean("share_heart", false)) startWindow(); followPhone() }
        startSteps()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        getSystemService(SensorManager::class.java)?.unregisterListener(listener)
        getSystemService(SensorManager::class.java)?.unregisterListener(stepListener); stepsOn = false
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
