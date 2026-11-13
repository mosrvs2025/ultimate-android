package app.ultimate.mesh

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager

/**
 * Records this phone's accelerometer and gyroscope into a [MotionLog] while a session is on. One row per accelerometer
 * event carrying the most recent gyroscope reading, stamped in milliseconds since the start. Holds a partial wake lock
 * (capped) so recording continues with the screen off, which is how a phone is used strapped to an arm or in a pocket.
 */
class MotionRecorder(context: Context) : SensorEventListener {
    private val app = context.applicationContext
    private val sm = app.getSystemService(SensorManager::class.java)
    private var wake: PowerManager.WakeLock? = null
    @Volatile var log = MotionLog(); private set
    @Volatile var running = false; private set
    @Volatile var startedAtMs = 0L; private set
    private var t0 = 0L
    private val gyro = FloatArray(3)

    val hasAccelerometer get() = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    val hasGyroscope get() = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    /** Returns null if recording began, else why not. */
    @Synchronized fun start(rateHz: Int = 50): String? {
        if (running) return "already recording"
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return "this phone has no accelerometer"
        log = MotionLog(); t0 = 0L; gyro.fill(0f)
        val us = 1_000_000 / rateHz.coerceIn(10, 100)
        if (!sm.registerListener(this, acc, us)) return "Android would not start the accelerometer"
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, us) }
        wake = app.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ultimate:motion")?.apply { setReferenceCounted(false); acquire(MAX_MS) }
        startedAtMs = System.currentTimeMillis(); running = true
        return null
    }

    @Synchronized fun stop() {
        if (!running) return
        running = false
        sm?.unregisterListener(this)
        runCatching { wake?.takeIf { it.isHeld }?.release() }; wake = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!running) return
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> { gyro[0] = e.values[0]; gyro[1] = e.values[1]; gyro[2] = e.values[2] }
            Sensor.TYPE_ACCELEROMETER -> {
                if (t0 == 0L) t0 = e.timestamp
                val ms = ((e.timestamp - t0) / 1_000_000L).toInt()
                if (!log.add(ms, e.values[0], e.values[1], e.values[2], gyro[0], gyro[1], gyro[2])) stop()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object { const val MAX_MS = 2L * 60 * 60 * 1000 + 60_000 }
}
