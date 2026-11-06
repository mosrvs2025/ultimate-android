package app.ultimate

import app.ultimate.echo.GestureModel
import app.ultimate.senselab.StudioMath
import app.ultimate.senselab.StudioMath.Lane
import app.ultimate.senselab.StudioMath.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StudioMathTest {
    private val rnd = Random(4)
    private fun ch(name: String) = StudioMath.CHANNELS.indexOf(name)

    /** A wave over the phone: proximity goes near and back, light dips; the watch sees a little motion. Noise elsewhere. */
    private fun wave(t0: Long) = buildList {
        for (i in 0 until 28) {
            val t = t0 + i * 50; val p = i / 27f
            add(Point(t, ch("near"), if (p in 0.35f..0.65f) 1f else 0f))
            add(Point(t, ch("light"), 5f - if (p in 0.3f..0.7f) 3f else 0f + rnd.nextFloat() * 0.2f))
            add(Point(t, ch("wx"), rnd.nextFloat() * 0.5f)); add(Point(t, ch("loud"), 40f + rnd.nextFloat() * 3))
        }
    }
    /** A clap: a sharp loud spike; nothing near the phone. */
    private fun clap(t0: Long) = buildList {
        for (i in 0 until 28) {
            val t = t0 + i * 50
            add(Point(t, ch("near"), 0f)); add(Point(t, ch("light"), 5f + rnd.nextFloat() * 0.2f))
            add(Point(t, ch("wx"), rnd.nextFloat() * 0.5f)); add(Point(t, ch("loud"), if (i == 14) 85f else 40f + rnd.nextFloat() * 3))
        }
    }
    private fun still(t0: Long) = buildList {
        for (i in 0 until 28) { val t = t0 + i * 50
            add(Point(t, ch("near"), 0f)); add(Point(t, ch("light"), 5f + rnd.nextFloat() * 0.2f)); add(Point(t, ch("wx"), rnd.nextFloat() * 0.5f)); add(Point(t, ch("loud"), 40f + rnd.nextFloat() * 3)) }
    }

    private fun examples(): GestureModel {
        val s = ArrayList<GestureModel.Sample>(); var t = 0L
        repeat(6) {
            listOf("wave" to ::wave, "clap" to ::clap, GestureModel.NONE to ::still).forEach { (k, f) ->
                val pts = f(t); s += GestureModel.Sample(k, StudioMath.window(pts, pts.last().t)); t += 3_000
            }
        }
        return GestureModel(s)
    }

    @Test fun aWindowHasEveryChannelAndZerosForMissingSenses() {
        val pts = wave(0)
        val x = StudioMath.window(pts, pts.last().t)
        assertEquals(StudioMath.CHANNELS.size * StudioMath.PER_CHANNEL, x.size)
        val echo = ch("range") * StudioMath.PER_CHANNEL
        assertTrue((0 until StudioMath.PER_CHANNEL).all { x[echo + it] == 0f })
        assertEquals(1f, x[ch("near") * StudioMath.PER_CHANNEL + 1]) // proximity's highest: near
    }

    @Test fun allSensesTogetherTellTheGesturesApart() {
        val acc = examples().accuracy()
        acc.forEach { (k, a) -> assertTrue("$k ${(a * 100).toInt()}%", a >= 0.8) }
    }

    @Test fun reliabilityShowsWhichSenseNoticesWhichGesture() {
        val r = StudioMath.reliability(examples())
        // Proximity sees the wave; sound sees the clap; the watch, barely moving, sees neither well.
        assertTrue(r.toString(), r.getValue("wave").getValue(Lane.Proximity) >= 0.8)
        assertTrue(r.toString(), r.getValue("clap").getValue(Lane.Sound) >= 0.8)
        assertTrue(r.toString(), r.getValue("wave").getValue(Lane.Watch) < 0.8)
        assertTrue(Lane.Echo !in r.getValue("wave").keys) // Echo was off, so it isn't scored
    }
}
