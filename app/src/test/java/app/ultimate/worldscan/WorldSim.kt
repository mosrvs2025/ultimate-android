package app.ultimate.worldscan

import app.ultimate.room.Alignment
import app.ultimate.room.Box
import app.ultimate.room.DepthFrame
import app.ultimate.room.P3
import app.ultimate.room.RoomMap
import app.ultimate.room.Sim
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * A made-up street for TESTING THE ALGORITHMS ONLY: a ground plane and boxes (buildings, cars, poles), seen by a ray-cast depth camera with
 * noise. Nothing here is, or is presented as, a measurement of a real place: the app itself never fabricates sensor data. What these tests
 * can show is that the code recovers a known truth from data with known defects; how it does on a phone in a street is a separate, device question.
 */
object WorldSim {
    /** A street along +x with building fronts on both sides, cross-street gaps, setbacks, parked cars and poles: varied enough that no slide along it fits equally well. */
    val street: List<Box> = listOf(
        Box(0f, 0f, -14f, 18f, 9f, -8f), Box(24f, 0f, -15f, 40f, 12f, -9f), Box(40f, 0f, -13f, 46f, 6f, -8f), Box(48f, 0f, -16f, 70f, 11f, -8.5f),
        Box(0f, 0f, 8f, 12f, 7f, 15f), Box(15f, 0f, 10f, 33f, 10f, 16f), Box(36f, 0f, 8f, 60f, 8f, 14f), Box(63f, 0f, 9f, 80f, 12f, 15f),
        Box(5f, 0f, -6.2f, 9.2f, 1.5f, -4.5f), Box(30f, 0f, 6.5f, 34.5f, 1.5f, 8f), Box(52f, 0f, -6.5f, 56.4f, 1.5f, -5f),
        Box(12f, 0f, -7f, 12.3f, 6f, -6.7f), Box(26f, 0f, 7f, 26.3f, 6f, 7.3f), Box(44f, 0f, -7f, 44.3f, 6f, -6.7f),
    )
    /** One flat wall and nothing else: a long straight wall can slide along itself, so a fit to it must be reported as ambiguous. */
    val flatWall: List<Box> = listOf(Box(0f, 0f, -12f, 120f, 8f, -8f))

    fun gauss(r: Random): Float = Sim.gauss(r)

    /** Cubes on the faces of [boxes] (and, if [ground], on the ground plane) as a settled map: each point seen from three viewpoints, as a real scan needs. */
    fun mapOf(boxes: List<Box>, xRange: ClosedFloatingPointRange<Float>, rnd: Random, noise: Float = 0.02f, ground: Boolean = true, size: Float = 0.10f, step: Float = 0.08f): RoomMap {
        val map = RoomMap(size)
        fun add(x: Float, y: Float, z: Float) { if (x !in xRange) return; val nx = x + gauss(rnd) * noise; val ny = y + gauss(rnd) * noise; val nz = z + gauss(rnd) * noise; for (v in 1..3) map.hit(nx, ny, nz, v, 1f) }
        for (b in boxes) {
            var x = b.x0
            while (x <= b.x1) { var y = b.y0; while (y <= b.y1) { add(x, y, b.z0); add(x, y, b.z1); y += step }; x += step }
            var z = b.z0; while (z <= b.z1) { var y = b.y0; while (y <= b.y1) { add(b.x0, y, z); add(b.x1, y, z); y += step }; z += step }
        }
        if (ground) { var x = xRange.start; while (x <= xRange.endInclusive) { var z = -10f; while (z <= 10f) { add(x, 0f, z); z += 0.2f }; x += 0.2f } }
        return map
    }

    /** The same map moved into another session's frame: the saved world is [a] applied to the session's points, so the session sees [a] inverted. */
    fun inSession(world: RoomMap, a: Alignment): RoomMap = world.mapped { a.invert(it) }

    /** A ray-cast depth picture of [boxes] and the ground from the camera [pose] (column-major, ARCore convention), with [noise] metres of range noise. */
    fun frame(boxes: List<Box>, pose: FloatArray, rnd: Random, tNs: Long, noise: Float = 0.01f, sessionPose: FloatArray = pose): DepthFrame {
        val w = Sim.W; val h = Sim.H; val f = Sim.F
        val mm = ShortArray(w * h); val conf = ByteArray(w * h)
        val o = floatArrayOf(pose[12], pose[13], pose[14])
        for (y in 0 until h) for (x in 0 until w) {
            val dc = floatArrayOf((x + 0.5f - w / 2f) / f, (h / 2f - y - 0.5f) / f, -1f)
            val d = floatArrayOf(pose[0] * dc[0] + pose[4] * dc[1] + pose[8] * dc[2], pose[1] * dc[0] + pose[5] * dc[1] + pose[9] * dc[2], pose[2] * dc[0] + pose[6] * dc[1] + pose[10] * dc[2])
            var t = hit(boxes, o, d)
            t += gauss(rnd) * (noise + 0.004f * t * t)
            mm[y * w + x] = if (t in 0.2f..65f) (t * 1000f).toInt().toShort() else 0
            conf[y * w + x] = (120 + rnd.nextInt(130)).toByte()
        }
        return DepthFrame(w, h, mm, conf, f, f, w / 2f, h / 2f, sessionPose, tNs, null)
    }

    private fun hit(boxes: List<Box>, o: FloatArray, d: FloatArray): Float {
        var best = Float.MAX_VALUE
        if (d[1] < -1e-4f) { val t = -o[1] / d[1]; if (t > 0.05f) best = t }
        for (b in boxes) {
            var t0 = 0.05f; var t1 = best
            val lo = floatArrayOf(b.x0, b.y0, b.z0); val hi = floatArrayOf(b.x1, b.y1, b.z1)
            var ok = true
            for (a in 0..2) {
                if (abs(d[a]) < 1e-6f) { if (o[a] < lo[a] || o[a] > hi[a]) { ok = false; break } }
                else { var ta = (lo[a] - o[a]) / d[a]; var tb = (hi[a] - o[a]) / d[a]; if (ta > tb) { val s = ta; ta = tb; tb = s }; t0 = max(t0, ta); t1 = min(t1, tb); if (t0 > t1) { ok = false; break } }
            }
            if (ok && t0 < best) best = t0
        }
        return best
    }

    /** Camera at [x],[z] (height 1.4) looking along [yawDeg] (0 = toward -z, -90 = toward +x) and tilted [pitchDeg]. */
    fun cam(x: Float, z: Float, yawDeg: Float, pitchDeg: Float = 0f, y: Float = 1.4f): FloatArray = Sim.matrix(app.ultimate.room.Cam(x, y, z, yawDeg, pitchDeg))

    fun p(x: Float, y: Float, z: Float) = P3(x, y, z)
}
