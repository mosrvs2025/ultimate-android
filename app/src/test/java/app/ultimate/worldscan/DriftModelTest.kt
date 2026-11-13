package app.ultimate.worldscan

import app.ultimate.room.P3
import app.ultimate.room.Sim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Drift correction against walks with a known truth. The walks are made up (an L of 300 m + 300 m with simulated GPS errors: a slowly-varying bias plus
 * white noise) and the drift is injected by us, so these tests show the fitting works on data with the defects it assumes, and that it declines on data
 * without them. How real motion tracking drifts is a device question.
 */
class DriftModelTest {
    private val anchor = GeoAnchor(34.2164, -119.0376, 30.0)
    private val theta = Math.toRadians(25.0)
    private val start = 30_000_000_000_000L

    /** The path the phone really walked: east then north, 1.4 m/s, ten poses a second, in the walk's frame (x east, -z north). */
    private fun truePath(leg: Double = 300.0): WalkTrack {
        val t = WalkTrack(); val n = (2 * leg / 1.4 * 10).toInt()
        for (i in 0..n) { val d = i * 0.14; val ax = if (d <= leg) d else leg; val ay = if (d <= leg) 0.0 else d - leg; t.add(PosePoint(start + i * 100_000_000L, ax.toFloat(), 0f, (-ay).toFloat(), 0f, 0f, 0f, 1f, TrackingQuality.TRACKING)) }
        return t
    }

    /** Motion tracking drift of a different shape from the model: the heading takes a random walk with [sigmaDegPerRootM], and steps are [scale] long. */
    private fun randomWalkDrift(truth: WalkTrack, sigmaDegPerRootM: Double, scale: Double, seed: Int): WalkTrack {
        val rnd = Random(seed); val out = WalkTrack(); var ang = 0.0; var ax = truth.points[0].x.toDouble(); var ay = -truth.points[0].z.toDouble()
        out.add(truth.points[0])
        for (k in 1 until truth.points.size) {
            val dx = (truth.points[k].x - truth.points[k - 1].x).toDouble(); val dy = -(truth.points[k].z - truth.points[k - 1].z).toDouble()
            ang += Math.toRadians(Sim.gauss(rnd) * sigmaDegPerRootM * sqrt(hypot(dx, dy)))
            ax += scale * (cos(ang) * dx - sin(ang) * dy); ay += scale * (sin(ang) * dx + cos(ang) * dy)
            out.add(PosePoint(truth.points[k].tNs, ax.toFloat(), 0f, (-ay).toFloat(), 0f, 0f, 0f, 1f, TrackingQuality.TRACKING))
        }
        return out
    }
    private fun sqrt(v: Double) = kotlin.math.sqrt(v)

    /** GPS fixes for the walk that was REALLY walked, with a slow bias plus white noise, reported accuracy 5 m. */
    private fun gps(truth: WalkTrack, seed: Int, bias: Double = 0.8, noise: Double = 1.5): List<GeoFix> {
        val rnd = Random(seed); val out = ArrayList<GeoFix>(); var bx = 0.0; var by = 0.0
        val c = cos(theta); val s = sin(theta)
        var sec = 1
        while (start + sec * 1_000_000_000L <= truth.points.last().tNs) {
            val p = truth.at(start + sec * 1_000_000_000L)!!; val ax = p.x.toDouble(); val ay = -p.z.toDouble()
            bx = 0.9 * bx + Sim.gauss(rnd) * bias; by = 0.9 * by + Sim.gauss(rnd) * bias
            val ll = anchor.unproject(c * ax - s * ay + 40.0 + bx + Sim.gauss(rnd) * noise, s * ax + c * ay - 10.0 + by + Sim.gauss(rnd) * noise)
            out += GeoFix(ll.first, ll.second, null, 5f, start + sec * 1_000_000_000L); sec++
        }
        return out
    }
    private fun endError(a: WalkTrack, truth: WalkTrack): Double { val p = a.points.last(); val q = truth.points.last(); return hypot((p.x - q.x).toDouble(), (p.z - q.z).toDouble()) }

    @Test fun theFitFindsInjectedDriftAndTheCorrectedWalkIsCloserToTheTruth() {
        val truth = truePath(); val raw = DriftModel.apply(truth, 0.006, 0.01)
        val fixes = gps(truth, 1)
        val fit = DriftModel.fit(raw, fixes, anchor)
        println("SIM drift: a ${"%.4f".format(fit.aDegPerM)} deg/m (injected -0.006 undone), b ${"%.4f".format(fit.b)}; disagreement ${"%.1f".format(fit.rmsBeforeM)} -> ${"%.1f".format(fit.rmsAfterM)} m; ${fit.why}")
        assertTrue(fit.why, fit.applicable)
        val corrected = DriftModel.apply(raw, fit.aDegPerM, fit.b)
        println("SIM drift: end of walk off by ${"%.1f".format(endError(raw, truth))} m raw, ${"%.1f".format(endError(corrected, truth))} m corrected")
        assertTrue("raw ${endError(raw, truth)} corrected ${endError(corrected, truth)}", endError(corrected, truth) < 0.5 * endError(raw, truth))
    }

    @Test fun driftOfAnotherShapeIsStillReduced() {
        val truth = truePath(); val raw = randomWalkDrift(truth, 0.15, 1.01, 3)
        val fit = DriftModel.fit(raw, gps(truth, 2), anchor)
        println("SIM drift (random-walk heading): disagreement ${"%.1f".format(fit.rmsBeforeM)} -> ${"%.1f".format(fit.rmsAfterM)} m, applicable ${fit.applicable}")
        assertTrue("before ${fit.rmsBeforeM} after ${fit.rmsAfterM}", fit.rmsAfterM < fit.rmsBeforeM)
    }

    @Test fun aWalkWithoutDriftIsLeftAlone() {
        val truth = truePath(); val fit = DriftModel.fit(truth, gps(truth, 4), anchor)
        println("SIM drift (none injected): applicable ${fit.applicable}: ${fit.why}")
        assertFalse(fit.why, fit.applicable)
    }

    @Test fun aShortWalkIsNotOfferedACorrection() {
        val truth = truePath(60.0); val fit = DriftModel.fit(DriftModel.apply(truth, 0.01, 0.0), gps(truth, 5), anchor)
        assertFalse(fit.applicable); assertTrue(fit.why, fit.why.contains("at least"))
    }

    @Test fun eachPiecesRigidMoveCarriesItsEndsOntoTheCorrectedPath() {
        val truth = truePath(); val raw = DriftModel.apply(truth, 0.006, 0.01); val corrected = DriftModel.apply(raw, -0.006, -0.0099)
        val spans = (0 until 28).map { longArrayOf(start + it * 15_000_000_000L, start + (it + 1) * 15_000_000_000L - 1) }
        val moves = DriftModel.pieceMoves(raw, corrected, spans)
        var worst = 0.0
        spans.forEachIndexed { i, sp ->
            for (t in sp) { val r = raw.at(t)!!; val c = corrected.at(t)!!; val q = moves[i].apply(P3(r.x, 0f, r.z)); worst = maxOf(worst, hypot((q.x - c.x).toDouble(), (q.z - c.z).toDouble())) }
        }
        println("SIM drift: worst piece-end error after the rigid moves ${"%.3f".format(worst)} m")
        assertTrue("worst $worst m", worst < 0.06)
    }

    // ---------------------------------------------------------------- the store
    private fun walkInput(raw: WalkTrack, fixes: List<GeoFix>): WalkInput {
        // Each 15 s piece of the map is a row of cubes 4 m to the left of where the walk (as the phone saw it) went.
        val pieces = (0 until 40).map { i ->
            val t0 = start + i * 15_000_000_000L; val t1 = t0 + 15_000_000_000L - 1; val m = WalkSettings.newMap()
            raw.points.filter { it.tNs in t0..t1 }.forEachIndexed { k, p -> if (k % 5 == 0) for (v in 1..3) m.hit(p.x - 4f, 1f, p.z, v, 1f) }
            WalkChunk(m, t0, t1)
        }
        return WalkInput("w", "Walk", 0L, 1L, raw, pieces, null, fixes, null, null, 10, "")
    }

    @Test fun correctingAWalkRewritesItAndRevertingPutsTheRawWalkBack() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wsdrift" + System.nanoTime()); val store = WalkStore(dir)
        val truth = truePath(); val raw = DriftModel.apply(truth, 0.006, 0.01)
        val r = store.save(walkInput(raw, gps(truth, 1)), null); val pid = r.place.id
        val before = r.walk
        assertNotNull(before.drift); assertTrue(before.drift!!.summary(), before.drift!!.applicable); assertFalse(before.drift!!.applied)
        val cubes0 = store.baseMap(pid)!!.solid
        assertNull(store.applyDrift(pid, "w"))
        val after = store.place(pid)!!.walks.single()
        assertTrue(after.drift!!.applied)
        val corrected = store.track(pid, "w")!!
        assertTrue("end ${endError(corrected, truth)} vs raw ${endError(raw, truth)}", endError(corrected, truth) < 0.5 * endError(raw, truth))
        assertTrue("GPS fit ${before.geo!!.rmsM} -> ${after.geo!!.rmsM}", after.geo!!.rmsM < before.geo!!.rmsM)
        assertEquals(cubes0.toDouble(), store.baseMap(pid)!!.solid.toDouble(), cubes0 * 0.05)
        assertEquals("applying twice is refused", "The correction is already applied.", store.applyDrift(pid, "w"))
        // Undo: the raw path is back exactly and the correction is offered again.
        assertNull(store.revertDrift(pid, "w"))
        val back = store.track(pid, "w")!!
        assertEquals(raw.points.last().x, back.points.last().x, 1e-3f); assertEquals(raw.points.last().z, back.points.last().z, 1e-3f)
        assertFalse(store.place(pid)!!.walks.single().drift!!.applied)
        dir.deleteRecursively()
    }
}
