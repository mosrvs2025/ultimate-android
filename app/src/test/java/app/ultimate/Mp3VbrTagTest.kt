package app.ultimate

import app.ultimate.audio.model.Mp3VbrTag
import de.sciss.jump3r.lowlevel.LameEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import javax.sound.sampled.AudioFormat
import kotlin.math.PI
import kotlin.math.sin

/** The Xing header for variable-bitrate MP3s, checked against the real encoder the app uses. */
class Mp3VbrTagTest {
    /** [seconds] of sound whose loudness changes a lot (tones, noise bursts, near-silence), so the encoder's bit rate really varies. */
    private fun pcm(rate: Int, channels: Int, seconds: Int): ByteArray {
        val rnd = Random(5)
        val n = rate * seconds
        val out = ByteArray(n * channels * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val phase = (t % 4.0)
            val level = when { phase < 1.0 -> 0.5; phase < 2.0 -> 0.02; phase < 3.0 -> 0.35; else -> 0.0 }
            val tone = sin(2 * PI * (220 + 90 * (t % 3)) * t) * level + (rnd.nextGaussian() * 0.05 * level)
            for (c in 0 until channels) {
                val v = (tone * (if (c == 0) 1.0 else 0.8) * 20_000).toInt().coerceIn(-32768, 32767)
                val o = (i * channels + c) * 2
                out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte()
            }
        }
        return out
    }

    private class Encoded(val audio: ByteArray, val tracker: Mp3VbrTag.Tracker)

    /** Encodes with the app's own call (variable bit rate, level [vbr]) and feeds the tracker in pieces of [chunk] bytes at most. */
    private fun encode(rate: Int, channels: Int, seconds: Int, vbr: Int, chunk: Int): Encoded {
        val enc = LameEncoder(AudioFormat(rate.toFloat(), 16, channels, true, false), 128, if (channels == 1) LameEncoder.CHANNEL_MODE_MONO else LameEncoder.CHANNEL_MODE_AUTO, vbr, true)
        val src = pcm(rate, channels, seconds)
        val outBuf = ByteArray(enc.mP3BufferSize)
        val audio = ByteArrayOutputStream()
        val tracker = Mp3VbrTag.Tracker()
        val rnd = Random(9)
        fun take(n: Int) {
            if (n <= 0) return
            audio.write(outBuf, 0, n)
            var o = 0
            // Pieces of any size, as an encoder hands them over: the tracker must not care where they split.
            while (o < n) { val k = minOf(n - o, 1 + rnd.nextInt(chunk)); tracker.feed(outBuf, o, k); o += k }
        }
        var i = 0
        val step = enc.pcmBufferSize
        while (i < src.size) { val k = minOf(step, src.size - i); take(enc.encodeBuffer(src, i, k, outBuf)); i += k }
        take(enc.encodeFinish(outBuf))
        enc.close()
        return Encoded(audio.toByteArray(), tracker)
    }

    /** Every frame of [b] from [from], by walking header to header. Returns the frame count, or -1 if the walk doesn't land exactly on the end. */
    private fun walk(b: ByteArray, from: Int): Int {
        var p = from; var n = 0
        while (p < b.size) { val h = Mp3VbrTag.parse(b, p) ?: return -1; p += h.frameBytes; n++ }
        return if (p == b.size) n else -1
    }

    private fun assemble(e: Encoded): ByteArray {
        val first = e.tracker.placeholder()
        val file = first + e.audio
        val tag = e.tracker.tag(file.size.toLong())
        assertEquals("the finished tag has the same length as the blank frame, so it can overwrite it in place", first.size, tag.size)
        System.arraycopy(tag, 0, file, 0, tag.size)
        return file
    }

    private fun check(rate: Int, channels: Int, vbr: Int, name: String) {
        val seconds = 24
        val e = encode(rate, channels, seconds, vbr, chunk = 700)
        val whole = encode(rate, channels, seconds, vbr, chunk = 1_000_000)
        assertEquals("$name: where the output was split does not matter", whole.tracker.frameCount, e.tracker.frameCount)
        assertEquals(walk(e.audio, 0).toLong(), e.tracker.frameCount)
        assertEquals(e.audio.size.toLong(), e.tracker.audioBytes)

        val file = assemble(e)
        val tagFrame = Mp3VbrTag.parse(file, 0)!!
        val read = Mp3VbrTag.read(file.copyOf(tagFrame.frameBytes))
        assertNotNull("$name: the first frame carries a Xing tag", read)
        assertEquals("$name: frames in the tag are the audio frames", e.tracker.frameCount, read!!.frames)
        assertEquals("$name: bytes in the tag are the whole file", file.size.toLong(), read.bytes)
        assertEquals("$name: the file is the tag frame then exactly the audio frames", e.tracker.frameCount.toInt(), walk(file, tagFrame.frameBytes))
        // The table starts at the beginning, never goes backwards, and ends near the end.
        assertEquals(0, read.toc.first())
        for (i in 1 until read.toc.size) assertTrue("$name: table rises ($i)", read.toc[i] >= read.toc[i - 1])
        assertTrue("$name: last entry is near the end, was ${read.toc.last()}", read.toc.last() in 230..255)
        // Length follows from the frame count: within two frames of what went in.
        val f = e.tracker.first!!
        val seconds2 = read.frames * f.samples.toDouble() / f.rate
        assertEquals("$name: length", seconds.toDouble(), seconds2, 2.0 * f.samples / f.rate + 0.1)
        // The bit rate varied, which is the whole reason for the tag.
        val rates = HashSet<Int>(); var p = tagFrame.frameBytes
        while (p < file.size) { val h = Mp3VbrTag.parse(file, p)!!; rates += h.kbps; p += h.frameBytes }
        if (vbr >= 4) assertTrue("$name: more than one bit rate, got $rates", rates.size > 1)
        File(System.getProperty("java.io.tmpdir"), "tagtest-$name.mp3").writeBytes(file)
    }

    @Test fun stereo48k() = check(48_000, 2, 5, "stereo48k")
    @Test fun mono44k() = check(44_100, 1, 6, "mono44k")
    @Test fun mono24kIsMpeg2() = check(24_000, 1, 5, "mono24k")
    @Test fun mono16kIsMpeg2() = check(16_000, 1, 7, "mono16k")
    @Test fun stereo32k() = check(32_000, 2, 8, "stereo32k")

    @Test fun theTagTellsAFixedRateFileApartAndRefusesJunk() {
        assertNull("not a frame", Mp3VbrTag.parse(ByteArray(8), 0))
        assertNull("too short", Mp3VbrTag.parse(ByteArray(2), 0))
        assertNull("a frame with no tag has nothing to read", Mp3VbrTag.read(encode(44_100, 2, 2, 5, 300).audio))
        assertFalse(Mp3VbrTag.Tracker().frameCount > 0)
    }

    @Test fun framesThatArriveInTinyPiecesAreStillCounted() {
        val e = encode(44_100, 2, 3, 5, chunk = 3)
        assertEquals(walk(e.audio, 0).toLong(), e.tracker.frameCount)
    }
}
