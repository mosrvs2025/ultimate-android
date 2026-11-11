package app.ultimate.studio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Finding the pauses in speech so they can be cut out in one tap. The decoding is on the device; the deciding is plain maths on a
 * list of loudness readings, one per [WINDOW_MS], so it is tested.
 */
object Silence {
    const val WINDOW_MS = 50

    /**
     * The stretches to remove, in milliseconds from the start of the scanned audio. [db] is the loudness of each window in dB
     * below full scale. A stretch counts when it stays under [thresholdDb] for at least [minSilentMs]; [keepMs] is left on each side
     * so words aren't clipped and cuts don't sound abrupt.
     */
    fun find(db: FloatArray, thresholdDb: Float = -42f, minSilentMs: Long = 450, keepMs: Long = 120): List<LongRange> {
        val out = ArrayList<LongRange>()
        var start = -1
        fun close(endWindow: Int) {
            if (start < 0) return
            val a = start.toLong() * WINDOW_MS; val b = endWindow.toLong() * WINDOW_MS
            if (b - a >= minSilentMs) { val from = a + keepMs; val to = b - keepMs; if (to - from >= 100) out += from until to }
            start = -1
        }
        db.forEachIndexed { i, v -> if (v < thresholdDb) { if (start < 0) start = i } else close(i) }
        close(db.size)
        return out
    }

    /** A sensible threshold for this recording: a little above its own noise floor, never louder than -30 dB or quieter than -55 dB. */
    fun thresholdFor(db: FloatArray): Float {
        if (db.isEmpty()) return -42f
        val sorted = db.sorted()
        val floor = sorted[(sorted.size * 0.1).toInt().coerceIn(0, sorted.size - 1)]
        return (floor + 9f).coerceIn(-55f, -30f)
    }

    /** Decodes [startMs]..[endMs] of the audio in [uri] and returns one loudness reading (dB) per [WINDOW_MS]. Null if it can't be read. */
    fun scan(context: Context, uri: Uri, startMs: Long, endMs: Long): FloatArray? = runCatching {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { it.configure(format, null, null, 0); it.start() }
            val info = MediaCodec.BufferInfo()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val readings = ArrayList<Float>()
            var sumSq = 0.0; var inWindow = 0L; var windowSamples = (rate * WINDOW_MS / 1000L).coerceAtLeast(1)
            var inputDone = false; var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime > endMs * 1000) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { val f = codec.outputFormat; rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT); windowSamples = (rate * WINDOW_MS / 1000L).coerceAtLeast(1) }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                        // Only what lies inside the clip's range is measured (decoding starts at a sync point before it).
                        val startUs = info.presentationTimeUs
                        var frame = 0
                        while (buf.remaining() >= channels) {
                            var mono = 0.0; repeat(channels) { mono += buf.get() / 32768.0 }; mono /= channels
                            val tMs = startUs / 1000 + frame * 1000L / rate; frame++
                            if (tMs < startMs) continue
                            sumSq += mono * mono; inWindow++
                            if (inWindow >= windowSamples) { readings += (20 * log10(sqrt(sumSq / inWindow).coerceAtLeast(1e-6))).toFloat(); sumSq = 0.0; inWindow = 0 }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val want = ((endMs - startMs) / WINDOW_MS).toInt()
            readings.take(want).toFloatArray().takeIf { it.isNotEmpty() }
        } finally { runCatching { codec?.stop() }; runCatching { codec?.release() }; ex.release() }
    }.getOrNull()
}
