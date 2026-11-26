package app.ultimate

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ultimate.audio.engine.AudioConverter
import app.ultimate.audio.engine.ConvertInput
import app.ultimate.audio.engine.OpusSupport
import app.ultimate.audio.model.AudioPlanner
import app.ultimate.audio.model.AudioSettings
import app.ultimate.audio.model.AudioSource
import app.ultimate.audio.model.OutFormat
import app.ultimate.core.JobLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Opus made, read and decoded by a real Android runtime (the CI emulator), because Robolectric has no media codecs. It makes a few
 * seconds of speech-like sound, runs the app's own converter to an Opus file, and checks that the file really is Ogg Opus, has the
 * right length, decodes back to sound with the pause still in it, and that the preview path makes one too.
 */
@RunWith(AndroidJUnit4::class)
class OpusRoundTripTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val rate = 48_000
    private val seconds = 4

    /** Four syllables a second of a voiced tone (a pitch with two harmonics) with a pause from 2.0 to 2.5 s, and a whisper of noise. */
    private fun writeWav(f: File) {
        val n = rate * seconds
        val pcm = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        val rnd = Random(3)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = if (t in 2.0..2.5) 0.0 else 0.5 + 0.5 * sin(2 * PI * 4 * t)
            val voiced = sin(2 * PI * 140 * t) * 0.6 + sin(2 * PI * 280 * t) * 0.3 + sin(2 * PI * 420 * t) * 0.2
            pcm.putShort((env * voiced * 9000 + rnd.nextGaussian() * 30).toInt().coerceIn(-32768, 32767).toShort())
        }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(n * 2)
        f.outputStream().use { it.write(h.array()); it.write(pcm.array()) }
    }

    private fun rms(pcm: ShortArray, fromS: Double, toS: Double, rate: Int = 16_000): Double {
        val a = (fromS * rate).toInt(); val b = minOf((toS * rate).toInt(), pcm.size)
        var sum = 0.0
        for (i in a until b) sum += pcm[i].toDouble() * pcm[i]
        return sqrt(sum / (b - a).coerceAtLeast(1))
    }

    private fun input(dir: File): ConvertInput {
        val wav = File(dir, "speechlike.wav").also { writeWav(it) }
        return ConvertInput(Uri.fromFile(wav), "speechlike.wav", AudioSource(seconds * 1000L, "audio/raw", rate, 1, rate * 16))
    }

    @Test fun opusIsMadeReadAndDecodedOnThisPhone() = runBlocking {
        Log.i("OpusRoundTrip", "Opus encoder available: ${OpusSupport.available}")
        assertTrue("this phone has an Opus encoder (c2.android.opus.encoder ships with Android 10 and later)", OpusSupport.available)
        val dir = File(context.cacheDir, "opus-test").apply { mkdirs() }
        val converter = AudioConverter(context)
        val out = converter.convert(listOf(input(dir)), AudioSettings(format = OutFormat.Opus, kbps = 24, mono = true), JobLog()) { _, _ -> }
        Log.i("OpusRoundTrip", "made ${out.displayName}: ${out.bytes} bytes, ${out.durationMs} ms, ${out.sampleRate} Hz, ${out.channels} ch")
        try {
            assertTrue("named like an Opus file: ${out.displayName}", out.displayName.endsWith(".opus") || out.displayName.endsWith(".ogg"))
            assertEquals(OutFormat.Opus, out.format)
            assertTrue("about 12 KB for 4 s at 24 kbps, was ${out.bytes}", out.bytes in 4_000..30_000)

            // It is an Ogg file holding Opus: starts with OggS and carries the OpusHead header.
            val head = context.contentResolver.openInputStream(out.uri)!!.use { it.readNBytes(256) }
            assertEquals("OggS", String(head, 0, 4, Charsets.US_ASCII))
            assertTrue("an OpusHead packet in the first page", String(head, Charsets.ISO_8859_1).contains("OpusHead"))

            // A player's view of it: one Opus track of the right length.
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, out.uri, null)
                val f = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME) == "audio/opus" }
                assertEquals(1, f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                val secs = f.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
                assertEquals("length of the sound", seconds.toDouble(), secs, 0.25)
            } finally { ex.release() }

            // Decoded back: the speech is there and the pause is quiet.
            val pcm = converter.speechPcm(out.uri, AudioSource(out.durationMs, AudioSource.OPUS, 48_000, 1, 24_000), 0, 0)
            assertEquals("decoded length", seconds.toDouble(), pcm.size / 16_000.0, 0.3)
            val speech = rms(pcm, 0.4, 1.4); val pause = rms(pcm, 2.1, 2.4)
            Log.i("OpusRoundTrip", "decoded: speech rms $speech, pause rms $pause")
            assertTrue("speech comes back (rms $speech)", speech > 800)
            assertTrue("and the pause stays quiet ($pause against $speech)", pause * 4 < speech)
        } finally {
            context.contentResolver.delete(out.uri, null, null)
        }
    }

    @Test fun thePreviewIsMadeWithTheRealCodec() = runBlocking {
        assertTrue(OpusSupport.available)
        val dir = File(context.cacheDir, "opus-test").apply { mkdirs() }
        val s = AudioSettings(format = OutFormat.Opus, kbps = 24, mono = true)
        val inp = input(dir)
        assertEquals(OutFormat.Opus, AudioPlanner.previewFormat(inp.source, s))
        val file = File(dir, "preview.opus")
        val (f, peaks) = AudioConverter(context).preview(inp, s, file, lengthMs = 3_000, encoded = true)
        assertTrue("a file was written, ${f.length()} bytes", f.length() > 1_000)
        assertEquals("OggS", f.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) })
        assertNotNull(peaks); assertTrue("the waveform has peaks", peaks.isNotEmpty())
    }
}
