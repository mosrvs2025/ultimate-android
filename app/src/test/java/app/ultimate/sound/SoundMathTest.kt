package app.ultimate.sound

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SoundMathTest {
    @Test fun silenceIsFiniteAndHasNoClipping() {
        val b = SoundMath.measure(ShortArray(100), 100)
        assertEquals(-120.0, b.rmsDb, 0.00001)
        assertEquals(0L, b.clipped)
    }
    @Test fun doubledAmplitudeAddsSixDecibels() {
        val low = SoundMath.measure(ShortArray(4800) { (4000 * sin(2 * PI * it / 48)).toInt().toShort() }, 4800)
        val high = SoundMath.measure(ShortArray(4800) { (8000 * sin(2 * PI * it / 48)).toInt().toShort() }, 4800)
        assertEquals(6.0206, high.rmsDb - low.rmsDb, 0.002)
        assertEquals(high.rmsDb + 3.0103, high.peakDb, 0.002)
    }
    @Test fun unevenReadsWeightSamplesRatherThanDecibels() {
        val acc = SoundAccumulator()
        acc.add(SoundMath.measure(ShortArray(100) { 1000 }, 100))
        acc.add(SoundMath.measure(ShortArray(900) { 10000 }, 900))
        val expected = 10 * kotlin.math.log10((100 * (1000.0 / 32768) * (1000.0 / 32768) + 900 * (10000.0 / 32768) * (10000.0 / 32768)) / 1000)
        assertEquals(expected, acc.averageDb, 1e-9)
        assertEquals(1000L, acc.samples)
    }
    @Test fun bothPcmRailsCountAsClippingWithoutOverflow() {
        val b = SoundMath.measure(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, 0), 3)
        assertEquals(2L, b.clipped)
        assertEquals(0.0, b.peakDb, 1e-9)
    }
    @Test fun partialBufferIgnoresStaleTail() {
        val b = SoundMath.measure(shortArrayOf(1000, 1000, Short.MAX_VALUE), 2)
        assertEquals(0L, b.clipped)
        assertEquals(SoundMath.measure(shortArrayOf(1000, 1000), 2), b)
    }
    @Test fun identicalAudioIsDeterministic() {
        val pcm = ShortArray(100) { 32000 }
        assertEquals(SoundMath.measure(pcm, 100), SoundMath.measure(pcm, 100))
    }
}
