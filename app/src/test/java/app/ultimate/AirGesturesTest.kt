package app.ultimate

import app.ultimate.air.AirGestures
import app.ultimate.air.HandPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirGesturesTest {
    /** A flat hand centred at (cx, cy), knuckles [w] apart, thumb tip [pinch] hand-widths from the index tip. */
    private fun hand(cx: Float, cy: Float, w: Float = 0.12f, pinch: Float = 0.8f, at: Long, sign: String? = null, score: Float = 0f): HandPose {
        val pts = MutableList(21) { cx to cy }
        pts[0] = cx to cy + w * 0.8f; pts[5] = cx - w / 2 to cy - w * 0.2f; pts[9] = cx to cy - w * 0.25f; pts[13] = cx + w / 3 to cy - w * 0.2f; pts[17] = cx + w / 2 to cy - w * 0.2f
        pts[8] = cx - w / 2 to cy - w * 1.2f; pts[4] = pts[8].first + pinch * w to pts[8].second
        return HandPose(pts, sign, score, at)
    }

    private fun run(g: AirGestures, poses: List<HandPose>) = poses.mapNotNull { g.feed(it, it.at) }.map { it.kind }

    @Test fun swipesAreReadInYourDirectionThroughTheMirror() {
        // Front camera: the hand moving right in the picture is moving to your left.
        assertEquals(listOf("swipe_left"), run(AirGestures(mirrored = true), (0..6).map { hand(0.3f + 0.06f * it, 0.5f, at = it * 50L) }))
        assertEquals(listOf("swipe_right"), run(AirGestures(mirrored = false), (0..6).map { hand(0.3f + 0.06f * it, 0.5f, at = it * 50L) }))
        assertEquals(listOf("swipe_up"), run(AirGestures(), (0..6).map { hand(0.5f, 0.8f - 0.06f * it, at = it * 50L) }))
    }

    @Test fun slowDriftIsNotASwipe() {
        assertTrue(run(AirGestures(), (0..40).map { hand(0.3f + 0.01f * it, 0.5f, at = it * 50L) }).none { it.startsWith("swipe") })
    }

    @Test fun pushPullAndPinch() {
        assertEquals(listOf("push"), run(AirGestures(), (0..5).map { hand(0.5f, 0.5f, w = 0.10f + 0.012f * it, at = it * 60L) }))
        assertEquals(listOf("pinch"), run(AirGestures(), (0..5).map { hand(0.5f, 0.5f, pinch = 0.8f - 0.14f * it, at = it * 60L) }))
    }

    @Test fun signsMustBeHeldAndHoldNeedsStillness() {
        val g = AirGestures()
        assertEquals(listOf("thumb_up"), run(g, (0..10).map { hand(0.5f, 0.5f, at = it * 50L, sign = "Thumb_Up", score = 0.9f) }))
        assertTrue("a flicker of a sign doesn't count", run(AirGestures(), (0..3).map { hand(0.5f, 0.5f, at = it * 50L, sign = if (it == 1) "Victory" else null, score = 0.9f) }).isEmpty())
        assertEquals(listOf("hold"), run(AirGestures(), (0..30).map { hand(0.5f + (it % 2) * 0.005f, 0.5f, at = it * 50L) }))
    }
}
