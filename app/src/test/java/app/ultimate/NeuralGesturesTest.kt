package app.ultimate

import app.ultimate.echo.GestureBrain
import app.ultimate.echo.GestureModel
import app.ultimate.echo.SonarFeatures
import app.ultimate.echo.TinyNet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The neural network on simulated sonar. Swipes left and right are made identical in distance, speed and motion and
 * differ only in their Doppler spill (which side of the chirp band gains energy), the case the spectrum features exist for.
 */
class NeuralGesturesTest {
    private val rnd = Random(5)

    private fun spectrum(shift: Float): FloatArray = FloatArray(256) { i ->
        val f = 16_000f + i * 6_000f / 256
        val inBand = if (f in 17_000f..20_000f) 1f else 0.05f
        val spill = if (shift > 0 && f in 20_000f..20_800f) shift else if (shift < 0 && f in 16_200f..17_000f) -shift else 0f
        inBand + spill + rnd.nextFloat() * 0.05f
    }

    private fun window(kind: String): FloatArray {
        val n = 14
        val frames = (0 until n).map { i ->
            val p = i / (n - 1f); val j = rnd.nextDouble(-0.03, 0.03)
            val motion = FloatArray(64) { rnd.nextFloat() * 0.3f + if (kind != "none" && it in 16..24) 3f else 0f }
            val f = when (kind) {
                "push" -> SonarFeatures.frame(0.40 - 0.25 * p + j, -0.5 + j, 10.0, 0, motion, spectrum(-0.3f))
                "pull" -> SonarFeatures.frame(0.15 + 0.25 * p + j, 0.5 + j, 10.0, 0, motion, spectrum(0.3f))
                "swipe_left" -> SonarFeatures.frame(0.25 + j, j, 8.0, 0, motion, spectrum(if (p < 0.5f) 0.6f else -0.6f))
                "swipe_right" -> SonarFeatures.frame(0.25 + j, j, 8.0, 0, motion, spectrum(if (p < 0.5f) -0.6f else 0.6f))
                else -> SonarFeatures.frame(Double.NaN, Double.NaN, 1.0, null, FloatArray(64) { rnd.nextFloat() * 0.3f }, spectrum(0f))
            }
            i * 43L to f
        }
        return SonarFeatures.window(frames, frames.last().first)!!
    }

    private fun examples(per: Int) = GestureModel(listOf("push", "pull", "swipe_left", "swipe_right", "none").flatMap { k -> List(per) { GestureModel.Sample(k, window(k)) } })

    @Test fun windowsHaveTheDocumentedLength() = assertEquals(SonarFeatures.DIMS, window("push").size)

    @Test fun networkLearnsGesturesIncludingSwipesThatDifferOnlyInDoppler() {
        val train = examples(30); val test = examples(10)
        val net = TinyNet.train(train.samples)
        val byLabel = test.samples.groupBy { it.label }.mapValues { (_, v) -> v.count { net.predict(it.x)?.label == it.label }.toDouble() / v.size }
        byLabel.forEach { (k, a) -> assertTrue("$k only ${(a * 100).toInt()}%", a >= 0.8) }
    }

    @Test fun savedNetworkPredictsTheSame() {
        val net = TinyNet.train(examples(15).samples, epochs = 30)
        val back = TinyNet.fromJson(org.json.JSONObject(net.toJson().toString()))
        assertNotNull(back)
        val x = window("pull")
        assertEquals(net.predict(x), back!!.predict(x))
    }

    @Test fun brainMatchesExamplesUntilThereAreEnough() {
        val few = GestureBrain.build(examples(5))
        assertFalse(few.usesNet); assertNull(few.report.netAccuracy)
        assertTrue(few.report.note, few.report.note.contains("more examples"))
    }

    @Test fun brainScoresBothLearnersOnHeldBackExamplesOnceThereAreEnough() {
        val brain = GestureBrain.build(examples(30))
        assertNotNull(brain.report.netAccuracy)
        assertEquals(brain.report.usesNet, brain.report.netAccuracy!! >= brain.report.matchAccuracy)
        if (brain.usesNet) assertNotNull(brain.net)
        assertTrue(brain.report.netAccuracy!! >= 0.8)
    }

    @Test fun mergeReplacesRetaughtGesturesAndCapsEachOne() {
        val old = examples(4)
        val merged = GestureBrain.merge(old, List(3) { GestureModel.Sample("push", window("push")) }, replace = listOf("push"))
        assertEquals(3, merged.count("push")); assertEquals(4, merged.count("pull"))
        val big = GestureBrain.merge(null, List(GestureBrain.KEEP_PER_LABEL + 20) { GestureModel.Sample("none", window("none")) })
        assertEquals(GestureBrain.KEEP_PER_LABEL, big.count("none"))
    }

    @Test fun examplesFromTheOldFormatAreNotLoaded() {
        assertNull(GestureModel.fromJson("""{"version":1,"samples":[{"label":"push","x":[1,2,3]}]}"""))
    }
}
