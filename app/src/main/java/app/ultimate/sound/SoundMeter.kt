package app.ultimate.sound

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Foreground-screen capture only: deliberately never registered with the data-sync WorkService.
data class MeterState(
    val running: Boolean = false,
    val currentDb: Double = -120.0,
    val averageDb: Double = -120.0,
    val minDb: Double = -120.0,
    val maxDb: Double = -120.0,
    val peakDb: Double = -120.0,
    val elapsedMs: Long = 0,
    val clipped: Long = 0,
    val graph: List<SoundPoint> = emptyList(),
    val offset: Double? = null,
    val route: String = "",
    val message: String? = null,
    val finished: SoundSession? = null,
)

class SoundMeter(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(MeterState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var stopReason = "Stopped"
    @Volatile private var stopRequested = false

    /**
     * Called on the UI thread. Repeated starts cannot create overlapping microphone owners.
     *
     * RECORD_AUDIO is declared only in editions that include this tool (see src/ultimate/AndroidManifest.xml) and is checked at
     * runtime below before any recording starts, so lint's MissingPermission is suppressed for the other editions, where R8 strips this code.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun start(offset: Double?) {
        if (job?.isCompleted == false) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutable.value = mutable.value.copy(message = "Microphone access is required. Enable it in Android app settings if permission was blocked.")
            return
        }
        stopReason = "Stopped"
        stopRequested = false
        mutable.value = MeterState(running = true, offset = offset)
        job = scope.launch {
            var recorder: AudioRecord? = null
            val total = SoundAccumulator()
            var interval = SoundAccumulator()
            val points = ArrayList<SoundPoint>()
            val started = System.currentTimeMillis()
            var rate = 48000
            var route = ""
            var lastRoute: Int? = null
            var error: String? = null
            fun flush() {
                if (interval.samples == 0L) return
                points += SoundPoint(total.samples * 1000 / rate, interval.averageDb, SoundMath.db(interval.peakPower), interval.clipped, interval.samples)
                interval = SoundAccumulator()
            }
            try {
                if (stopRequested) return@launch
                val manager = context.getSystemService(AudioManager::class.java)
                val source = if (manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                    MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
                val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBuffer > 0) { "48 kHz microphone capture is unavailable on this device" }
                val input = AudioRecord.Builder().setAudioSource(source)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minBuffer * 2, rate / 5 * 2)).build()
                recorder = input
                check(input.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
                if (stopRequested) return@launch
                input.startRecording()
                check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
                rate = input.sampleRate
                val buffer = ShortArray(rate / 10)
                var emptySince = android.os.SystemClock.elapsedRealtime()
                while (isActive && !stopRequested) {
                    check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission was revoked" }
                    check(!manager.isMicrophoneMute) { "Microphone is muted" }
                    check(input.activeRecordingConfiguration?.isClientSilenced != true) { "Another app or Android silenced the microphone" }
                    val device = input.routedDevice
                    if (device != null) {
                        check(lastRoute == null || lastRoute == device.id) { "Microphone route changed; start a new session" }
                        lastRoute = device.id
                        route = "${device.productName} · ${rate} Hz · ${if (source == MediaRecorder.AudioSource.UNPROCESSED) "unprocessed requested" else "MIC (processing may apply)"}"
                    }
                    val count = input.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "Microphone read failed ($count)" }
                    if (count == 0) {
                        check(android.os.SystemClock.elapsedRealtime() - emptySince < 3000) { "Microphone stopped delivering samples" }
                        delay(10)
                        continue
                    }
                    emptySince = android.os.SystemClock.elapsedRealtime()
                    val block = SoundMath.measure(buffer, count)
                    total.add(block); interval.add(block)
                    if (interval.samples >= rate) flush()
                    mutable.value = MeterState(true, block.rmsDb, total.averageDb, total.minDb, total.maxDb,
                        SoundMath.db(total.peakPower), total.samples * 1000 / rate, total.clipped, points.takeLast(120), offset, route)
                    delay(10)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stop reason is supplied by the lifecycle or the Stop button.
            } catch (e: Exception) {
                error = e.message ?: "Microphone capture failed"
            } finally {
                recorder?.let { input ->
                    runCatching { input.stop() }
                    runCatching { input.release() }.onFailure { error = "Microphone release failed: ${it.message}" }
                }
                flush()
                val reason = error ?: stopReason
                val result = if (total.samples > 0) SoundSession(UUID.randomUUID().toString(), "Sound session", started,
                    total.samples * 1000 / rate, total.averageDb, total.minDb, total.maxDb, SoundMath.db(total.peakPower),
                    total.clipped, offset, route.ifBlank { "Microphone · $rate Hz" }, reason, points.toList()) else null
                mutable.value = mutable.value.copy(running = false, graph = points.takeLast(120), message = reason, finished = result)
            }
        }
    }
    fun stop(reason: String = "Stopped") {
        stopReason = reason
        stopRequested = true
    }
}
