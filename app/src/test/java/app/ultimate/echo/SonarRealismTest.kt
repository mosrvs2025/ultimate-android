package app.ultimate.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces what a real phone (Galaxy SM-F956U1 bench, Nov 2026) showed: a very strong direct path whose delay and
 * gain wobble slightly from frame to frame, because the speaker and microphone clocks are not locked. On that phone the
 * first tracker reported a target in 100% of still frames, always 4-6 cm away, whatever the hand did.
 */
class SonarRealismTest {
    private val matched = Sonar.Matched()
    private fun samples(m: Double) = m / Sonar.M_PER_SAMPLE

    /** Paths at real-valued delays; the direct path jitters by up to [jitter] samples and [gainJitter] in strength. */
    private fun receive(paths: List<Pair<Double, Double>>, seed: Int, jitter: Double = 0.35, gainJitter: Double = 0.03, noise: Double = 0.0005): FloatArray {
        val r = java.util.Random(seed.toLong())
        val dj = (r.nextDouble() * 2 - 1) * jitter; val gj = 1 + (r.nextDouble() * 2 - 1) * gainJitter
        return FloatArray(Sonar.PERIOD) { i ->
            (paths.sumOf { (d, a) -> Sonar.at(i - d - dj) * a * gj } + noise * r.nextGaussian()).toFloat()
        }
    }

    private val direct = 700.0
    // Like a real phone: the direct path is far stronger than any echo, the table is close, the hand is faint.
    private val room = listOf(direct to 1.0, direct + samples(0.04) to 0.3, direct + samples(0.20) to 0.05)

    private fun fix(t: SonarTracker, paths: List<Pair<Double, Double>>, frame: Int): SonarTracker.Fix {
        val re = DoubleArray(Sonar.PERIOD); val im = DoubleArray(Sonar.PERIOD)
        matched.profile(receive(paths, frame), re, im)
        return t.feed(re, im, frame * 43L)
    }

    @Test fun aStillRoomWithClockWobbleRaisesFewFalseAlarms() {
        val t = SonarTracker()
        repeat(40) { fix(t, room, it) }
        val hits = (40 until 140).count { !fix(t, room, it).rangeM.isNaN() }
        assertTrue("false alarms in $hits of 100 frames", hits <= 5)
    }

    @Test fun aHandIsStillRangedThroughTheWobble() {
        for (cm in listOf(10, 20, 30)) {
            val t = SonarTracker()
            repeat(40) { fix(t, room, it) }
            val reads = (40 until 60).map { k -> fix(t, room + ((direct + samples(cm / 100.0 + 0.004 * (k % 3))) to 0.01), k).rangeM }.filter { !it.isNaN() }
            assertTrue("hand at $cm cm detected in ${reads.size}/20", reads.size >= 14)
            val med = reads.sorted()[reads.size / 2]
            assertEquals("hand at $cm cm", cm / 100.0, med, 0.025)
        }
    }
}
