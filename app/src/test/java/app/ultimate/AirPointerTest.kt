package app.ultimate

import app.ultimate.air.AirPointer
import app.ultimate.air.AirPointer.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class AirPointerTest {
    private val w = 1080f; private val h = 2400f

    /** Holds the finger at (x, y) for [ms] at 30 frames a second, with [pinch], returning any actions. */
    private fun hold(p: AirPointer, x: Float, y: Float, pinch: Float, ms: Long, t0: Long, jitter: Float = 0f, rnd: Random = Random(1)): Pair<List<Action>, Long> {
        val out = ArrayList<Action>(); var t = t0
        while (t < t0 + ms) { p.feed(x + (rnd.nextFloat() - 0.5f) * jitter, y + (rnd.nextFloat() - 0.5f) * jitter, pinch, t)?.let(out::add); t += 33 }
        return out to t
    }

    @Test fun theMiddleOfThePictureReachesTheWholeScreen() {
        val p = AirPointer(w, h)
        hold(p, 0.5f, 0.5f, 1f, 500, 0)
        assertEquals(w / 2, p.x, 5f); assertEquals(h / 2, p.y, 5f)
        hold(p, 0.2f, 0.8f, 1f, 1_500, 1_000) // the edge of the reach is the corner of the screen
        assertEquals(0f, p.x, 5f); assertEquals(h, p.y, 10f)
    }

    @Test fun aSteadyHandGivesASteadyCursor() {
        val p = AirPointer(w, h)
        val xs = ArrayList<Float>(); var t = 0L; val rnd = Random(7)
        repeat(90) { p.feed(0.5f + (rnd.nextFloat() - 0.5f) * 0.01f, 0.5f, 1f, t); xs += p.x; t += 33 }
        val spread = xs.drop(30).let { it.max() - it.min() }
        // Raw jitter of 1% of the picture is ~18 px on screen; smoothed, it should be a fraction of that.
        assertTrue("spread $spread px", spread < 8f)
    }

    @Test fun aQuickPinchIsATapWhereItStarted() {
        val p = AirPointer(w, h)
        var (_, t) = hold(p, 0.5f, 0.5f, 1f, 500, 0)
        val (a1, t1) = hold(p, 0.5f, 0.5f, 0.1f, 200, t); t = t1
        val (a2, _) = hold(p, 0.5f, 0.5f, 0.8f, 100, t)
        val tap = (a1 + a2).single() as Action.Tap
        assertEquals(w / 2, tap.x, 10f)
    }

    @Test fun aHeldPinchIsALongPressOnce() {
        val p = AirPointer(w, h)
        var (_, t) = hold(p, 0.5f, 0.5f, 1f, 500, 0)
        val (a1, t1) = hold(p, 0.5f, 0.5f, 0.1f, 1_200, t); t = t1
        val (a2, _) = hold(p, 0.5f, 0.5f, 0.8f, 100, t)
        assertEquals(1, (a1 + a2).size); assertTrue((a1 + a2).single() is Action.LongPress)
    }

    @Test fun aPinchThatMovesIsADrag() {
        val p = AirPointer(w, h)
        var (_, t) = hold(p, 0.5f, 0.6f, 1f, 500, 0)
        hold(p, 0.5f, 0.6f, 0.1f, 100, t).let { t = it.second }
        val (a, t2) = hold(p, 0.5f, 0.35f, 0.1f, 400, t); t = t2 // move up while pinched: a scroll
        val drag = (a + hold(p, 0.5f, 0.35f, 0.8f, 100, t).first).single() as Action.Drag
        assertTrue(drag.y0 - drag.y1 > 500)
        assertTrue(abs(drag.x1 - drag.x0) < 30)
    }

    @Test fun aPinchNearTheThresholdDoesNotFlicker() {
        val p = AirPointer(w, h)
        var (_, t) = hold(p, 0.5f, 0.5f, 1f, 300, 0)
        hold(p, 0.5f, 0.5f, 0.2f, 100, t).let { t = it.second }
        // Opening a little (0.3) stays pinched; only past 0.4 does it let go.
        val (a, _) = hold(p, 0.5f, 0.5f, 0.32f, 300, t)
        assertTrue(a.isEmpty()); assertTrue(p.pinched)
        assertNull(p.feed(0.5f, 0.5f, 1f, t + 2_000).takeIf { it !is Action.LongPress && it != null })
    }
}
