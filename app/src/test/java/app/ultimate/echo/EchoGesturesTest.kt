package app.ultimate.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGesturesTest {
    /** Feeds a distance per 43 ms frame and collects what fires. */
    private fun run(g: EchoGestures, ranges: List<Double>, start: Long = 0): List<EchoGestures.Gesture> =
        ranges.mapIndexedNotNull { i, r -> g.feed(r, start + i * 43L) }

    @Test fun aQuickMoveTowardThePhoneIsAPushAndAwayIsAPull() {
        val g = EchoGestures()
        val push = run(g, listOf(0.30, 0.28, 0.25, 0.22, 0.20, 0.20))
        assertEquals("push", push.single().kind)
        val pull = run(EchoGestures(), listOf(0.12, 0.15, 0.18, 0.21, 0.22))
        assertEquals("pull", pull.single().kind)
    }

    @Test fun slowDriftIsNotAGesture() {
        // 6 cm over two seconds: a hand settling, not a push.
        assertTrue(run(EchoGestures(holdMs = 10_000), List(48) { 0.30 - 0.06 * it / 47 }).none { it.kind == "push" })
    }

    @Test fun holdingStillIsAHoverReportedOnce() {
        val g = EchoGestures()
        val got = run(g, List(60) { 0.20 + if (it % 2 == 0) 0.005 else -0.005 })
        assertEquals(listOf("hover"), got.map { it.kind })
        assertEquals(0.20, got.single().atM, 0.01)
    }

    @Test fun aLongGapEndsTheHold() {
        val g = EchoGestures()
        run(g, List(10) { 0.2 })
        assertNull(g.feed(Double.NaN, 10 * 43L + 300))
        assertTrue(run(g, List(10) { 0.2 }, start = 1_000).isEmpty())
    }
}
