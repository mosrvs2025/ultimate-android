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

    /**
     * Bench 2 on the same phone (1.53.1): no false alarms any more, but a hand held at 10 cm read 20 cm and one at 30 cm
     * read 20 cm. A hand over the phone comes with an arm behind it, moving too and often echoing more strongly. The
     * hand is the nearest moving thing, so that is what Echo should report.
     */
    @Test fun theNearestMovingThingWinsOverAStrongerArmBehindIt() {
        val t = SonarTracker()
        repeat(40) { fix(t, room, it) }
        val reads = (40 until 60).map { k ->
            fix(t, room + ((direct + samples(0.15 + 0.004 * (k % 3))) to 0.01) + ((direct + samples(0.32 + 0.005 * (k % 2))) to 0.025), k).rangeM
        }.filter { !it.isNaN() }
        assertTrue(reads.size >= 14)
        assertEquals(0.15, reads.sorted()[reads.size / 2], 0.025)
    }

    /**
     * Bench 3 (1.54.0): a hand held at 20 or 30 cm read exactly 3.9 cm, the edge of the search window, every frame. A hand
     * over the phone also changes how strongly the speaker reaches the mic; locking each frame to that direct path then
     * rescales every still echo, and the strong ones beside the phone light up as if they had moved.
     */
    @Test fun aHandThatDisturbsTheDirectPathIsStillRangedNotTheEdge() {
        for (cm in listOf(12, 20, 30)) {
            val t = SonarTracker()
            val still = listOf(direct to 1.0, direct + samples(0.04) to 0.3, direct + samples(0.09) to 0.08, direct + samples(0.20) to 0.05)
            repeat(40) { fix(t, still, it) }
            val reads = (40 until 60).map { k ->
                val withHand = listOf(direct to 0.85) + still.drop(1) + ((direct + samples(cm / 100.0 + 0.004 * (k % 3))) to 0.01)
                fix(t, withHand, k).rangeM
            }.filter { !it.isNaN() }
            assertTrue("hand at $cm cm detected ${reads.size}/20", reads.size >= 14)
            assertEquals("hand at $cm cm", cm / 100.0, reads.sorted()[reads.size / 2], 0.025)
        }
    }

    /**
     * Galaxy S10, 1.57.0: talking near the phone read as "something came closer". Speech, its "s" and "sh" sounds above
     * all, reaches into the 17-20 kHz band; the matched filter turns that into change at every distance at once, unlike a
     * hand, which changes one. A loud hiss burst with nobody's hand there must not be reported.
     */
    @Test fun aHissInTheChirpBandIsNotAHand() {
        val t = SonarTracker()
        val still = listOf(direct to 1.0, direct + samples(0.04) to 0.3, direct + samples(0.20) to 0.05)
        repeat(40) { fix(t, still, it) }
        val r = java.util.Random(9)
        var reported = 0
        for (k in 40 until 70) {
            val clean = receive(still, k)
            // A sibilant: many tones with random phases across 16-21 kHz, louder than any echo of the chirp.
            val tones = List(160) { Triple(16_000.0 + 5_000.0 * r.nextDouble(), r.nextDouble() * 6.283, 0.02) }
            val hiss = FloatArray(Sonar.PERIOD) { i -> (clean[i] + tones.sumOf { (f, p, a) -> a * kotlin.math.sin(6.283 * f * i / Sonar.RATE + p) }).toFloat() }
            val re = DoubleArray(Sonar.PERIOD); val im = DoubleArray(Sonar.PERIOD)
            matched.profile(hiss, re, im)
            if (!t.feed(re, im, k * 43L).rangeM.isNaN()) reported++
        }
        assertTrue("hiss reported as a target in $reported of 30 frames", reported <= 1)
    }

    /**
     * Galaxy Fold, 1.59.0: after the voice filter, a hand held at 10, 20 or 30 cm was "not detected" in every frame. A
     * real hand comes with an arm, a wrist and reflections between them, so it changes many near distances at once. The
     * voice filter judged by all distances and took that for noise. A hand must stay detected through that clutter.
     */
    @Test fun aRealHandWithArmAndClutterIsStillDetected() {
        for (cm in listOf(10, 20, 30)) {
            val t = SonarTracker()
            val still = listOf(direct to 1.0, direct + samples(0.04) to 0.3, direct + samples(0.20) to 0.05)
            repeat(40) { coloured(t, still, it) }
            val reads = (40 until 60).map { k ->
                val w = 0.004 * (k % 3)
                // The palm, then wrist, forearm and elbow reflections spread out behind it, all moving.
                val clutter = (1..8).map { j -> (direct + samples(cm / 100.0 + 0.03 * j + w * j)) to 0.006 }
                coloured(t, listOf(direct to 0.85) + still.drop(1) + ((direct + samples(cm / 100.0 + w)) to 0.01) + clutter, k).rangeM
            }.filter { !it.isNaN() }
            // Detection is what the voice filter must not take away. (Through a coloured speaker the range can lock onto
            // leakage near the phone; that's what per-phone calibration and the hand-over-the-bottom-edge advice address.)
            assertTrue("hand at $cm cm with clutter detected in ${reads.size}/20", reads.size >= 14)
        }
    }

    /** Like [fix], but through a real speaker and mic: their response colours the sound, so the direct path's shape no
     *  longer matches the ideal chirp exactly, as on any phone. */
    private fun coloured(t: SonarTracker, paths: List<Pair<Double, Double>>, frame: Int): SonarTracker.Fix {
        val x = receive(paths, frame)
        val y = FloatArray(x.size) { i -> x[i] + 0.45f * x[Math.floorMod(i - 1, x.size)] - 0.25f * x[Math.floorMod(i - 3, x.size)] + 0.1f * x[Math.floorMod(i - 7, x.size)] }
        val re = DoubleArray(Sonar.PERIOD); val im = DoubleArray(Sonar.PERIOD)
        matched.profile(y, re, im)
        return t.feed(re, im, frame * 43L)
    }

}
