package app.ultimate.context

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.sqrt

/** Reads the few cheap, permission-free facts that make up a [Situation]. Nothing here is stored or sent. */
object SituationReader {
    fun charging(c: Context): Boolean = runCatching {
        c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false
    }.getOrDefault(false)

    private val headphoneTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
    )

    fun headphones(c: Context): Boolean = runCatching {
        c.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headphoneTypes }
    }.getOrDefault(false)

    /** [prev] is the tool opened just before, if it was recent. Wi-Fi is left out: offline editions don't ask to see the network. */
    fun now(c: Context, prev: String?, at: Long = System.currentTimeMillis()): Situation {
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        return Situation.of(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.DAY_OF_WEEK), charging(c), headphones(c), wifi = null, prev = prev)
    }
}

/** Listens to the accelerometer for a moment to see how the phone is being carried. Needs no permission. */
object MotionSampler {
    suspend fun sample(c: Context, ms: Long = 2500): Motion {
        val sm = c.getSystemService(SensorManager::class.java) ?: return Motion.Still
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return Motion.Still
        val values = ArrayList<Float>(256)
        val start = System.nanoTime()
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { values += sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        return try {
            sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_GAME)
            delay(ms)
            val secs = (System.nanoTime() - start) / 1e9f
            // Resample to an even rate so the classifier's frequency maths is right whatever the sensor delivered.
            val n = values.size
            if (n < 8) Motion.Still else MotionClassifier.classify(values.toFloatArray(), n / secs)
        } finally { sm.unregisterListener(l) }
    }
}
