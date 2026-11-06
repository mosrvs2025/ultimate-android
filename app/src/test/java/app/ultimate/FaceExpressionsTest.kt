package app.ultimate

import app.ultimate.air.FaceExpressions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceExpressionsTest {
    private val relaxed = mapOf("browInnerUp" to 0.05f, "browDownLeft" to 0.05f, "browDownRight" to 0.05f, "jawOpen" to 0.02f, "mouthSmileLeft" to 0.03f, "mouthSmileRight" to 0.03f)

    private fun warm(f: FaceExpressions, face: Map<String, Float> = relaxed): Long {
        var t = 0L
        repeat(30) { assertNull(f.feed(face, t).fired); t += 40 }
        return t
    }

    @Test fun eyebrowsUpFiresOnceWhileHeld() {
        val f = FaceExpressions(); var t = warm(f)
        val up = relaxed + ("browInnerUp" to 0.8f)
        val fired = (0 until 20).mapNotNull { f.feed(up, t).fired.also { t += 40 } }
        assertEquals(listOf("brows_up"), fired.map { it.kind })
    }

    @Test fun firesAgainAfterRelaxing() {
        val f = FaceExpressions(refractoryMs = 300); var t = warm(f)
        val up = relaxed + ("browInnerUp" to 0.8f)
        var count = 0
        repeat(3) {
            repeat(5) { if (f.feed(up, t).fired != null) count++; t += 40 }
            repeat(10) { f.feed(relaxed, t); t += 40 }
        }
        assertEquals(3, count)
    }

    @Test fun aRestingFaceWithHighBrowsIsNotAnExpression() {
        // Someone whose brows rest high must not trigger "eyebrows up" just by looking at the camera.
        val high = relaxed + ("browInnerUp" to 0.5f)
        val f = FaceExpressions(); var t = 0L
        val fired = (0 until 200).mapNotNull { f.feed(high, t).fired.also { t += 40 } }
        assertTrue(fired.none { it.kind == "brows_up" })
    }

    @Test fun blinkAndWinkAreToldApart() {
        val f = FaceExpressions(); var t = warm(f)
        val wink = f.feed(relaxed + ("eyeBlinkLeft" to 0.9f) + ("eyeBlinkRight" to 0.05f), t).fired
        assertEquals("wink", wink?.kind)
        repeat(20) { t += 40; f.feed(relaxed, t) }
        val blink = f.feed(relaxed + ("eyeBlinkLeft" to 0.9f) + ("eyeBlinkRight" to 0.9f), t + 40).fired
        assertEquals("blink", blink?.kind)
    }

    @Test fun eyebrowsDownAndSmileAreSeparate() {
        val f = FaceExpressions(); var t = warm(f)
        assertEquals("brows_down", f.feed(relaxed + ("browDownLeft" to 0.8f) + ("browDownRight" to 0.8f), t).fired?.kind)
        repeat(20) { t += 40; f.feed(relaxed, t) }
        assertEquals("smile", f.feed(relaxed + ("mouthSmileLeft" to 0.9f) + ("mouthSmileRight" to 0.9f), t + 40).fired?.kind)
    }
}
