package app.ultimate.worldscan

import app.ultimate.room.Box
import app.ultimate.room.RoomMap
import app.ultimate.room.ScanIntegrator
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
}
