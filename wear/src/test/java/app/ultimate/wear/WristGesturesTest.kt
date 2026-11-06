package app.ultimate.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Simulated wrist motion at 50 Hz: a smooth burst of turning about one axis, as a real flick looks on the gyroscope. */
class WristGesturesTest {
    private fun burst(peak: Float, ms: Int = 160): List<Float> { val n = ms / 20; return (0 until n).map { peak * sin(PI * it / (n - 1)).toFloat() } }
    private fun still(ms: Int) = List(ms / 20) { 0.1f }

    /** Runs gyro readings through the detector, with [axis] 0/1/2 for x/y/z. */
    private fun run(d: WristGestures, readings: List<Pair<Int, Float>>, t0: Long = 0): List<String> {
        var t = t0
        return readings.mapNotNull { (axis, v) ->
            t += 20
            val noise = 0.2f
            d.gyro(if (axis == 0) v else noise, if (axis == 1) v else noise, if (axis == 2) v else noise, t)
        }
    }
    private fun on(axis: Int, values: List<Float>) = values.map { axis to it }

    @Test fun flicksLeftAndRight() {
        val d = WristGestures()
        assertEquals(listOf("flick_left"), run(d, on(2, burst(7f)) + on(2, still(800))))
        assertEquals(listOf("flick_right"), run(d, on(2, burst(-7f)) + on(2, still(800)), 2_000))
    }

    @Test fun theRightWristMirrorsLeftAndRight() {
        val d = WristGestures(rightWrist = true)
        assertEquals(listOf("flick_right"), run(d, on(2, burst(7f)) + on(2, still(800))))
    }

    @Test fun flicksUpAndDown() {
        val d = WristGestures()
        assertEquals(listOf("flick_up"), run(d, on(0, burst(7f)) + on(0, still(800))))
        assertEquals(listOf("flick_down"), run(d, on(0, burst(-7f)) + on(0, still(800)), 2_000))
    }

    @Test fun aTwistNeedsTheTurnBack() {
        val d = WristGestures()
        // Half a twist is nothing...
        assertTrue(run(d, on(1, burst(8f)) + on(1, still(1_000))).isEmpty())
        // ...a twist and back is one twist.
        assertEquals(listOf("twist"), run(d, on(1, burst(8f)) + on(1, still(100)) + on(1, burst(-8f)) + on(1, still(800)), 5_000))
    }

    @Test fun ordinaryArmMovementIsNotAGesture() {
        val d = WristGestures()
        var t = 0L
        // Turning on all three axes at once, as when reaching for a cup.
        val fired = (0 until 50).mapNotNull { i -> t += 20; val v = 6f * sin(i / 8.0).toFloat(); d.gyro(v, v * 0.9f, v * 0.8f, t) }
        assertTrue(fired.toString(), fired.isEmpty())
    }

    @Test fun oneFlickIsOneGesture() {
        val d = WristGestures()
        assertEquals(1, run(d, on(2, burst(9f, 300)) + on(2, still(200))).size)
    }

    @Test fun shakeNeedsThreeAlternatingJolts() {
        val d = WristGestures()
        assertEquals(null, d.accel(20f, 0f, 0f, 0)); assertEquals(null, d.accel(20f, 0f, 0f, 50)) // same direction twice is one jolt
        assertEquals(null, d.accel(-20f, 0f, 0f, 150))
        assertEquals("shake", d.accel(20f, 0f, 0f, 300))
        assertEquals(null, d.accel(3f, 0f, 0f, 2_000)) // gentle movement does nothing
    }

    @Test fun aCalibrationFromThePhoneDecidesWhichWayIsUp() {
        // On this wrist "up" turns negative about X, the opposite of the built-in guess.
        val d = WristGestures()
        assertEquals(listOf("flick_down"), run(d, on(0, burst(-7f)) + on(0, still(800))))
        assertTrue(d.configure("config:rate=3.50;twist=y;x-=flick_up;x+=flick_down;z+=flick_left;z-=flick_right"))
        assertEquals(listOf("flick_up"), run(d, on(0, burst(-5f)) + on(0, still(800)), 5_000)) // and 5 rad/s now clears 3.5
        assertEquals(false, d.configure("gesture:flick_up"))
    }

    @Test fun aFlicksOwnJoltIsNotAShake() {
        val d = WristGestures()
        var t = 0L
        // A sharp flick: fast turning with hard jolts back and forth at the same moments.
        val fired = (0 until 12).flatMap { i ->
            t += 20
            val v = 8f * sin(PI * i / 11).toFloat()
            listOfNotNull(d.gyro(0.2f, 0.2f, v, t), d.accel(if (i % 2 == 0) 25f else -25f, 0f, 0f, t))
        }
        assertEquals(listOf("flick_left"), fired)
    }
}
