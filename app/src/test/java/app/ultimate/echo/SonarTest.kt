package app.ultimate.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SonarTest {
    private val tx = Sonar.period()
    private val matched = Sonar.Matched()

    /** What the mic hears in one period: each path is the chirp delayed (circularly, as the chirp repeats) and scaled. */
    private fun receive(paths: List<Pair<Int, Double>>, seed: Int, noise: Double = 0.002): FloatArray {
        val r = java.util.Random(seed.toLong())
        return FloatArray(Sonar.PERIOD) { i ->
            (paths.sumOf { (d, a) -> tx[Math.floorMod(i - d, Sonar.PERIOD)] * a } + noise * r.nextGaussian()).toFloat()
        }
    }

    private fun samples(m: Double) = Math.round(m / Sonar.M_PER_SAMPLE).toInt()

    private fun run(t: SonarTracker, paths: List<Pair<Int, Double>>, frame: Int): SonarTracker.Fix {
        val re = DoubleArray(Sonar.PERIOD); val im = DoubleArray(Sonar.PERIOD)
        matched.profile(receive(paths, frame), re, im)
        return t.feed(re, im, frame * 43L)
    }

    // The direct path arrives at an arbitrary delay (the phone's audio latency), here 700 samples; a table 20 cm off.
    private val direct = 700
    private val room = listOf(direct to 1.0, direct + samples(0.20) to 0.25)

    @Test fun theMatchedFilterFindsTheDirectPathExactly() {
        val re = DoubleArray(Sonar.PERIOD); val im = DoubleArray(Sonar.PERIOD); val mag = DoubleArray(Sonar.PERIOD)
        matched.profile(receive(room, 1), re, im); Sonar.magnitude(re, im, mag)
        assertEquals(direct.toDouble(), Sonar.peak(mag), 0.5)
    }

    @Test fun aStillRoomShowsNoTarget() {
        val t = SonarTracker()
        var fix: SonarTracker.Fix? = null
        repeat(40) { fix = run(t, room, it) }
        assertTrue("range ${fix!!.rangeM}", fix!!.rangeM.isNaN())
        assertTrue(fix!!.linkSnr > 20)
    }

    @Test fun aHandIsRangedWithinTwoCentimetres() {
        for (cm in listOf(8, 15, 30, 50)) {
            val t = SonarTracker()
            repeat(30) { run(t, room, it) }
            val fix = run(t, room + ((direct + samples(cm / 100.0)) to 0.04), 21)
            assertEquals("hand at $cm cm", cm / 100.0, fix.rangeM, 0.02)
            assertTrue(fix.confidence >= SonarTracker.CONFIDENT)
        }
    }

    @Test fun aHandMovingCloserHasNegativeVelocity() {
        val t = SonarTracker()
        repeat(30) { run(t, room, it) }
        var fix: SonarTracker.Fix? = null
        // 40 cm to 20 cm in 10 frames (0.43 s): about -0.47 m/s.
        // Ends at 28 cm, clear of the table at 20 cm: within the 5.7 cm resolution, a hand and a strong still echo merge.
        for (k in 0..6) { fix = run(t, room + ((direct + samples(0.40 - 0.02 * k)) to 0.04), 31 + k) }
        assertEquals(0.28, fix!!.rangeM, 0.02)
        assertTrue("v ${fix!!.velocity}", fix!!.velocity < -0.2)
    }

    @Test fun theDirectPathIsFoundWhereverTheLatencyPutsIt() {
        val t = SonarTracker()
        val far = 1900 // near the wrap-around: echoes continue past the period's end
        repeat(30) { run(t, listOf(far to 1.0), it) }
        val fix = run(t, listOf(far to 1.0, (far + samples(0.25)) to 0.05), 21)
        assertEquals(0.25, fix.rangeM, 0.02)
    }

    @Test fun bearingLeansTowardTheEarlierMic() {
        val a = FloatArray(200); val b = FloatArray(200)
        for (i in 0 until 200) { a[i] = kotlin.math.exp(-((i - 100.0) * (i - 100.0)) / 20).toFloat(); b[i] = kotlin.math.exp(-((i - 102.0) * (i - 102.0)) / 20).toFloat() }
        assertEquals(2.0, Bearing.lag(a, b, 100), 0.3)
        assertTrue(Bearing.angle(2.0, 0.015) > 60)
        assertEquals(0.0, Bearing.angle(0.0, 0.015), 1e-9)
    }

    @Test fun fftRoundTrips() {
        val re = DoubleArray(64) { kotlin.math.sin(it * 0.3) }; val im = DoubleArray(64); val orig = re.copyOf()
        Fft.transform(re, im, false); Fft.transform(re, im, true)
        for (i in orig.indices) assertEquals(orig[i], re[i], 1e-9)
    }

    /** Experiment, not a pass/fail: how ranging degrades as noise rises, and whether two hands 8 cm apart separate. */
    @Test fun experimentNoiseAndResolution() {
        val out = StringBuilder()
        for (noise in listOf(0.002, 0.01, 0.03, 0.06, 0.1)) {
            var hits = 0; var errSum = 0.0
            repeat(10) { trial ->
                val t = SonarTracker()
                repeat(30) { i -> val re = DoubleArray(Sonar.PERIOD); val im = DoubleArray(Sonar.PERIOD); matched.profile(receive(room, trial * 100 + i, noise), re, im); t.feed(re, im, i * 43L) }
                val re = DoubleArray(Sonar.PERIOD); val im = DoubleArray(Sonar.PERIOD)
                matched.profile(receive(room + ((direct + samples(0.30)) to 0.04), trial * 100 + 50, noise), re, im)
                val f = t.feed(re, im, 21 * 43L)
                if (!f.rangeM.isNaN() && kotlin.math.abs(f.rangeM - 0.30) < 0.02) { hits++; errSum += kotlin.math.abs(f.rangeM - 0.30) }
            }
            out.appendLine("noise %.3f (hand echo 0.04): ranged within 2 cm in %d/10, mean error %.1f mm".format(noise, hits, if (hits > 0) errSum / hits * 1000 else Double.NaN))
        }
        // Two moving reflectors: 8 cm apart (above the 5.7 cm resolution) and 4 cm apart (below).
        for (gap in listOf(0.08, 0.04)) {
            val t = SonarTracker()
            repeat(30) { i -> val re = DoubleArray(Sonar.PERIOD); val im = DoubleArray(Sonar.PERIOD); matched.profile(receive(room, i), re, im); t.feed(re, im, i * 43L) }
            val re = DoubleArray(Sonar.PERIOD); val im = DoubleArray(Sonar.PERIOD)
            matched.profile(receive(room + ((direct + samples(0.30)) to 0.04) + ((direct + samples(0.30 + gap)) to 0.04), 77), re, im)
            val f = t.feed(re, im, 21 * 43L)
            val m = f.motion; val peaks = (2 until m.size - 2).count { m[it] > m[it - 1] && m[it] >= m[it + 1] && m[it] > 0.5f * (m.maxOrNull() ?: 0f) }
            out.appendLine("two reflectors %.0f cm apart: %d strong peaks".format(gap * 100, peaks))
        }
        java.io.File("build/echo-experiment.txt").writeText(out.toString())
        println(out)
    }
}
