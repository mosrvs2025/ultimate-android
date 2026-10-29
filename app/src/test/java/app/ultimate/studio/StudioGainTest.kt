package app.ultimate.studio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class StudioGainTest {
    @Test fun pcm16GainDoesNotWrapAtTheRails() {
        val processor = GainProcessor(2f)
        processor.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT)); processor.flush()
        val input = ByteBuffer.allocateDirect(6).order(ByteOrder.nativeOrder()).putShort(20000).putShort(-20000).putShort(1000)
        input.flip(); processor.queueInput(input)
        val out = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(32767, out.short.toInt()); assertEquals(-32768, out.short.toInt()); assertEquals(2000, out.short.toInt())
    }
    @Test fun floatGainIsAppliedPerInterleavedSample() {
        val processor = GainProcessor(0.5f)
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT)); processor.flush()
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).putFloat(0.8f).putFloat(-0.4f)
        input.flip(); processor.queueInput(input)
        val out = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(0.4f, out.float, 0.00001f); assertEquals(-0.2f, out.float, 0.00001f)
    }
}
