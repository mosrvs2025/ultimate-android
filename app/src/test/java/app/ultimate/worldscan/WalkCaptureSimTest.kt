package app.ultimate.worldscan

import app.ultimate.room.Box
import app.ultimate.room.RoomMap
import app.ultimate.room.ScanIntegrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * A simulated walk down [WorldSim.street] through the real depth pipeline ([ScanIntegrator]), to test the capture settings and measure what
 * the pipeline does to noisy depth. Simulated: these numbers say how the code behaves on data with the stated noise, not how a phone does.
 */
class WalkCaptureSimTest {
    private val fps = 10; private val pace = 1.4f

    /** Walks from x=[x0] to [x1] along z=-4.5 at [pace] m/s, looking toward the left-hand facades with a gentle sweep. */
    private fun walk(integrator: ScanIntegrator, x0: Float, x1: Float, seed: Int = 1) {
        val rnd = Random(seed); var x = x0; var t = 1_000_000_000L; var k = 0
        while (x < x1) {
            val yaw = 12f * kotlin.math.sin(k / 9.0).toFloat(); val pitch = -4f + 6f * kotlin.math.sin(k / 13.0).toFloat()
            val pose = WorldSim.cam(x, -4.5f, yaw, pitch)
            integrator.noteCamera(pose)
            integrator.integrate(WorldSim.frame(WorldSim.street, pose, rnd, t))
            x += pace / fps; t += 1_000_000_000L / fps; k++
        }
    }

    @Test fun theRoomScannersDefaultsThrowAWalkAway() {
        val room = ScanIntegrator(RoomMap(WalkSettings.VOXEL))      // a person standing in a room turning slowly
        walk(room, 2f, 14f)
        val walking = WalkSettings.integrator(RoomMap(WalkSettings.VOXEL))
        walk(walking, 2f, 14f)
        val n = room.counters.frames
        assertTrue("room defaults used ${room.counters.integrated} of $n", room.counters.integrated <= 2)
        assertTrue("room defaults called ${room.counters.tooFast} frames too fast", room.counters.tooFast >= n - 3)
        assertTrue("walk settings used ${walking.counters.integrated} of ${walking.counters.frames}", walking.counters.integrated >= walking.counters.frames * 0.9)
    }

    @Test fun aSimulatedWalkBuildsFacadesAtTheirTrueDistanceAndNeverMakesTheGroundIntoAWall() {
        val map = WalkSettings.newMap(); val integ = WalkSettings.integrator(map)
        walk(integ, 2f, 38f)
        // True facade plane for the buildings on the left side of the walked stretch (3.5 and 4.5 m from the path).
        fun planeZ(x: Float): Float? = when { x in 0f..18f -> -8f; x in 24f..40f -> -9f; else -> null }
        val zs = HashMap<Int, ArrayList<Float>>()
        map.forEachSolid { x, y, z ->
            val pz = planeZ(x)
            if (y in 0.9f..3.2f && pz != null && z in (pz - 0.7f)..(pz + 0.7f)) zs.getOrPut((x * 2).toInt()) { ArrayList() } += z
        }
        // Where the facade is: the centre of the cubes in each half-metre of it. (Cube-by-cube spread follows the depth noise by design: the map
        // counts readings, it does not average them; the centre of the readings is what is unbiased.)
        val centreErr = ArrayList<Float>(); val thick = ArrayList<Float>()
        for ((bin, list) in zs) { if (list.size < 5) continue; val pz = planeZ((bin + 0.5f) / 2f) ?: continue; val mean = list.average().toFloat(); centreErr += abs(mean - pz); thick += kotlin.math.sqrt(list.map { (it - mean) * (it - mean) }.average()).toFloat() }
        centreErr.sort(); thick.sort()
        val median = centreErr[centreErr.size / 2]; val p90 = centreErr[(centreErr.size * 0.9).toInt()]; val thickness = thick[thick.size / 2]
        val want = (4..35).count { planeZ(it.toFloat()) != null } * 2
        val coverage = zs.count { it.value.size >= 5 && it.key / 2 in 4..35 }.toDouble() / want
        println("SIM walk: ${integ.counters.integrated}/${integ.counters.frames} frames used, ${map.solid} cubes, facade centre error median ${"%.1f".format(median * 100)} cm, p90 ${"%.1f".format(p90 * 100)} cm, thickness (1 sd) ${"%.1f".format(thickness * 100)} cm, coverage ${"%.0f".format(coverage * 100)}%")
        assertTrue("facade centre error median ${median * 100} cm", median < 0.05f)
        assertTrue("facade centre error p90 ${p90 * 100} cm", p90 < 0.12f)
        assertTrue("thickness ${thickness * 100} cm", thickness < 0.15f)
        assertTrue("coverage $coverage", coverage >= 0.85)
        // The structure the aligner will use is made of upright columns, not the pavement: none may be on the walked strip.
        val s = Structure.of(map)
        assertTrue("structure columns ${s.size}", s.size > 100)
        var bad = 0
        for (i in 0 until s.size) { val z = s.zs[i]; if (z > -6.0f && z < -3.0f) bad++ }
        assertTrue("$bad structure columns on the pavement of ${s.size}", bad <= s.size * 0.03)
    }

    // ---------------------------------------------------------------- two whole walks, end to end
    private fun rotY(m: FloatArray, a: Float): FloatArray {
        val c = kotlin.math.cos(a); val s = kotlin.math.sin(a); val o = FloatArray(16)
        for (col in 0 until 4) { val x = m[col * 4]; val z = m[col * 4 + 2]; o[col * 4] = x * c + z * s; o[col * 4 + 1] = m[col * 4 + 1]; o[col * 4 + 2] = -x * s + z * c; o[col * 4 + 3] = m[col * 4 + 3] }
        return o
    }

    /**
     * Walks the street from [x0] to [x1] along z=-4.5 the way [WalkScan] captures it: depth into the walking settings' integrator, a new piece every
     * [WalkSettings.PIECE_LENGTH_M] of path. The phone's own frame is the street turned by [yaw] and shifted by ([tx], [tz]) with its origin at start
     * height (as ARCore's is), and it drifts: its heading is [driftDegPerM] degrees further wrong for every metre walked.
     */
    private fun walkPieces(seed: Int, yaw: Float, tx: Float, tz: Float, x0: Float, x1: Float, driftDegPerM: Float): List<WalkChunk> {
        val rnd = Random(seed); var x = x0; var t = 1_000_000_000L; var k = 0; var path = 0.0
        val out = ArrayList<WalkChunk>(); var map = WalkSettings.newMap(); var integ = WalkSettings.integrator(map); var t0 = t
        while (x < x1) {
            val world = WorldSim.cam(x, -4.5f, 12f * kotlin.math.sin(k / 9.0).toFloat(), -4f + 6f * kotlin.math.sin(k / 13.0).toFloat())
            val session = rotY(app.ultimate.room.Sim.toSession(world, yaw, tx, -1.4f, tz), Math.toRadians((driftDegPerM * (x - x0)).toDouble()).toFloat())
            integ.noteCamera(session); integ.integrate(WorldSim.frame(WorldSim.street, world, rnd, t, sessionPose = session))
            x += pace / fps; t += 1_000_000_000L / fps; k++; path += pace / fps
            if (path >= WalkSettings.PIECE_LENGTH_M) { out += WalkChunk(map, t0, t); map = WalkSettings.newMap(); integ = WalkSettings.integrator(map); t0 = t; path = 0.0 }
        }
        if (map.solid > 50) out += WalkChunk(map, t0, t)
        return out
    }

    @Test fun twoSimulatedWalksOfTheSameStreetRegisterAndTheSecondImprovesTheFirst() {
        // 0.012 degrees of heading error per metre is about what motion tracking drifts (1% of the distance); 0.04 is a bad walk.
        for ((drift, mustRefine) in listOf(0.012f to false, 0.04f to true)) {
            val first = walkPieces(seed = 1, yaw = 0f, tx = 0f, tz = 0f, x0 = 2f, x1 = 60f, driftDegPerM = 0f)
            val second = walkPieces(seed = 2, yaw = 23.5f, tx = 7.2f, tz = -4.1f, x0 = 4f, x1 = 58f, driftDegPerM = drift)
            assertTrue("pieces ${first.size}/${second.size}", first.size >= 3 && second.size >= 3)
            val dir = java.io.File(System.getProperty("java.io.tmpdir"), "ws2e" + System.nanoTime()); val store = WalkStore(dir)
            fun input(id: String, pieces: List<WalkChunk>) = WalkInput(id, id, 0L, 1L, WalkTrack().also { tr -> for (i in 0..200) tr.add(PosePoint(i * 100_000_000L, i * 0.28f, 0f, 0f, 0f, 0f, 0f, 1f, TrackingQuality.TRACKING)) }, pieces, null, emptyList(), null, null, 0, "")
            val pid = store.save(input("a", first), null).place.id
            val cubesBefore = store.baseMap(pid)!!.solid
            val m = store.save(input("b", second), pid).merge!!
            println("SIM merge (drift $drift deg/m): ${m.summary()} | whole-walk fit ${"%.1f".format((m.global?.meanDist ?: 0f) * 100)} cm, yaw ${m.global?.alignment?.yawDeg}, margin ${m.global?.margin}")
            assertTrue(m.summary(), m.accepted)
            // Both sessions have their origin at start height, so the true height shift between them is zero; the true turn and shift are those of Sim.truth.
            val truth = app.ultimate.room.Sim.truth(23.5f, 7.2f, -1.4f, -4.1f)
            val g = m.global!!.alignment
            assertTrue("yaw ${g.yawDeg} vs ${truth.yawDeg}", abs(((g.yawDeg - truth.yawDeg + 540f) % 360f) - 180f) < 1.5f + drift * 40f)
            assertEquals(0f, m.dy!!, 0.15f)
            assertTrue("used ${m.used} of ${second.size}", m.used >= second.size - 1)
            assertTrue("average fit ${m.meanCm} cm", m.meanCm < 25f)
            if (mustRefine) assertTrue("fitted individually ${m.refined}", m.refined >= 1)
            val after = store.baseMap(pid)!!.solid
            println("SIM merge: cubes $cubesBefore -> $after")
            assertTrue("map shrank: $cubesBefore -> $after", after >= cubesBefore * 0.97)
            dir.deleteRecursively()
        }
    }
}
