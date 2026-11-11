package app.ultimate.room

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finding how a new scan sits on a saved one, from shape alone, on the phone. Gravity fixes which way is up, so the unknowns are a turn
 * about the vertical and a shift.
 *
 * What it matches is *structure*: places where the scan is solid over at least half a metre of height (walls, wardrobes, door frames),
 * taken as a top-down plan. That does not depend on knowing where the floor is (the first version sliced "0.3 to 2.2 m above the
 * floor", so one wrong floor estimate made every match fail), and it ignores floor clutter that moves between scans.
 *
 * Search: every turn in 2° steps; for each, every pairing of a new cell with a saved cell votes for the shift that would put one on the
 * other (a generalised Hough transform), and the best few shifts are checked against all cells. The winner is then refined to a fraction
 * of a cell by minimising the distance from each new cell to the nearest saved cell. The height shift comes from lining up the two
 * scans' layers (floors and ceilings are the big ones).
 *
 * It answers null rather than guess: too little structure, too few cells agreeing, or the fit too loose. When a second, very different
 * turn fits nearly as well (a bare rectangular room looks the same turned half round), the result is marked [Alignment.ambiguous] and
 * must not be merged without more scanning. Pure, so it is tested.
 */
object Aligner {
    const val CELL = 0.1f
    private const val N = 512; private const val HALF = N / 2

    /** Top-down cells that are solid over at least [minBins] different 5 cm layers. */
    fun structure(pts: List<P3>, minBins: Int = 10): List<Pair<Int, Int>> {
        val cols = HashMap<Long, HashSet<Int>>()
        for (p in pts) cols.getOrPut((floor(p.x / CELL).toLong() shl 32) or (floor(p.z / CELL).toLong() and 0xFFFFFFFFL)) { HashSet() }.add(floor(p.y / 0.05f).toInt())
        return cols.filter { it.value.size >= minBins }.keys.map { (it shr 32).toInt() to it.toInt() }
    }

    fun align(fresh: List<P3>, saved: List<P3>, minPrecision: Float = 0.72f, minCells: Int = 80): Alignment? {
        val a = structure(fresh); val b = structure(saved)
        if (a.size < minCells || b.size < minCells) return null
        val bSet = HashSet<Long>(b.size * 2); for ((x, z) in b) bSet += key(x, z)
        fun near(x: Int, z: Int) = key(x, z) in bSet || key(x + 1, z) in bSet || key(x - 1, z) in bSet || key(x, z + 1) in bSet || key(x, z - 1) in bSet
        val ax = FloatArray(a.size) { (a[it].first + 0.5f) * CELL }; val az = FloatArray(a.size) { (a[it].second + 0.5f) * CELL }
        fun sample(n: Int, want: Int) = if (n <= want) (0 until n).toList() else (0 until want).map { (it.toLong() * n / want).toInt() }
        val sa = sample(a.size, 300); val sb = sample(b.size, 600)
        val votes = IntArray(N * N)
        class Cand(val yaw: Float, val tx: Float, val tz: Float, val precision: Float, val hits: Int)
        val perYaw = ArrayList<Cand>()
        for (deg in 0 until 360 step 2) {
            val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
            java.util.Arrays.fill(votes, 0)
            for (i in sa) {
                val rx = floor((ax[i] * c - az[i] * s) / CELL).toInt(); val rz = floor((ax[i] * s + az[i] * c) / CELL).toInt()
                for (j in sb) { val tx = b[j].first - rx + HALF; val tz = b[j].second - rz + HALF; if (tx in 0 until N && tz in 0 until N) votes[tz * N + tx]++ }
            }
            // The three most-voted shifts, each checked against every cell.
            var best: Cand? = null
            repeat(3) {
                var bi = 0; for (k in votes.indices) if (votes[k] > votes[bi]) bi = k
                if (votes[bi] == 0) return@repeat
                val tx = bi % N - HALF; val tz = bi / N - HALF
                for (dz in -1..1) for (dx in -1..1) { val k = (bi / N + dz) * N + (bi % N + dx); if (k in votes.indices) votes[k] = 0 }
                var hit = 0
                for (i in a.indices) { val x = floor((ax[i] * c - az[i] * s) / CELL).toInt() + tx; val z = floor((ax[i] * s + az[i] * c) / CELL).toInt() + tz; if (near(x, z)) hit++ }
                val p = hit.toFloat() / a.size
                if (best == null || p > best!!.precision) best = Cand(deg.toFloat(), tx * CELL, tz * CELL, p, hit)
            }
            best?.let { perYaw += it }
        }
        val top = perYaw.maxByOrNull { it.precision } ?: return null
        if (top.precision < minPrecision * 0.85f || top.hits < minCells * 0.6f) return null
        fun apart(y1: Float, y2: Float): Float { val d = abs(y1 - y2) % 360f; return min(d, 360f - d) }
        val rival = perYaw.filter { apart(it.yaw, top.yaw) > 20f }.maxOfOrNull { it.precision } ?: 0f

        // Refine: slide and turn a little to minimise the distance from each new cell to the nearest saved cell.
        val bx = HashMap<Long, FloatArray>(); for ((x, z) in b) bx[key(x, z)] = floatArrayOf((x + 0.5f) * CELL, (z + 0.5f) * CELL)
        fun cost(yaw: Float, tx: Float, tz: Float): Triple<Float, Int, Float> {
            val r = Math.toRadians(yaw.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
            var sum = 0f; var hit = 0; var sq = 0f
            for (i in a.indices) {
                val x = ax[i] * c - az[i] * s + tx; val z = ax[i] * s + az[i] * c + tz
                val ix = floor(x / CELL).toInt(); val iz = floor(z / CELL).toInt(); var d2 = 0.0225f
                for (u in -1..1) for (v in -1..1) bx[key(ix + u, iz + v)]?.let { val ddx = it[0] - x; val ddz = it[1] - z; d2 = min(d2, ddx * ddx + ddz * ddz) }
                if (d2 < 0.0225f) { hit++; sq += d2 }
                sum += sqrt(d2)
            }
            return Triple(sum / a.size, hit, if (hit > 0) sqrt(sq / hit) else 0.15f)
        }
        var yaw = top.yaw; var tx = top.tx; var tz = top.tz; var best = cost(yaw, tx, tz)
        for (step in listOf(Triple(1f, 0.05f, 2), Triple(0.5f, 0.025f, 2), Triple(0.25f, 0.0125f, 2))) {
            var improved = true; var guard = 0
            while (improved && guard++ < 12) {
                improved = false
                for (dy in -1..1) for (dx in -1..1) for (dz in -1..1) {
                    if (dy == 0 && dx == 0 && dz == 0) continue
                    val c = cost(yaw + dy * step.first, tx + dx * step.second, tz + dz * step.second)
                    if (c.first < best.first - 1e-5f) { best = c; yaw += dy * step.first; tx += dx * step.second; tz += dz * step.second; improved = true }
                }
            }
        }
        val precision = best.second.toFloat() / a.size
        if (precision < minPrecision || best.second < minCells * 0.6f) return null
        // How much of the saved structure the new scan covers (low for a partial scan; that is fine).
        val r = Math.toRadians(yaw.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val covered = HashSet<Long>(); for (i in a.indices) { val ix = floor((ax[i] * c - az[i] * s + tx) / CELL).toInt(); val iz = floor((ax[i] * s + az[i] * c + tz) / CELL).toInt(); for (u in -1..1) for (v in -1..1) if (key(ix + u, iz + v) in bSet) covered += key(ix + u, iz + v) }
        val dy = heightShift(fresh, saved)
        val margin = top.precision - rival
        return Alignment(((yaw % 360f) + 360f) % 360f, tx, dy, tz, score = precision, precision = precision, recall = covered.size.toFloat() / b.size, rmsCm = best.third * 100f, matched = best.second, margin = margin, ambiguous = margin < 0.08f)
    }

    /** The shift in height that best lines up the two scans' layers: add it to a new point's height to get the saved room's. */
    fun heightShift(fresh: List<P3>, saved: List<P3>): Float {
        fun hist(p: List<P3>): Pair<Int, FloatArray> { val lo = floor(p.minOf { it.y } / 0.05f).toInt(); val hi = floor(p.maxOf { it.y } / 0.05f).toInt(); val h = FloatArray(hi - lo + 1); for (q in p) h[floor(q.y / 0.05f).toInt() - lo]++; val m = h.max(); for (i in h.indices) h[i] = sqrt(h[i] / m); return lo to h }
        val (la, ha) = hist(fresh); val (lb, hb) = hist(saved)
        var bestS = 0; var bestV = -1f
        for (sft in -60..60) { var v = 0f; for (i in ha.indices) { val j = i + la + sft - lb; if (j in hb.indices) v += ha[i] * hb[j] }; if (v > bestV) { bestV = v; bestS = sft } }
        // Sub-bin: centre of mass of the correlation around the best shift.
        fun corr(sft: Int): Float { var v = 0f; for (i in ha.indices) { val j = i + la + sft - lb; if (j in hb.indices) v += ha[i] * hb[j] }; return v }
        val l = corr(bestS - 1); val m = bestV; val rr = corr(bestS + 1); val den = l - 2 * m + rr
        val frac = if (abs(den) > 1e-6f) (0.5f * (l - rr) / den).coerceIn(-0.5f, 0.5f) else 0f
        return (bestS + frac) * 0.05f
    }

    private fun key(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
}
