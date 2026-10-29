package app.ultimate.send

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** ADTS headers make raw AAC frames a stream any browser's audio element can play as it arrives. */
object Adts {
    private val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** The 7-byte ADTS header for an AAC-LC frame of [payload] bytes. */
    fun header(payload: Int, sampleRate: Int, channels: Int): ByteArray {
        val len = payload + 7
        val freq = rates.indexOf(sampleRate).takeIf { it >= 0 } ?: 4
        val profile = 2 // AAC LC
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            (((profile - 1) shl 6) or (freq shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (len shr 11)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}

/**
 * The phone's microphone, live to every browser listening: recorded, encoded to AAC and sent frame by frame. Runs only while
 * switched on in Send to computer with the app open, the same rule as the camera.
 */
class CompanionMic {
    private val listeners = CopyOnWriteArrayList<OutputStream>()
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var level = 0f
        private set

    fun listen(out: OutputStream) { listeners += out }
    fun unlisten(out: OutputStream) { listeners -= out }
    val active get() = running

    @SuppressLint("MissingPermission") // asked for on the phone before this starts
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "companion-mic").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500); thread = null
        listeners.forEach { runCatching { it.close() } }; listeners.clear()
        level = 0f
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val rate = 44100; val channels = 1
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192)) }.getOrNull() ?: run { running = false; return }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start(); record.startRecording()
            val pcm = ByteArray(4096); val info = MediaCodec.BufferInfo()
            while (running) {
                val n = record.read(pcm, 0, pcm.size)
                if (n > 0) {
                    var peak = 0
                    for (i in 0 until n - 1 step 2) peak = maxOf(peak, kotlin.math.abs((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)))
                    level = peak / 32768f
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) { codec.getInputBuffer(inIdx)!!.apply { clear(); put(pcm, 0, n) }; codec.queueInputBuffer(inIdx, 0, n, System.nanoTime() / 1000, 0) }
                }
                while (true) {
                    val outIdx = codec.dequeueOutputBuffer(info, 0)
                    if (outIdx < 0) break
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val frame = ByteArray(info.size); codec.getOutputBuffer(outIdx)!!.apply { position(info.offset); get(frame) }
                        val packet = Adts.header(frame.size, rate, channels) + frame
                        listeners.forEach { o -> runCatching { o.write(packet); o.flush() }.onFailure { listeners -= o } }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
        } finally {
            runCatching { record.stop() }; record.release()
            runCatching { codec.stop() }; codec.release()
        }
    }
}
