package app.ultimate

import app.ultimate.spheres.SphereStitch
import app.ultimate.spheres.SphereStitch.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class SphereStitchTest {
    /** A made-up world: a colour for every direction, smooth enough to sample, varied enough to catch mistakes. */
    private fun world(d: DoubleArray): Int {
        val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        val lon = atan2(d[0], -d[2]); val lat = asin(d[1] / len)
        val r = (128 + 110 * sin(lon * 2)).toInt(); val g = (128 + 110 * sin(lat * 3)).toInt(); val b = (128 + 110 * sin(lon + lat * 2)).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private val lens = SphereStitch.Lens(fx = 280.0, fy = 280.0, cx = 160.0, cy = 120.0, width = 320, height = 240) // about 60° across

    /** What a camera turned by [t] would see of the made-up world. */
    private fun shoot(t: Turn) = IntArray(lens.width * lens.height) { i ->
        val u = i % lens.width + 0.5; val v = i / lens.width + 0.5
        world(t.apply(doubleArrayOf((u - lens.cx) / lens.fx, -(v - lens.cy) / lens.fy, -1.0)))
    }

    private fun aim(yawDeg: Double, pitchDeg: Double) = Turn.of(Turn.about(1.0, 0.0, 0.0, Math.toRadians(pitchDeg)), Turn.about(0.0, 1.0, 0.0, Math.toRadians(yawDeg)))

    @Test fun turnsGoTheRightWay() {
        val left = Turn.about(0.0, 1.0, 0.0, PI / 2).apply(doubleArrayOf(0.0, 0.0, -1.0))
        assertEquals(-1.0, left[0], 1e-9); assertEquals(0.0, left[2], 1e-9)
        val upward = Turn.about(1.0, 0.0, 0.0, PI / 2).apply(doubleArrayOf(0.0, 0.0, -1.0))
        assertEquals(1.0, upward[1], 1e-9)
        val back = Turn.about(0.0, 1.0, 0.0, 0.7).inverse().apply(Turn.about(0.0, 1.0, 0.0, 0.7).apply(doubleArrayOf(0.3, 0.2, -0.9)))
        assertEquals(0.3, back[0], 1e-9); assertEquals(0.2, back[1], 1e-9)
    }

    @Test fun panoramaCoordinatesRoundTrip() {
        val rnd = Random(3)
        repeat(50) {
            val px = rnd.nextDouble() * 1600; val py = 1 + rnd.nextDouble() * 798
            val p = SphereStitch.toPano(SphereStitch.fromPano(px, py, 1600, 800), 1600, 800)
            assertEquals(px, p[0], 1e-6); assertEquals(py, p[1], 1e-6)
        }
        // Straight ahead (-Z) is the middle column; up is the top row.
        assertEquals(800.0, SphereStitch.toPano(doubleArrayOf(0.0, 0.0, -1.0), 1600, 800)[0], 1e-9)
        assertEquals(0.0, SphereStitch.toPano(doubleArrayOf(0.0, 1.0, 0.0), 1600, 800)[1], 1e-9)
    }

    @Test fun aFullSweepRebuildsTheWorld() {
        val s = SphereStitch(720, 360)
        for (pitch in listOf(-60.0, -20.0, 20.0, 60.0)) for (yaw in 0 until 360 step 40) s.add(shoot(aim(yaw.toDouble(), pitch)), lens, aim(yaw.toDouble(), pitch))
        s.add(shoot(aim(0.0, 89.0)), lens, aim(0.0, 89.0)); s.add(shoot(aim(0.0, -89.0)), lens, aim(0.0, -89.0))
        assertTrue("coverage ${s.coverage()}", s.coverage() > 0.97)
        val px = s.pixels(); val rnd = Random(7); var worst = 0
        repeat(400) {
            val x = rnd.nextInt(720); val y = 20 + rnd.nextInt(320)
            val want = world(SphereStitch.fromPano(x + 0.5, y + 0.5, 720, 360)); val got = px[y * 720 + x]
            for (sh in intArrayOf(16, 8, 0)) worst = maxOf(worst, abs(((want shr sh) and 0xFF) - ((got shr sh) and 0xFF)))
        }
        assertTrue("worst colour error $worst", worst <= 14)
        assertNull(s.biggestGap())
    }

    @Test fun onlyLookingAheadLeavesTheBackAsTheGap() {
        val s = SphereStitch(720, 360)
        for (yaw in -40..40 step 20) s.add(shoot(aim(yaw.toDouble(), 0.0)), lens, aim(yaw.toDouble(), 0.0))
        assertTrue(s.coverage() in 0.1..0.4)
        val gap = s.biggestGap(); assertNotNull(gap)
        gap!!
        assertTrue("gap at lon ${gap[0]}", abs(gap[0]) > 100) // behind you
    }

    @Test fun lookingStraightUpFillsTheTopAcrossTheSeam() {
        val s = SphereStitch(720, 360)
        assertTrue(s.add(shoot(aim(0.0, 90.0)), lens, aim(0.0, 90.0)) > 0)
        // The top band is filled all the way round, including both sides of the back seam.
        assertTrue((0 until 36).all { s.bins[it] })
        val px = s.pixels(); val want = world(doubleArrayOf(0.0, 1.0, 0.0))
        assertTrue(abs(((px[1 * 720 + 5] shr 8) and 0xFF) - ((want shr 8) and 0xFF)) < 20)
    }
}
