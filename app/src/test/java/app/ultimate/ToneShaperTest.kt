package app.ultimate

import app.ultimate.audio.model.AudioPlanner
import app.ultimate.audio.model.AudioSettings
import app.ultimate.audio.model.AudioSource
import app.ultimate.audio.model.Biquad
import app.ultimate.audio.model.ToneShaper
import app.ultimate.audio.model.OutFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ToneShaperTest {
    private val rate = 44100

    private fun sine(hz: Double, frames: Int, amp: Double = 10_000.0) = ShortArray(frames) { (amp * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    /** Peak over the second half, once the filter has settled. */
    private fun peak(s: ShortArray) = s.drop(s.size / 2).maxOf { abs(it.toInt()) }

    private fun shaped(hz: Double, bass: Float = 0f, treble: Float = 0f, cut: Int = 0): Int {
        val s = sine(hz, rate)
        ToneShaper(rate, 1, bass, treble, cut, 0, 0, s.size.toLong()).process(s, s.size)
        return peak(s)
    }

    @Test fun highCutAttenuatesAboveAndKeepsBelow() {
        assertTrue(shaped(15_000.0, cut = 8000) < 2500)
        assertTrue(abs(shaped(500.0, cut = 8000) - 10_000) < 300)
    }

    @Test fun shelvesBoostTheirOwnBand() {
        // +6 dB is about twice the amplitude.
        assertTrue(shaped(40.0, bass = 6f) in 18_000..21_000)
        assertTrue(abs(shaped(40.0, treble = 6f) - 10_000) < 600)
        assertTrue(shaped(14_000.0, treble = 6f) in 18_000..21_000)
        assertTrue(shaped(40.0, bass = -12f) < 3500)
    }

    @Test fun fadesRampFromAndToSilence() {
        val s = ShortArray(rate) { 10_000 }
        ToneShaper(rate, 1, 0f, 0f, 0, 4410, 4410, s.size.toLong()).process(s, s.size)
        assertEquals(0, s[0].toInt())
        assertTrue(s[2205] in 4500..5500)
        assertEquals(10_000, s[rate / 2].toInt())
        assertTrue(abs(s.last().toInt()) < 10)
    }

    @Test fun gainClipsInsteadOfWrapping() {
        val s = shortArrayOf(20_000, -20_000)
        ToneShaper(rate, 1, 0f, 0f, 0, 0, 0, 2, gain = 4f).process(s, 2)
        assertEquals(Short.MAX_VALUE, s[0])
        assertTrue(s[1] <= -32767)
    }

    @Test fun pitchFactorIsTwelveToneEqual() {
        assertEquals(2f, AudioPlanner.pitchFactor(12), 1e-4f)
        assertEquals(1f, AudioPlanner.pitchFactor(0), 0f)
        assertEquals(0.7937f, AudioPlanner.pitchFactor(-4), 1e-3f)
    }

    @Test fun anySoundEditStopsAacCopy() {
        val aac = AudioSource(durationMs = 1000, mime = "audio/mp4a-latm", sampleRate = 44100, channels = 2, bitrate = 128_000)
        assertTrue(AudioPlanner.copies(aac, AudioSettings(format = OutFormat.M4a)))
        assertFalse(AudioPlanner.copies(aac, AudioSettings(format = OutFormat.M4a, pitch = 1)))
        assertFalse(AudioPlanner.copies(aac, AudioSettings(format = OutFormat.M4a, normalize = true)))
    }

    @Test fun biquadLowPassPassesDc() {
        val f = Biquad.lowPass(rate, 1000.0)
        var y = 0.0
        repeat(2000) { y = f.next(1.0) }
        assertEquals(1.0, y, 1e-6)
    }
}
