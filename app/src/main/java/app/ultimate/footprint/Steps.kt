package app.ultimate.footprint

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Step counter readings kept on the phone, so days can be worked out. Nothing else is stored, and it can be cleared. */
class StepStore(context: Context) {
    private val file = File(context.filesDir, "footprint-steps.json")
    private val prefs = context.getSharedPreferences("footprint", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("steps", false)
        set(v) { prefs.edit().putBoolean("steps", v).apply() }

    @Synchronized fun all(): List<StepSample> = runCatching {
        val a = JSONArray(file.readText()); List(a.length()) { a.getJSONArray(it).let { p -> StepSample(p.getLong(0), p.getLong(1)) } }
    }.getOrDefault(emptyList())

    @Synchronized fun add(s: StepSample) {
        // Old readings fall off after four months, and the file never grows without bound.
        val keep = (all() + s).filter { it.at > s.at - 120 * 86_400_000L }.takeLast(6000)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray().apply { keep.forEach { put(JSONArray(listOf(it.at, it.counter))) } }.toString()); tmp.renameTo(file)
    }

    @Synchronized fun clear() { file.delete() }
}

object StepSampler {
    private const val WORK = "footprint-steps"

    fun supported(context: Context) = context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    fun allowed(context: Context) = context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** Asks the step counter for its current value and waits a few seconds for it; null if it won't say. */
    fun read(context: Context): StepSample? {
        if (!allowed(context)) return null
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        val latch = CountDownLatch(1); var value = -1f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { value = e.values[0]; latch.countDown() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        try { latch.await(4, TimeUnit.SECONDS) } finally { sm.unregisterListener(listener) }
        return if (value >= 0) StepSample(System.currentTimeMillis(), value.toLong()) else null
    }

    fun sampleNow(context: Context) { read(context)?.let { StepStore(context).add(it) } }

    fun start(context: Context) {
        StepStore(context).enabled = true
        sampleNow(context)
        schedule(context)
    }

    /** One reading every half hour while counting is on, even when the app is closed. */
    fun schedule(context: Context) {
        if (!StepStore(context).enabled) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<StepWorker>(30, TimeUnit.MINUTES).setConstraints(Constraints.NONE).build())
    }

    fun stop(context: Context, forget: Boolean) {
        StepStore(context).apply { enabled = false; if (forget) clear() }
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}

class StepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { StepSampler.sampleNow(applicationContext); return Result.success() }
}
