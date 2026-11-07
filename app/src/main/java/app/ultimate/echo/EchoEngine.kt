package app.ultimate.echo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
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
import kotlin.coroutines.coroutineContext

/** Echo's state for the screen. */
sealed interface EchoState {
    data object Off : EchoState
    data object Calibrating : EchoState
    data class Sensing(val reading: EchoReading) : EchoState
    /** It could not start or the mic can't hear the tone; [why] says what to do. */
    data class Failed(val why: String) : EchoState
}

/**
 * Plays the tone and listens for its echo. Runs only while asked to, with a visible session that can be stopped from
 * anywhere; it records nothing and keeps no audio, only the readings, and only in memory.
 */
class EchoEngine(private val context: Context, private val senses: Senses) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    /** The microphone of the current run, so Stop can release a blocked read at once. */
    @Volatile private var liveRec: AudioRecord? = null
    private val _state = MutableStateFlow<EchoState>(EchoState.Off)
    val state: StateFlow<EchoState> = _state
    /** True while Echo ignores motion because someone is talking (on phones whose microphone is always processed). */
    val voicePaused = MutableStateFlow(false)

    /** Whether this phone says its speaker and microphone work near ultrasound. Many that say nothing still work. */
    fun support(): String? {
        val am = context.getSystemService(AudioManager::class.java)
        val spk = am.getProperty(AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND)
        val mic = am.getProperty(AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND)
        return if (spk == "false" || mic == "false") "This phone says its speaker or microphone doesn't reach the tone Echo uses, so it may not sense anything." else null
    }

    val running get() = job?.isActive == true

    fun start() {
        if (running) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { _state.value = EchoState.Failed("Echo needs the microphone to hear the echo."); return }
        senses.begin(SESSION, "echo", "Echo is listening for movement", sensitive = true) { stop() }
        runCatching { context.startForegroundService(android.content.Intent(context, EchoService::class.java)) }
        _state.value = EchoState.Calibrating
        val previous = job
        job = scope.launch {
            previous?.join() // the last run must let go of the microphone first
            val tone = EchoMath.tone()
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(EchoMath.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(tone.size * 2).build()
                    .also { it.write(tone, 0, tone.size); it.setLoopPoints(0, tone.size, -1); it.play() }
            }.getOrNull()
            val minBuf = AudioRecord.getMinBufferSize(EchoMath.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("MissingPermission")
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, EchoMath.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, EchoMath.FRAME * 4))
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                    ?: AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, EchoMath.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, EchoMath.FRAME * 4))
            }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            if (track == null || rec == null) {
                track?.release(); rec?.release()
                _state.value = EchoState.Failed(if (track == null) "Couldn't play the tone." else "Couldn't open the microphone. Another app may be using it.")
                senses.end(SESSION); runCatching { context.stopService(android.content.Intent(context, EchoService::class.java)) }; return@launch
            }
            try {
                liveRec = rec
                rec.startRecording()
                val pcm = ShortArray(EchoMath.FRAME); val frame = FloatArray(EchoMath.FRAME); val win = EchoMath.window()
                val det = EchoDetector()
                val gate = VoiceGate(); val rawFrame = FloatArray(EchoMath.FRAME)
                var deaf = 0
                while (isActive) {
                    var got = 0
                    while (got < pcm.size && isActive) { val n = rec.read(pcm, got, pcm.size - got); if (n <= 0) break; got += n }
                    if (got < pcm.size) break
                    for (i in pcm.indices) { rawFrame[i] = pcm[i] / 32768f; frame[i] = rawFrame[i] * win[i] }
                    val talking = gate.feed(rawFrame, System.currentTimeMillis())
                    if (voicePaused.value != talking) voicePaused.value = talking
                    val (reading, event) = det.feed(EchoMath.bands(frame), System.currentTimeMillis())
                    // If the tone never shows up, the volume is off or this phone filters it out: say so instead of sensing nothing.
                    deaf = if (reading.heard) 0 else deaf + 1
                    if (deaf > 60) { _state.value = EchoState.Failed("The microphone can't hear Echo's tone. Turn the media volume up, or this phone may filter out high sounds."); break }
                    _state.value = if (det.calibrating) EchoState.Calibrating else EchoState.Sensing(reading)
                    event?.takeIf { !talking }?.let { kind ->
                        senses.publish(Signal("echo", kind, if (kind == "approach") "Something came closer to the phone" else "Something moved away from the phone", mapOf("strength" to "%.2f".format(reading.motion))))
                    }
                }
            } finally {
                runCatching { rec.stop() }; rec.release()
                runCatching { track.stop() }; track.release()
                // Only the current run tidies up: an older run finishing late must not stop the one that replaced it.
                if (job == null || job === coroutineContext[Job]) {
                    senses.end(SESSION)
                    runCatching { context.stopService(android.content.Intent(context, EchoService::class.java)) }
                    if (_state.value !is EchoState.Failed) _state.value = EchoState.Off
                }
            }
        }
    }

    fun stop() { liveRec?.let { runCatching { it.stop() } }; liveRec = null; job?.cancel(); job = null; senses.end(SESSION); runCatching { context.stopService(android.content.Intent(context, EchoService::class.java)) }; if (_state.value !is EchoState.Failed) _state.value = EchoState.Off }

    companion object { const val SESSION = "echo" }
}
