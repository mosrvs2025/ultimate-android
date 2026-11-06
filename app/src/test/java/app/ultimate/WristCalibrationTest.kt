package app.ultimate

import app.ultimate.air.WristCalibration
import app.ultimate.air.WristCalibration.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WristCalibrationTest {
    /**
     * A flick on [axis] at 50 Hz: a turn of [peak] rad/s, then a rebound of [rebound] × that the other way as the wrist
     * stops. With a strong rebound, the strongest moment points the wrong way, which is how "up" read as "down".
     */
    private fun flick(axis: Char, peak: Float, rebound: Float = 0.9f, t0: Long = 0): List<Sample> {
        val turn = (0 until 8).map { peak * sin(PI * it / 7).toFloat() }
        val back = (0 until 8).map { -peak * rebound * sin(PI * it / 7).toFloat() }
        val rest = List(15) { 0.1f }
        return (turn + back + rest).mapIndexed { i, v ->
            Sample(t0 + i * 20L, if (axis == 'x') v else 0.2f, if (axis == 'y') v else 0.2f, if (axis == 'z') v else 0.2f, 3f, 2f, 1f)
        }
    }

    @Test fun theDirectionIsTheFirstTurnNotTheStrongerRebound() {
        val b = WristCalibration.bursts(flick('x', -6f, rebound = 1.2f))
        assertEquals(1, b.size)
        val s = WristCalibration.signature(b.first())!!
        assertEquals('x', s.axis); assertEquals(-1, s.sign)
        assertEquals(6f, s.peak, 0.3f)
    }

    @Test fun separateFlicksAreSeparateBursts() {
        val stream = flick('z', 7f) + flick('z', 7f, t0 = 1_000) + flick('z', -7f, t0 = 2_000)
        val sigs = WristCalibration.bursts(stream).map { WristCalibration.signature(it)!!.key }
        assertEquals(listOf("z+", "z+", "z-"), sigs)
    }

    @Test fun buildsTheMappingThisWatchActuallyUses() {
        // On this wrist, "up" turns negative about X: the opposite of the uncalibrated guess.
        fun sigs(axis: Char, peak: Float) = List(3) { i -> WristCalibration.signature(WristCalibration.bursts(flick(axis, peak, t0 = i * 1_000L)).first())!! }
        val c = WristCalibration.build(mapOf(
            "flick_up" to sigs('x', -6f), "flick_down" to sigs('x', 6f),
            "flick_left" to sigs('z', 8f), "flick_right" to sigs('z', -8f),
            "twist" to sigs('y', 9f),
        ))
        assertEquals("flick_up", c.directions["x-"]); assertEquals("flick_down", c.directions["x+"])
        assertEquals("flick_left", c.directions["z+"]); assertEquals("flick_right", c.directions["z-"])
        assertEquals('y', c.twistAxis)
        // A little over half the gentlest flick (6 rad/s), so all of them clear it.
        assertTrue(c.flickRate.toString(), c.flickRate in 3f..4.5f)
        assertTrue(WristCalibration.encode(c).startsWith("config:rate="))
        assertTrue(WristCalibration.encode(c).contains("x-=flick_up"))
    }

    @Test fun aDirectionClaimedTwiceGoesToTheMoreConsistentGesture() {
        val up = List(3) { WristCalibration.Signature('x', 1, 6f) }
        val down = listOf(WristCalibration.Signature('x', 1, 6f), WristCalibration.Signature('x', -1, 6f))
        val c = WristCalibration.build(mapOf("flick_up" to up, "flick_down" to down))
        assertEquals("flick_up", c.directions["x+"])
        assertEquals(1, c.directions.size)
    }

    @Test fun parsesTheWatchStream() {
        val s = WristCalibration.parseSensors("sensors:0,1,2,3,4,5,6|20,1.5,0,0,0,0,9.8|bad", 10_000)
        assertEquals(2, s.size)
        assertEquals(9_980L, s[0].t); assertEquals(10_000L, s[1].t)
        assertEquals(9.8f, s[1].az)
        assertTrue(WristCalibration.parseSensors("gesture:flick_up", 0).isEmpty())
    }
}
