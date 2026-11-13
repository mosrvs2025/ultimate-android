package app.ultimate.worldscan

import app.ultimate.room.Alignment
import app.ultimate.room.RoomMap
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The upright things of a walk seen from above (facades, walls, trunks, parked cars): one point per [cell]-sized column that has a tall stack of solid cubes. */
class Structure(val xs: FloatArray, val zs: FloatArray) {
    val size: Int get() = xs.size
    fun cx(): Float = if (size == 0) 0f else xs.average().toFloat()
    fun cz(): Float = if (size == 0) 0f else zs.average().toFloat()
    fun extent(): Float = if (size == 0) 0f else max(xs.max() - xs.min(), zs.max() - zs.min())

    companion object {
        private const val OFF = 1 shl 20
        /** Columns with at least [minCubes] solid cubes spanning at least [minSpan] metres of height. Flat ground does not qualify, so it cannot make two walks look alike. */
        fun of(map: RoomMap, cell: Float = 0.25f, minSpan: Float = 0.7f, minCubes: Int = 4): Structure {
            val cols = HashMap<Long, FloatArray>()
            map.forEachSolid { x, y, z ->
                val k = ((floor(x / cell).toInt() + OFF).toLong() shl 32) or (floor(z / cell).toInt() + OFF).toLong()
                val c = cols.getOrPut(k) { floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, 0f) }
                if (y < c[0]) c[0] = y; if (y > c[1]) c[1] = y; c[2]++
            }
            val xs = ArrayList<Float>(); val zs = ArrayList<Float>()
            for ((k, c) in cols) if (c[2] >= minCubes && c[1] - c[0] >= minSpan) {
                xs += (((k shr 32).toInt() - OFF) + 0.5f) * cell; zs += (((k and 0xFFFFFFFFL).toInt() - OFF) + 0.5f) * cell
            }
            return Structure(xs.toFloatArray(), zs.toFloatArray())
        }
    }
}

/** Distance (metres, capped at [trunc]) from any ground position to the nearest structure column, on a grid: the map every candidate pose is scored against. */
class DistanceField(val x0: Float, val z0: Float, val cell: Float, val w: Int, val h: Int, private val d: FloatArray, val trunc: Float) {
    fun at(x: Float, z: Float): Float {
        val fx = (x - x0) / cell - 0.5f; val fz = (z - z0) / cell - 0.5f
        val ix = floor(fx).toInt(); val iz = floor(fz).toInt()
        if (ix < 0 || iz < 0 || ix + 1 >= w || iz + 1 >= h) return trunc
        val ax = fx - ix; val az = fz - iz; val i = iz * w + ix
        return (d[i] * (1 - ax) + d[i + 1] * ax) * (1 - az) + (d[i + w] * (1 - ax) + d[i + w + 1] * ax) * az
    }

    companion object {
        fun of(s: Structure, cell0: Float, trunc: Float): DistanceField {
            var cell = cell0
            val minX = s.xs.min() - trunc; val minZ = s.zs.min() - trunc; val spanX = s.xs.max() - s.xs.min() + 2 * trunc; val spanZ = s.zs.max() - s.zs.min() + 2 * trunc
            while ((spanX / cell + 2) * (spanZ / cell + 2) > 6_000_000) cell *= 2f
            val w = ceil(spanX / cell).toInt() + 2; val h = ceil(spanZ / cell).toInt() + 2
            val d = FloatArray(w * h) { trunc }
            for (i in 0 until s.size) { val ix = ((s.xs[i] - minX) / cell).toInt().coerceIn(0, w - 1); val iz = ((s.zs[i] - minZ) / cell).toInt().coerceIn(0, h - 1); d[iz * w + ix] = 0f }
            val dg = cell * 1.4142f
            for (z in 0 until h) for (x in 0 until w) {   // forward pass
                val i = z * w + x; var v = d[i]
                if (x > 0) v = min(v, d[i - 1] + cell); if (z > 0) { v = min(v, d[i - w] + cell); if (x > 0) v = min(v, d[i - w - 1] + dg); if (x < w - 1) v = min(v, d[i - w + 1] + dg) }
                d[i] = min(v, trunc)
            }
            for (z in h - 1 downTo 0) for (x in w - 1 downTo 0) {   // backward pass
                val i = z * w + x; var v = d[i]
                if (x < w - 1) v = min(v, d[i + 1] + cell); if (z < h - 1) { v = min(v, d[i + w] + cell); if (x < w - 1) v = min(v, d[i + w + 1] + dg); if (x > 0) v = min(v, d[i + w - 1] + dg) }
                d[i] = min(v, trunc)
            }
            return DistanceField(minX, minZ, cell, w, h, d, trunc)
        }
    }
}

/** Where the search may look: a turn of [yawDeg] ± [yawRadius] and a shift of ([dx], [dz]) ± [radius] metres (the fresh walk's origin, written in the saved walk's frame). */
class Prior(val yawDeg: Float, val yawRadius: Float, val dx: Float, val dz: Float, val radius: Float)

/** How well one walk fits another. [meanDist] is the average distance (m, each capped at 1 m) from the fresh structure to the saved structure after the move; [inlier] the share within 30 cm. */
class Registration(val alignment: Alignment, val meanDist: Float, val inlier: Float, val matched: Int, val margin: Float, val ambiguous: Boolean)

/**
 * Finds the turn and shift that lay one walk's upright structure onto another's. Two phone sessions never share a coordinate frame (each
 * starts facing an arbitrary way) and each drifts a little, so this is a search, not a lookup: every candidate pose is scored by how close the
 * fresh structure lands to the saved structure (a chamfer score on a distance field, which is smooth, so a nearly-right pose scores nearly
 * right and a hill-climb can finish the job), a coarse sweep keeps the best few distinct poses, and each is polished. A rival pose that
 * scores almost as well (a long straight wall can slide along itself) is reported as [Registration.ambiguous], and callers must not trust
 * such a fit. Pure, so it is tested.
 *
 * (The room scanner's nearest-point alignment was tried first for walks and rejected: it locks onto the voxel grid, a bias of 8-17 cm.)
 */
object WalkAligner {
    private const val T = 2.0f      // distance cap while searching: wide enough for a pose a metre off to still feel its way in
    private const val CAP = 1.0f    // distance cap for the reported score

    fun register(fresh: Structure, saved: Structure, prior: Prior?, minCells: Int = 60, prebuilt: DistanceField? = null): Registration? {
        if (fresh.size < minCells || saved.size < minCells) return null
        val field = prebuilt ?: DistanceField.of(saved, 0.25f, T)
        // --- coarse sweep over the allowed poses, scored on a sample of the fresh structure
        var sampleN = min(300, fresh.size); val sx = FloatArray(sampleN); val sz = FloatArray(sampleN)
        run { for (i in 0 until sampleN) { val j = (i.toLong() * fresh.size / sampleN).toInt(); sx[i] = fresh.xs[j]; sz[i] = fresh.zs[j] } }
        val yaws = ArrayList<Float>(); val shifts = ArrayList<FloatArray>()
        val tStep: Float
        if (prior != null) {
            val ys = max(1f, prior.yawRadius / 30f); var y = prior.yawDeg - prior.yawRadius; while (y <= prior.yawDeg + prior.yawRadius + 1e-3f) { yaws += y; y += ys }
            tStep = max(0.5f, prior.radius / 30f)
            var a = -prior.radius; while (a <= prior.radius + 1e-3f) { var b = -prior.radius; while (b <= prior.radius + 1e-3f) { shifts += floatArrayOf(prior.dx + a, prior.dz + b); b += tStep }; a += tStep }
        } else {
            var y = 0f; while (y < 360f) { yaws += y; y += 3f }
            val r = min(25f, max(6f, 0.4f * max(fresh.extent(), saved.extent()))); tStep = max(0.75f, r / 25f)
            var a = -r; while (a <= r + 1e-3f) { var b = -r; while (b <= r + 1e-3f) { shifts += floatArrayOf(a, b); b += tStep }; a += tStep }
        }
        val perYaw = shifts.size.toLong()
        while (yaws.size * perYaw * sampleN > 400_000_000L && sampleN > 80) sampleN = max(80, sampleN * 2 / 3)
        val best = PriorityQueue<DoubleArray>(256, compareByDescending<DoubleArray> { it[0] })   // [score, yaw, tx, tz], worst first
        for (yaw in yaws) {
            val rad = Math.toRadians(yaw.toDouble()); val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
            // Without a prior the shift is relative to centroid-to-centroid, which removes most of the search.
            val base = if (prior != null) floatArrayOf(0f, 0f) else floatArrayOf(saved.cx() - (fresh.cx() * c - fresh.cz() * s), saved.cz() - (fresh.cx() * s + fresh.cz() * c))
            for (sh in shifts) {
                val tx = sh[0] + base[0]; val tz = sh[1] + base[1]
                var total = 0f
                for (i in 0 until sampleN) total += field.at(sx[i] * c - sz[i] * s + tx, sx[i] * s + sz[i] * c + tz)
                val score = total.toDouble() / sampleN
                if (best.size < 240) best.add(doubleArrayOf(score, yaw.toDouble(), tx.toDouble(), tz.toDouble()))
                else if (score < best.peek()[0]) { best.poll(); best.add(doubleArrayOf(score, yaw.toDouble(), tx.toDouble(), tz.toDouble())) }
            }
        }
        val ranked = best.toList().sortedBy { it[0] }
        // --- keep a handful of distinct poses and polish each on (up to) 2500 fresh points
        val picks = ArrayList<DoubleArray>()
        for (c in ranked) { if (picks.none { poseApart(it, c, 4.0, 1.5) < 1.0 }) picks += c; if (picks.size >= 6) break }
        val m = min(2500, fresh.size); val fx = FloatArray(m); val fz = FloatArray(m)
        for (i in 0 until m) { val j = (i.toLong() * fresh.size / m).toInt(); fx[i] = fresh.xs[j]; fz[i] = fresh.zs[j] }
        val refined = picks.map { polish(field, fx, fz, it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) }.sortedBy { it[0] }
        if (refined.isEmpty()) return null
        val top = refined[0]
        val rival = refined.drop(1).firstOrNull { poseApart(it, top, 3.0, 1.0) >= 1.0 }
        val margin = if (rival == null) 1f else ((rival[0] - top[0]) / max(rival[0], 0.05)).toFloat()
        val rad = Math.toRadians(top[1]); val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
        var inl = 0; var sum = 0f
        for (i in 0 until m) { val d = field.at(fx[i] * c - fz[i] * s + top[2].toFloat(), fx[i] * s + fz[i] * c + top[3].toFloat()); sum += min(d, CAP); if (d <= 0.3f) inl++ }
        val mean = sum / m; val inlier = inl.toFloat() / m
        val al = Alignment(top[1].toFloat(), top[2].toFloat(), 0f, top[3].toFloat(), score = 1f - mean, precision = inlier, recall = inlier, rmsCm = mean * 100f, matched = inl, margin = margin, ambiguous = margin < 0.08f)
        return Registration(al, mean, inlier, inl, margin, margin < 0.08f)
    }

    /** The distance field [register] searches against; build it once to register many pieces to the same saved structure. */
    fun field(saved: Structure): DistanceField = DistanceField.of(saved, 0.25f, T)

    /** How well [fresh] sits on the saved structure under [a]: (mean capped distance in metres, share within 30 cm). */
    fun evaluate(field: DistanceField, fresh: Structure, a: Alignment): Pair<Float, Float> {
        if (fresh.size == 0) return CAP to 0f
        val rad = Math.toRadians(a.yawDeg.toDouble()); val c = cos(rad).toFloat(); val s = sin(rad).toFloat(); var sum = 0f; var inl = 0
        for (i in 0 until fresh.size) { val d = field.at(fresh.xs[i] * c - fresh.zs[i] * s + a.dx, fresh.xs[i] * s + fresh.zs[i] * c + a.dz); sum += min(d, CAP); if (d <= 0.3f) inl++ }
        return sum / fresh.size to inl.toFloat() / fresh.size
    }

    /** Hill-climb on the capped distance, shrinking the step: returns [score, yaw, tx, tz]. */
    private fun polish(f: DistanceField, xs: FloatArray, zs: FloatArray, yaw0: Float, tx0: Float, tz0: Float): DoubleArray {
        fun cost(yaw: Float, tx: Float, tz: Float): Double {
            val rad = Math.toRadians(yaw.toDouble()); val c = cos(rad).toFloat(); val s = sin(rad).toFloat(); var t = 0f
            for (i in xs.indices) t += min(f.at(xs[i] * c - zs[i] * s + tx, xs[i] * s + zs[i] * c + tz), CAP)
            return t.toDouble() / xs.size
        }
        var yaw = yaw0; var tx = tx0; var tz = tz0; var best = cost(yaw, tx, tz)
        var ys = 1.0f; var ts = 0.25f; var it = 0
        while (it++ < 140 && (ys > 0.05f || ts > 0.01f)) {
            var moved = false
            for (k in 0 until 6) {
                val ny = yaw + if (k == 0) ys else if (k == 1) -ys else 0f; val nx = tx + if (k == 2) ts else if (k == 3) -ts else 0f; val nz = tz + if (k == 4) ts else if (k == 5) -ts else 0f
                val c = cost(ny, nx, nz); if (c < best - 1e-6) { best = c; yaw = ny; tx = nx; tz = nz; moved = true }
            }
            if (!moved) { ys *= 0.5f; ts *= 0.5f }
        }
        return doubleArrayOf(best, yaw.toDouble(), tx.toDouble(), tz.toDouble())
    }

    /** Max of (yaw difference / [yawTol], shift / [shiftTol]): below 1 means "the same pose". */
    private fun poseApart(a: DoubleArray, b: DoubleArray, yawTol: Double, shiftTol: Double): Double {
        val dy = abs(((a[1] - b[1] + 540.0) % 360.0) - 180.0); val dt = hypot(a[2] - b[2], a[3] - b[3])
        return max(dy / yawTol, dt / shiftTol)
    }

    /**
     * How far the saved ground sits below or above the fresh walk's after the horizontal move: motion tracking starts each session at height
     * zero wherever the phone happens to be, so heights differ by the start height. Median over columns both walks saw the ground in; null when
     * too few columns are shared to say.
     */
    fun groundShift(fresh: RoomMap, saved: RoomMap, a: Alignment, cell: Float = 0.5f, minShared: Int = 25): Float? {
        fun lows(m: RoomMap, f: (Float, Float) -> Pair<Float, Float>): HashMap<Long, Float> {
            val out = HashMap<Long, Float>()
            m.forEachSolid { x, y, z -> val (ax, az) = f(x, z); val k = (floor(ax / cell).toLong() shl 32) xor (floor(az / cell).toLong() and 0xFFFFFFFFL); val v = out[k]; if (v == null || y < v) out[k] = y }
            return out
        }
        val rad = Math.toRadians(a.yawDeg.toDouble()); val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
        val fl = lows(fresh) { x, z -> (x * c - z * s + a.dx) to (x * s + z * c + a.dz) }; val sl = lows(saved) { x, z -> x to z }
        val diffs = ArrayList<Float>()
        for ((k, v) in fl) { val u = sl[k] ?: continue; diffs += u - v }
        if (diffs.size < minShared) return null
        diffs.sort(); return diffs[diffs.size / 2]
    }

}
