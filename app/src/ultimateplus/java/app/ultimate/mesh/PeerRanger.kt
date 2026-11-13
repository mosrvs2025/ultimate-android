package app.ultimate.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * One phone's half of a sound-ranging measurement: record for [PeerRange.Plan.RECORD_MS], play this phone's chirp at its
 * planned moment inside that recording, then find both chirps. The positions come from the recording itself, so how
 * long Android takes to start the speaker never matters.
 */
class PeerRanger(context: Context) {
    private val app = context.applicationContext

    sealed interface Outcome {
        data class Done(val side: PeerRange.Side) : Outcome
        /** The recording was heard or not; [why] is for people. */
        data class Failed(val why: String) : Outcome
    }

    fun supportNote(): String? {
        val am = app.getSystemService(android.media.AudioManager::class.java)
        val spk = am.getProperty(android.media.AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND)
        val mic = am.getProperty(android.media.AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND)
        return if (spk == "false" || mic == "false") "This phone says its speaker or microphone does not reach the pitch used." else null
    }

    suspend fun run(role: PeerRange.Role): Outcome = withContext(Dispatchers.IO) {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@withContext Outcome.Failed("the microphone is not allowed for Ultimate on this phone")
        val total = PeerRange.Plan.samples(PeerRange.Plan.RECORD_MS)
        val minBuf = AudioRecord.getMinBufferSize(PeerRange.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, PeerRange.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, total * 2))
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?: AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, PeerRange.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, total * 2))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?: return@withContext Outcome.Failed("the microphone could not be opened; another app may be using it")
        val pcm = PeerRange.chirp(role).let { c -> ShortArray(c.size) { (c[it] * Short.MAX_VALUE).toInt().toShort() } }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(PeerRange.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
                .also { it.write(pcm, 0, pcm.size) }
        }.getOrNull() ?: run { rec.release(); return@withContext Outcome.Failed("the speaker could not be opened") }

        val samples = ShortArray(total)
        try {
            rec.startRecording()
            val at = if (role == PeerRange.Role.Caller) PeerRange.Plan.CALLER_AT_MS else PeerRange.Plan.CALLEE_AT_MS
            coroutineScope {
                launch { delay(at.toLong()); track.play() }
                launch {
                    var got = 0
                    while (got < total) { val n = rec.read(samples, got, total - got); if (n <= 0) break; got += n }
                }
            }
        } finally {
            runCatching { rec.stop() }; rec.release(); runCatching { track.stop() }; track.release()
        }
        // Android silences a background app's microphone without an error: an all-zero recording means exactly that.
        if (samples.all { abs(it.toInt()) < 2 }) return@withContext Outcome.Failed("the microphone returned silence: Android may be blocking it while the app is in the background, or something else holds it")
        val f = FloatArray(samples.size) { samples[it] / 32768f }
        PeerRange.measure(f, role)?.let { Outcome.Done(it) } ?: Outcome.Failed("the recording was too short to analyse")
    }
}
