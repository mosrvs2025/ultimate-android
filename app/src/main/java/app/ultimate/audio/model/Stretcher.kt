package app.ultimate.audio.model

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Changes the speed and, separately, the pitch of interleaved 16-bit PCM (Sonic time-stretching). */
@androidx.annotation.OptIn(UnstableApi::class)
class Stretcher(rate: Int, channels: Int, speed: Float, pitch: Float = 1f) {
    private val sonic = SonicAudioProcessor().apply {
        setSpeed(speed)
        setPitch(pitch)
        configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        flush()
    }
    private var input: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var out = ShortArray(0)

    /** Returns the stretched samples in a reused buffer and how many are valid. */
    fun process(samples: ShortArray, count: Int): Pair<ShortArray, Int> {
        if (input.capacity() < count * 2) input = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        input.clear()
        input.asShortBuffer().put(samples, 0, count)
        input.limit(count * 2)
        sonic.queueInput(input)
        return drain()
    }

    /** Flushes what Sonic still holds at the end of the stream. */
    fun finish(): Pair<ShortArray, Int> {
        sonic.queueEndOfStream()
        return drain()
    }

    private fun drain(): Pair<ShortArray, Int> {
        val bytes = sonic.output.order(ByteOrder.LITTLE_ENDIAN)
        val n = bytes.remaining() / 2
        if (out.size < n) out = ShortArray(n)
        bytes.asShortBuffer().get(out, 0, n)
        bytes.position(bytes.limit())
        return out to n
    }
}
