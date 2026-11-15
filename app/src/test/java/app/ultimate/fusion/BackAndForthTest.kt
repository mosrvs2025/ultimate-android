package app.ultimate.fusion

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Walking back and forth along one straight line, phone in hand, no camera: the case where a route drawn from step
 * headings alone turns into a scribble, because the heading drifts a little more at every turn-around. With GPS good to
 * a few metres the drawn route must stay a line.
 */
class BackAndForthTest {
    @Test fun aStraightLineStaysALine() {
        for (seed in 1L..4L) {
            val c = SimConfig(seed = seed, course = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(20.0, 0.0)), laps = 8, camera = false, gyroBias = 0.04)
            val sim = WalkSim.run(c)
            val r = MovementAnalyzer.analyze(Trace.read(sim.lines.asSequence()), null, "test", "walk")
            val p = r.path!!
            // Its spread across the walking direction can be no better than GPS's own noise (~4 m here), but no worse either,
            // and the shape must read as a line (long one way, narrow the other), not the blob that drifting step headings drew.
            val (minor, major) = spread(p.e, p.n)
            val tr = Trace.read(sim.lines.asSequence()); val g = GpsSource.of(tr)!!
            val gp = (0 until sim.durationS.toInt()).mapNotNull { g.position(it.toDouble()) }
            val gpsMinor = spread(gp.map { it[0] }.toDoubleArray(), gp.map { it[1] }.toDoubleArray()).first
            assertTrue("seed $seed: across ${"%.1f".format(minor)} m vs GPS ${"%.1f".format(gpsMinor)} m, along ${"%.1f".format(major)} m",
                minor <= gpsMinor * 1.25 + 0.5 && minor < 0.62 * major)
            // The distance: GPS cuts every turn-around short, so it must not drag the total down.
            assertTrue("seed $seed: distance ${"%.0f".format(r.distance!!.value)} vs ${"%.0f".format(sim.truthMetres)} m", abs(r.distance!!.value - sim.truthMetres) < sim.truthMetres * 0.10)
            // The drawn route's own length follows the steps; it must not be inflated by GPS jitter or shrunk by its corner-cutting.
            assertTrue("seed $seed: route length ${"%.0f".format(p.length)} vs ${"%.0f".format(sim.truthMetres)} m", abs(p.length - sim.truthMetres) < sim.truthMetres * 0.15)
        }
    }

    private fun spread(e: DoubleArray, n: DoubleArray): Pair<Double, Double> {
        val me = e.average(); val mn = n.average(); var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in e.indices) { val x = e[i] - me; val y = n[i] - mn; sxx += x * x; syy += y * y; sxy += x * y }
        val tr = (sxx + syy) / e.size; val det = (sxx * syy - sxy * sxy) / (e.size.toDouble() * e.size)
        val d = kotlin.math.sqrt(maxOf(0.0, tr * tr / 4 - det))
        return kotlin.math.sqrt(maxOf(0.0, tr / 2 - d)) to kotlin.math.sqrt(tr / 2 + d)
    }
}
