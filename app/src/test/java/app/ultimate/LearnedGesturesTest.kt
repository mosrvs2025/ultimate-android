package app.ultimate

import app.ultimate.echo.GestureModel
import app.ultimate.echo.LearnedGestureDetector
import app.ultimate.echo.SonarFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Simulated sonar, shaped like the real tracker's output, for the gestures Echo learns. */
class LearnedGesturesTest {
    private val rnd = Random(3)
    private fun motion(peakBin: Float, strength: Float) = FloatArray(64) { i -> val d = i / 8f - peakBin; (strength * kotlin.math.exp(-d * d) + rnd.nextFloat() * 0.3f) }

    /** One gesture's frames over [ms], 43 ms apart, starting at [t0]. */
    private fun gesture(kind: String, t0: Long, ms: Long = 600): List<Pair<Long, FloatArray>> {
        val n = (ms / 43).toInt()
        return (0 until n).map { i ->
            val p = i / (n - 1f); val j = { rnd.nextDouble(-0.02, 0.02) }
            val f = when (kind) {
                "push" -> SonarFeatures.frame(0.40 - 0.25 * p + j(), -0.5 + j(), 12.0, 0, motion(5f - 3f * p, 4f))
                "pull" -> SonarFeatures.frame(0.15 + 0.25 * p + j(), 0.5 + j(), 12.0, 0, motion(2f + 3f * p, 4f))
                "swipe_left" -> SonarFeatures.frame(0.25 + j(), 0.0 + j(), 9.0, if (p < 0.5) 1 else -1, motion(3f, 5f * (1 - abs(p - 0.5f) * 2)))
                "swipe_right" -> SonarFeatures.frame(0.25 + j(), 0.0 + j(), 9.0, if (p < 0.5) -1 else 1, motion(3f, 5f * (1 - abs(p - 0.5f) * 2)))
                else -> SonarFeatures.frame(Double.NaN, Double.NaN, 1.0, null, motion(0f, 0f))
            }
            (t0 + i * 43L) to f
        }
    }
    private fun abs(x: Float) = kotlin.math.abs(x)

    private fun examples(perClass: Int = 8): GestureModel {
        val s = ArrayList<GestureModel.Sample>(); var t = 0L
        for (k in listOf("push", "pull", "swipe_left", "swipe_right", GestureModel.NONE)) repeat(perClass) {
            val g = gesture(k, t); t += 2_000
            s += GestureModel.Sample(k, SonarFeatures.window(g, g.last().first)!!)
        }
        return GestureModel(s)
    }

    @Test fun learnsToTellTheGesturesApart() {
        val acc = examples().accuracy()
        acc.forEach { (k, a) -> assertTrue("$k recognised only ${(a * 100).toInt()}%", a >= 0.75) }
    }

    @Test fun savesAndLoadsTheSameExamples() {
        val m = examples(3)
        val back = GestureModel.fromJson(m.toJson())
        assertNotNull(back)
        assertEquals(m.samples.size, back!!.samples.size)
        assertEquals(m.samples.first().x.toList(), back.samples.first().x.toList())
    }

    @Test fun detectorFiresOncePerGestureAndNotOnAQuietRoom() {
        val d = LearnedGestureDetector(examples())
        val quiet = gesture(GestureModel.NONE, 0, 4_000)
        assertTrue(quiet.mapNotNull { (t, f) -> d.feed(f, t) }.isEmpty())
        val push = gesture("push", 5_000, 800)
        val fired = push.mapNotNull { (t, f) -> d.feed(f, t) }
        assertEquals(listOf("push"), fired)
    }

    @Test fun tooFewFramesGiveNoWindow() {
        assertEquals(null, SonarFeatures.window(gesture("push", 0).take(3), 200))
    }
}
