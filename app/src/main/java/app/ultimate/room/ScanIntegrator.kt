package app.ultimate.room

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/** One depth picture with everything needed to place it in the room. [mm] is millimetres along the viewing direction (0 = no reading); [conf] is 0..255 or null. */
class DepthFrame(val w: Int, val h: Int, val mm: ShortArray, val conf: ByteArray?, val fx: Float, val fy: Float, val cx: Float, val cy: Float, val pose: FloatArray, val timeNs: Long)

/** Why frames were or were not used: the first thing to read when a scan on a real phone looks wrong. */
class IntegratorCounters {
    var frames = 0; var duplicate = 0; var tooFast = 0; var tooClose = 0; var integrated = 0
    var pixels = 0L; var lowConfidence = 0L; var outOfRange = 0L; var edges = 0L; var kept = 0L
    var camMinY = Float.POSITIVE_INFINITY; var camMaxY = Float.NEGATIVE_INFINITY; var maxPitchDeg = -90f
    fun summary() = "frames $frames · used $integrated · repeats $duplicate · too fast $tooFast · too similar $tooClose · pixels kept ${if (pixels > 0) (kept * 100 / pixels) else 0}% (low confidence ${if (pixels > 0) (lowConfidence * 100 / pixels) else 0}%, out of range ${if (pixels > 0) (outOfRange * 100 / pixels) else 0}%, edges ${if (pixels > 0) (edges * 100 / pixels) else 0}%)"
}

/**
 * Feeds depth pictures into a [RoomMap] the careful way. A picture is skipped when it is the same as the last one (depth updates more
 * slowly than the camera, and counting a picture twice used to make noise look like a surface), when the phone is turning or moving
 * too fast for the depth to be trusted, or when the phone has hardly moved since the last key frame. A reading is dropped when its
 * confidence is very low, when it is nearer or farther than [maxRange] allows, or when it sits on a sharp depth edge (where one pixel has
 * mixed a near and a far surface into a point floating in between). What is left is added as evidence, and a sample of rays is carved
 * so that things seen through later (a hand, a foot) fade. Pure, so it is tested.
 */
class ScanIntegrator(val map: RoomMap, private val maxRange: Float = 4.5f, private val minConfidence: Int = 50, private val maxTurnDegPerS: Float = 100f, private val maxSpeed: Float = 1.2f) {
    val counters = IntegratorCounters()
    private var lastSum = Long.MIN_VALUE
    private var prevPos: FloatArray? = null; private var prevFwd: FloatArray? = null; private var prevT = 0L
    private var keyPos: FloatArray? = null; private var keyFwd: FloatArray? = null
    var keyFrames = 0; private set

    /** Notes where the camera is (call for every tracked frame), for the floor and ceiling checks: the phone is always above the floor and below the ceiling. */
    fun noteCamera(pose: FloatArray) { counters.camMinY = minOf(counters.camMinY, pose[13]); counters.camMaxY = max(counters.camMaxY, pose[13]); counters.maxPitchDeg = max(counters.maxPitchDeg, Math.toDegrees(Math.asin((-pose[9]).toDouble().coerceIn(-1.0, 1.0))).toFloat()) }

    /** Returns null when the picture was used, or the reason it was not. */
    fun integrate(f: DepthFrame): String? {
        val c = counters; c.frames++
        var sum = 1469598103934665603L; var i = 0; val step = max(1, f.mm.size / 331)
        while (i < f.mm.size) { sum = (sum xor f.mm[i].toLong()) * 1099511628211L; i += step }
        f.conf?.let { cf -> i = 0; while (i < cf.size) { sum = (sum xor cf[i].toLong()) * 1099511628211L; i += max(1, cf.size / 97) } }
        if (sum == lastSum) { c.duplicate++; return "repeat" }
        lastSum = sum
        val pos = floatArrayOf(f.pose[12], f.pose[13], f.pose[14]); val fwd = floatArrayOf(-f.pose[8], -f.pose[9], -f.pose[10])
        val pp = prevPos; val pf = prevFwd; val pt = prevT
        prevPos = pos; prevFwd = fwd; prevT = f.timeNs
        if (pp != null && pf != null && f.timeNs > pt) {
            val dt = (f.timeNs - pt) / 1e9f
            if (dt in 0.001f..0.5f && (turn(pf, fwd) / dt > Math.toRadians(maxTurnDegPerS.toDouble()) || dist(pp, pos) / dt > maxSpeed)) { c.tooFast++; return "fast" }
        }
        val kp = keyPos; val kf = keyFwd
        if (kp != null && kf != null && dist(kp, pos) < 0.03f && turn(kf, fwd) < Math.toRadians(2.5)) { c.tooClose++; return "still" }
        keyPos = pos; keyFwd = fwd; keyFrames++; c.integrated++
        val w = f.w; val h = f.h
        synchronized(map) {
            val pose = f.pose
            for (y in 0 until h) for (x in 0 until w) {
                c.pixels++
                val raw = f.mm[y * w + x].toInt() and 0xFFFF
                if (raw == 0) { c.outOfRange++; continue }
                val d = raw / 1000f
                if (d < 0.4f || d > maxRange) { c.outOfRange++; continue }
                if (f.conf != null && (f.conf[y * w + x].toInt() and 0xFF) < minConfidence) { c.lowConfidence++; continue }
                if (edge(f, x, y, d)) { c.edges++; continue }
                val nx = (x + 0.5f - f.cx) * d / f.fx; val ny = (f.cy - y - 0.5f) * d / f.fy; val nz = -d
                val px = pose[0] * nx + pose[4] * ny + pose[8] * nz + pose[12]; val py = pose[1] * nx + pose[5] * ny + pose[9] * nz + pose[13]; val pz = pose[2] * nx + pose[6] * ny + pose[10] * nz + pose[14]
                // Weight: nearer readings and ones ARCore is surer of count for more; a doubtful reading still counts a little, so plain walls fill in over several views.
                val cw = if (f.conf != null) ((f.conf[y * w + x].toInt() and 0xFF) / 160f).coerceIn(0.35f, 1f) else 1f
                map.hit(px, py, pz, keyFrames, (1.3f - d / 4f).coerceIn(0.5f, 1f) * cw)
                c.kept++
                if (x % 3 == 1 && y % 3 == 1) map.carve(pos[0], pos[1], pos[2], px, py, pz, 0.15f + 0.1f * d)
            }
        }
        return null
    }

    /** A depth pixel on a sharp step in depth (compared with the pixel to its right and below) is a blend of two surfaces, not a real one. */
    private fun edge(f: DepthFrame, x: Int, y: Int, d: Float): Boolean {
        val lim = max(0.15f, 0.12f * d)
        if (x + 1 < f.w) { val n = (f.mm[y * f.w + x + 1].toInt() and 0xFFFF) / 1000f; if (n > 0f && abs(n - d) > lim) return true }
        if (y + 1 < f.h) { val n = (f.mm[(y + 1) * f.w + x].toInt() and 0xFFFF) / 1000f; if (n > 0f && abs(n - d) > lim) return true }
        return false
    }
    private fun dist(a: FloatArray, b: FloatArray): Float { val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]; return sqrt(x * x + y * y + z * z) }
    private fun turn(a: FloatArray, b: FloatArray): Double = acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).toDouble().coerceIn(-1.0, 1.0))
}
