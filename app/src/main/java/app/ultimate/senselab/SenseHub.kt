package app.ultimate.senselab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.ultimate.air.WatchFeed
import app.ultimate.senselab.StudioMath.Point
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Every sense on one timeline while Gesture Studio is open: the phone's proximity, light and motion sensors, the
 * microphone's loudness (when Sound is on), the watch's motion (when linked), and Echo's distance (when Echo runs).
 * Readings are kept for a few seconds and dropped; nothing is recorded or saved.
 */
class SenseHub(private val context: Context) {
    private val ch = StudioMath.CHANNELS
    private val buffer = ArrayDeque<Point>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var micJob: Job? = null
    private var watchJob: Job? = null
    /** Changes whenever new readings arrive, for the screen to redraw. */
    val tick = MutableStateFlow(0L)
    /** Which senses are giving readings right now. */
    val live = MutableStateFlow<Set<StudioMath.Lane>>(emptySet())
    private val lastSeen = HashMap<StudioMath.Lane, Long>()

    @Synchronized private fun add(channel: String, v: Float, t: Long = System.currentTimeMillis()) {
        buffer.addLast(Point(t, ch.indexOf(channel), v))
        while (buffer.isNotEmpty() && t - buffer.first().t > 8_000) buffer.removeFirst()
        StudioMath.Lane.entries.firstOrNull { channel in it.channels }?.let { lastSeen[it] = t }
    }
    @Synchronized fun points(): List<Point> = buffer.toList()

    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                // Proximity: 1 when something is close to the top of the phone, 0 when not.
                Sensor.TYPE_PROXIMITY -> add("near", if (e.values[0] < e.sensor.maximumRange * 0.5f) 1f else 0f)
                // Light: log scale, so a hand's shadow shows the same in a dim room as in daylight.
                Sensor.TYPE_LIGHT -> add("light", log10(1f + e.values[0]))
                Sensor.TYPE_GYROSCOPE -> add("turn", sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]))
                Sensor.TYPE_LINEAR_ACCELERATION -> add("jolt", sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]))
            }
        }
    }

    /** Starts the phone's sensors and the watch's stream; Sound and Echo are separate switches. */
    fun start() {
        val sm = context.getSystemService(SensorManager::class.java)
        listOf(Sensor.TYPE_PROXIMITY, Sensor.TYPE_LIGHT, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LINEAR_ACCELERATION).forEach { type ->
            sm?.getDefaultSensor(type)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        WatchFeed.request(true)
        watchJob = scope.launch {
            var lastT = 0L; var renewed = System.currentTimeMillis()
            while (isActive) {
                WatchFeed.samples.value.filter { it.t > lastT }.forEach { s ->
                    add("wx", s.gx, s.t); add("wy", s.gy, s.t); add("wz", s.gz, s.t); add("wjolt", s.jolt, s.t); lastT = s.t
                }
                val now = System.currentTimeMillis()
                if (now - renewed > 4_000) { WatchFeed.request(true); renewed = now }
                live.value = lastSeen.filterValues { now - it < 1_500 }.keys
                tick.value = now
                kotlinx.coroutines.delay(16)
            }
        }
    }

    fun stop() {
        context.getSystemService(SensorManager::class.java)?.unregisterListener(listener)
        WatchFeed.request(false)
        watchJob?.cancel(); watchJob = null
        sound(false); echoFeed = null
    }

    /** The microphone's loudness, 20 times a second, in decibels; only while this is on. */
    fun sound(on: Boolean): Boolean {
        micJob?.cancel(); micJob = null
        if (!on) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        micJob = scope.launch {
            val rate = 16_000; val frame = rate / 20
            val size = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), frame * 2)
            val rec = runCatching { @Suppress("MissingPermission") AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size) }.getOrNull() ?: return@launch
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@launch }
            val buf = ShortArray(frame)
            try {
                rec.startRecording()
                while (isActive) {
                    val n = rec.read(buf, 0, frame); if (n <= 0) continue
                    var sum = 0.0; for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                    add("loud", (20 * log10(sqrt(sum / n) + 1)).toFloat())
                }
            } finally { runCatching { rec.stop() }; rec.release() }
        }
        return true
    }

    /** Echo's distance, fed from its sonar while it runs (in metres, 0 when nothing is in range). */
    @Volatile var echoFeed: ((Long, FloatArray) -> Unit)? = null
    fun echoListener(): (Long, FloatArray) -> Unit = { t, f -> add("range", if (f[0] > 0.5f) f[1] else 0f, t) }
}
