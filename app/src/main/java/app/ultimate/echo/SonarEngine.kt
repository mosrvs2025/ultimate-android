package app.ultimate.echo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.BatteryManager
import app.ultimate.core.Senses
import app.ultimate.core.Signal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/** What the phone reports about its own audio and motion hardware. Reported by Android, not measured by Echo. */
data class EchoHardware(
    val speakerUltrasound: String?, val micUltrasound: String?, val unprocessed: Boolean, val outputRate: String?,
    val mics: List<String>, val micSpacingM: Double?, val gyroscope: Boolean, val accelerometer: Boolean,
)

object EchoProbe {
    fun read(context: Context): EchoHardware {
        val am = context.getSystemService(AudioManager::class.java)
        val sm = context.getSystemService(SensorManager::class.java)
        val mics = runCatching { am.microphones }.getOrDefault(emptyList()).filter { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val names = mics.map { m ->
            val where = when (m.location) { android.media.MicrophoneInfo.LOCATION_MAINBODY -> "body"; android.media.MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "movable part"; else -> "unknown place" }
            val pos = m.position.takeIf { it != android.media.MicrophoneInfo.POSITION_UNKNOWN }?.let { " at (%.1f, %.1f, %.1f) cm".format(it.x * 100, it.y * 100, it.z * 100) }.orEmpty()
            "${m.description.ifBlank { "Microphone" }} · $where$pos"
        }
        // Spacing between the first two mics whose positions the phone states; most phones don't state them.
        val known = mics.mapNotNull { it.position.takeIf { p -> p != android.media.MicrophoneInfo.POSITION_UNKNOWN } }
        val spacing = if (known.size >= 2) sqrt(((known[0].x - known[1].x) * (known[0].x - known[1].x) + (known[0].y - known[1].y) * (known[0].y - known[1].y) + (known[0].z - known[1].z) * (known[0].z - known[1].z)).toDouble()).takeIf { it > 0.002 } else null
        return EchoHardware(
            am.getProperty(AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND), am.getProperty(AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND),
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true", am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE),
            names, spacing, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
        )
    }
}

/** One frame for the screen. Every number says what kind it is; see the labels in the interface. */
data class SonarFrame(
    val fix: SonarTracker.Fix,
    /** The second microphone's motion profile when the phone gives two distinct ones; null on one mic. */
    val motion2: FloatArray?,
    /** Degrees left (-) or right (+) of straight ahead, an estimate; null without two mics or without a target. */
    val bearingDeg: Double?,
    /** -1 left, 0 centre, +1 right, when the mic spacing is unknown and only the sign means anything. */
    val lean: Int?,
    /** The received spectrum from 16 to 22 kHz, for the spectrogram. */
    val spectrum: FloatArray,
    /** True while the phone itself is moving fast: readings are paused, since the whole room seems to move. */
    val phoneMoving: Boolean,
    /** Milliseconds this phone took to process the frame. */
    val processMs: Float,
    val stereo: Boolean,
)

sealed interface SonarState {
    data object Off : SonarState
    data object Warming : SonarState
    data class Running(val frame: SonarFrame) : SonarState
    data class Failed(val why: String) : SonarState
}

/**
 * Runs the ranging sonar: repeats the chirp, records one or two microphones in step with it, and tracks what moves.
 * The same session and Stop as Echo's motion mode: one Echo, two ways of listening.
 */
class SonarEngine(private val context: Context, private val senses: Senses) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _state = MutableStateFlow<SonarState>(SonarState.Off)
    val state: StateFlow<SonarState> = _state
    val running get() = job?.isActive == true

    // The gyroscope gates readings: when the phone turns faster than about 17°/s, everything appears to move.
    @Volatile private var turnRate = 0f
    private val gyro = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) { turnRate = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) }
        override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
    }

    fun start(amplitude: Float = 0.5f) {
        if (running) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { _state.value = SonarState.Failed("Echo needs the microphone to hear the echo."); return }
        senses.begin(EchoEngine.SESSION, "echo", "Echo is measuring distance", sensitive = true) { stop() }
        runCatching { context.startForegroundService(Intent(context, EchoService::class.java)) }
        val sm = context.getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(gyro, it, SensorManager.SENSOR_DELAY_GAME) }
        _state.value = SonarState.Warming
        val hw = EchoProbe.read(context)
        job = scope.launch {
            val pcm = Sonar.periodPcm(amplitude)
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(Sonar.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
                    .also { it.write(pcm, 0, pcm.size); it.setLoopPoints(0, pcm.size, -1) }
            }.getOrNull()
            val (rec, channels) = openMic()
            if (track == null || rec == null) {
                track?.release(); rec?.release()
                _state.value = SonarState.Failed(if (track == null) "Couldn't play the chirp." else "Couldn't open the microphone. Another app may be using it.")
                cleanup(); return@launch
            }
            try {
                rec.startRecording(); track.play()
                val buf = ShortArray(Sonar.PERIOD * channels)
                val a = FloatArray(Sonar.PERIOD); val b = FloatArray(Sonar.PERIOD)
                val re = DoubleArray(Sonar.PERIOD); val im = DoubleArray(Sonar.PERIOD)
                val re2 = DoubleArray(Sonar.PERIOD); val im2 = DoubleArray(Sonar.PERIOD)
                val matched = Sonar.Matched(Sonar.period(amplitude))
                val t1 = SonarTracker(); val t2 = SonarTracker()
                var distinct: Boolean? = null
                var silent = 0
                var lastEvent = 0L
                var lastShown = 0L
                while (isActive) {
                    var got = 0
                    while (got < buf.size && isActive) { val n = rec.read(buf, got, buf.size - got); if (n <= 0) break; got += n }
                    if (got < buf.size) break
                    val t0 = System.nanoTime()
                    for (i in 0 until Sonar.PERIOD) { a[i] = buf[i * channels] / 32768f; if (channels == 2) b[i] = buf[i * 2 + 1] / 32768f }
                    // Two channels are only worth anything if they really are two microphones, not one copied twice.
                    if (channels == 2 && distinct == null) distinct = (0 until Sonar.PERIOD).sumOf { abs(a[it] - b[it]).toDouble() } > 1e-3 * Sonar.PERIOD
                    val now = System.currentTimeMillis()
                    matched.profile(a, re, im)
                    val fix = t1.feed(re, im, now)
                    val fix2 = if (distinct == true) { matched.profile(b, re2, im2); t2.feed(re2, im2, now) } else null
                    val moving = turnRate > 0.3f
                    var bearing: Double? = null; var lean: Int? = null
                    if (fix2 != null && !fix.rangeM.isNaN()) {
                        val at = (fix.rangeM / Sonar.M_PER_SAMPLE).toInt()
                        // The search must cover the spacing: 14 cm apart is up to 20 samples of difference.
                        val maxLag = hw.micSpacingM?.let { kotlin.math.ceil(it / (Sonar.SOUND / Sonar.RATE)).toInt() + 1 } ?: 4
                        val lag = Bearing.lag(fix.motion, fix2.motion, at, span = maxLag * 3, maxLag = maxLag)
                        if (hw.micSpacingM != null) bearing = Bearing.angle(lag, hw.micSpacingM) else lean = if (abs(lag) < 0.4) 0 else if (lag > 0) 1 else -1
                    }
                    // The mic must hear the chirp: a weak direct path for two seconds means sound off or filtered.
                    silent = if (fix.linkSnr < 6) silent + 1 else 0
                    if (silent > 48) { _state.value = SonarState.Failed("The microphone can't hear Echo's chirp. Turn the media volume up; some phones filter out sounds this high."); break }
                    if (!moving && !fix.rangeM.isNaN() && !fix.velocity.isNaN() && abs(fix.velocity) > 0.25 && now - lastEvent > 700) {
                        lastEvent = now
                        val kind = if (fix.velocity < 0) "approach" else "recede"
                        senses.publish(Signal("echo", kind, if (kind == "approach") "Something came closer, now %.0f cm away".format(fix.rangeM * 100) else "Something moved away, now %.0f cm".format(fix.rangeM * 100),
                            mapOf("distance_cm" to "%.0f".format(fix.rangeM * 100), "speed_m_s" to "%.2f".format(abs(fix.velocity)))))
                    }
                    val ms = (System.nanoTime() - t0) / 1e6f
                    if (now - lastShown >= 50) {
                        lastShown = now
                        _state.value = if (fix.linkSnr > 0 && t1Warm(fix)) SonarState.Running(SonarFrame(fix, fix2?.motion, bearing, lean, Fft.band(a, 16_000.0, 22_000.0), moving, ms, distinct == true)) else SonarState.Warming
                    }
                }
            } finally {
                runCatching { rec.stop() }; rec.release()
                runCatching { track.stop() }; track.release()
                cleanup()
                if (_state.value !is SonarState.Failed) _state.value = SonarState.Off
            }
        }
    }

    private var frames = 0
    private fun t1Warm(@Suppress("UNUSED_PARAMETER") f: SonarTracker.Fix) = ++frames > SonarTracker.WARMUP

    /** Stereo when the phone allows it (some give two real mics), the unprocessed source when it exists (no filtering of high sounds). */
    @Suppress("MissingPermission")
    private fun openMic(): Pair<AudioRecord?, Int> {
        // For two channels the camcorder source comes first: on Samsung and many others it is the one that really records
        // two separate microphones (top and bottom), where the others copy one mic into both channels.
        val stereo = listOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        val mono = listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        for (channels in listOf(2, 1)) for (src in if (channels == 2) stereo else mono) {
            val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val min = AudioRecord.getMinBufferSize(Sonar.RATE, mask, AudioFormat.ENCODING_PCM_16BIT).takeIf { it > 0 } ?: continue
            val r = runCatching { AudioRecord(src, Sonar.RATE, mask, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Sonar.PERIOD * channels * 2 * 4)) }.getOrNull()
            if (r?.state == AudioRecord.STATE_INITIALIZED) return r to channels
            r?.release()
        }
        return null to 1
    }

    private fun cleanup() {
        frames = 0
        runCatching { context.getSystemService(SensorManager::class.java).unregisterListener(gyro) }
        senses.end(EchoEngine.SESSION)
        runCatching { context.stopService(Intent(context, EchoService::class.java)) }
    }

    fun stop() { job?.cancel(); job = null; cleanup(); if (_state.value !is SonarState.Failed) _state.value = SonarState.Off }

    /** Battery current in milliamps where the phone reports it (many report 0 or nothing). */
    fun currentMa(): Double? = runCatching {
        val v = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (v == 0 || v == Int.MIN_VALUE) null else abs(v) / 1000.0
    }.getOrNull()
}
