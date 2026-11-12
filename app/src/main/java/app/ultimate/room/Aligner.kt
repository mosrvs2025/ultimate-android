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

    /** The best way the new scan fits the saved one, or null (see [alignAll]). */
    fun align(fresh: List<P3>, saved: List<P3>, minPrecision: Float = 0.72f, minCells: Int = 80): Alignment? = alignAll(fresh, saved, minPrecision, minCells, 3).firstOrNull()

    /**
     * Up to [k] clearly different ways the new scan could sit on the saved one, best first, each polished. A bare or partial scan fits
     * more than one way (a rectangular room fits itself turned half round); the caller can then break the tie with something else the
     * two scans share, such as the things found in them ([resolve]). Each result's [Alignment.margin] is its fit minus the best of the others.
     */
    fun alignAll(fresh: List<P3>, saved: List<P3>, minPrecision: Float = 0.72f, minCells: Int = 80, k: Int = 3): List<Alignment> {
        val a = structure(fresh); val b = structure(saved)
        if (a.size < minCells || b.size < minCells) return emptyList()
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
        fun apart(y1: Float, y2: Float): Float { val d = abs(y1 - y2) % 360f; return min(d, 360f - d) }
        // Distinct candidates: different enough in turn or in place from the better ones already picked.
        val picked = ArrayList<Cand>()
        for (c in perYaw.sortedByDescending { it.precision }) {
            if (picked.size >= k) break
            if (c.precision < minPrecision * 0.85f || c.hits < minCells * 0.6f) break
            if (picked.none { apart(it.yaw, c.yaw) <= 20f || sqrt((it.tx - c.tx) * (it.tx - c.tx) + (it.tz - c.tz) * (it.tz - c.tz)) <= 0.6f && apart(it.yaw, c.yaw) <= 40f }) picked += c
        }
        if (picked.isEmpty()) return emptyList()
        val out = ArrayList<Alignment>()
        for ((n, top) in picked.withIndex()) {
            val rival = picked.filterIndexed { i, _ -> i != n }.maxOfOrNull { it.precision } ?: 0f
            polish(top.yaw, top.tx, top.tz, top.precision - rival, a, b, ax, az, bSet, fresh, saved, minPrecision, minCells)?.let { out += it }
        }
        return out.sortedByDescending { it.precision }
    }

    private fun polish(yaw0: Float, tx0: Float, tz0: Float, margin: Float, a: List<Pair<Int, Int>>, b: List<Pair<Int, Int>>, ax: FloatArray, az: FloatArray, bSet: HashSet<Long>, fresh: List<P3>, saved: List<P3>, minPrecision: Float, minCells: Int): Alignment? {

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
        var yaw = yaw0; var tx = tx0; var tz = tz0; var best = cost(yaw, tx, tz)
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
        val coarse = Alignment(((yaw % 360f) + 360f) % 360f, tx, dy, tz, score = precision, precision = precision, recall = covered.size.toFloat() / b.size, rmsCm = best.third * 100f, matched = best.second, margin = margin, ambiguous = margin < 0.08f)
        return refine(coarse, fresh, saved)
    }

    /**
     * Breaks a tie between ways of fitting by what the two scans have in common: carry each of the new scan's things (chair, bed, laptop,
     * ...) across with the candidate alignment and count how many land on a saved thing of the same kind. A fit that explains two or more
     * more things than every other is taken, and is no longer ambiguous. Otherwise the best fit is returned unchanged (still ambiguous).
     */
    fun resolve(cands: List<Alignment>, freshObjects: List<RoomObject>, savedObjects: List<RoomObject>): Alignment? {
        val best = cands.firstOrNull() ?: return null
        if (cands.size < 2 || !best.ambiguous && cands.drop(1).none { it.precision >= best.precision - 0.1f }) return best
        fun agree(al: Alignment): Int { val used = HashSet<Int>(); var n = 0
            for (o in freshObjects) { val p = al.apply(o.at); val j = savedObjects.indices.firstOrNull { it !in used && savedObjects[it].label == o.label && savedObjects[it].at.dist(p) <= 1.1f * RoomLabels.radius(o.label) + 0.3f }; if (j != null) { used += j; n++ } }
            return n }
        val scored = cands.map { it to agree(it) }.sortedByDescending { it.second }
        val top = scored.first(); val next = scored.getOrNull(1)?.second ?: 0
        return if (top.second >= 2 && top.second >= next + 2) top.first.copy(ambiguous = false, margin = maxOf(top.first.margin, 0.1f)) else best
    }

    /** Points that stand in tall columns (walls, wardrobes, door frames), the same idea as [structure] but keeping the points. */
    private fun structurePoints(pts: List<P3>, minBins: Int = 10): List<P3> {
        val cols = HashMap<Long, HashSet<Int>>()
        fun k(p: P3) = (floor(p.x / CELL).toLong() shl 32) or (floor(p.z / CELL).toLong() and 0xFFFFFFFFL)
        for (p in pts) cols.getOrPut(k(p)) { HashSet() }.add(floor(p.y / 0.05f).toInt())
        return pts.filter { (cols[k(it)]?.size ?: 0) >= minBins }
    }

    /**
     * Polishes an alignment against the actual wall points. The search above works on 10 cm cells, so it is good to a few centimetres;
     * a merge needs better, or each scan lays a slightly different copy of every wall on the last.
     *
     * The saved walls are turned into a smooth density picture (each wall point spreads a little, about 7 cm), and the turn and shift
     * are nudged, in steps shrinking from 3 cm to 3 mm, to put the new wall points where that picture is densest. A smooth picture
     * matters: matching each point to its nearest saved point instead pulls the two scans' 5 cm cube grids into step with each other
     * (rather than the walls into step), and was measured to be off by up to 17 cm even though it reported a 2 cm fit. [Alignment.rmsCm]
     * afterwards is the typical distance from each new wall point to the nearest saved one.
     */
    fun refine(a: Alignment, fresh: List<P3>, saved: List<P3>): Alignment {
        val sp = structurePoints(saved); val fp0 = structurePoints(fresh)
        if (sp.size < 300 || fp0.size < 300) return a
        val fp = if (fp0.size <= 4000) fp0 else (0 until 4000).map { fp0[(it.toLong() * fp0.size / 4000).toInt()] }
        val res = 0.05f; val x0 = sp.minOf { it.x } - 1f; val z0 = sp.minOf { it.z } - 1f
        val nx = ((sp.maxOf { it.x } + 1f - x0) / res).toInt() + 1; val nz = ((sp.maxOf { it.z } + 1f - z0) / res).toInt() + 1
        if (nx * nz > 1_500_000) return a
        val raw = FloatArray(nx * nz); for (p in sp) { val ix = ((p.x - x0) / res).toInt(); val iz = ((p.z - z0) / res).toInt(); raw[iz * nx + ix] += 1f }
        // Gaussian blur, radius 3 cells (sigma about 1.5 cells), in two passes.
        val kern = floatArrayOf(0.05f, 0.12f, 0.2f, 0.26f, 0.2f, 0.12f, 0.05f)
        val tmp = FloatArray(nx * nz); val den = FloatArray(nx * nz)
        for (z in 0 until nz) for (x in 0 until nx) { var s = 0f; for (k in -3..3) { val xx = x + k; if (xx in 0 until nx) s += raw[z * nx + xx] * kern[k + 3] }; tmp[z * nx + x] = s }
        for (z in 0 until nz) for (x in 0 until nx) { var s = 0f; for (k in -3..3) { val zz = z + k; if (zz in 0 until nz) s += tmp[zz * nx + x] * kern[k + 3] }; den[z * nx + x] = s }
        fun at(x: Float, z: Float): Float {
            val fx = (x - x0) / res - 0.5f; val fz = (z - z0) / res - 0.5f; val ix = floor(fx).toInt(); val iz = floor(fz).toInt()
            if (ix < 0 || iz < 0 || ix + 1 >= nx || iz + 1 >= nz) return 0f
            val u = fx - ix; val v = fz - iz
            return den[iz * nx + ix] * (1 - u) * (1 - v) + den[iz * nx + ix + 1] * u * (1 - v) + den[(iz + 1) * nx + ix] * (1 - u) * v + den[(iz + 1) * nx + ix + 1] * u * v
        }
        fun score(yaw: Float, tx: Float, tz: Float): Float {
            val r = Math.toRadians(yaw.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat(); var sum = 0f
            for (p in fp) sum += at(p.x * c - p.z * s + tx, p.x * s + p.z * c + tz)
            return sum
        }
        var yaw = a.yawDeg; var tx = a.dx; var tz = a.dz; var best = score(yaw, tx, tz); val start = best
        var dYaw = 1f; var dT = 0.03f
        while (dT > 0.0035f) {
            var improved = true; var guard = 0
            while (improved && guard++ < 20) {
                improved = false
                for (dy in -1..1) for (dx in -1..1) for (dz in -1..1) {
                    if (dy == 0 && dx == 0 && dz == 0) continue
                    val s = score(yaw + dy * dYaw, tx + dx * dT, tz + dz * dT)
                    if (s > best + 1e-3f) { best = s; yaw += dy * dYaw; tx += dx * dT; tz += dz * dT; improved = true }
                }
            }
            dYaw /= 2f; dT /= 2f
        }
        if (best <= start) return a
        val out = a.copy(yawDeg = ((yaw % 360f) + 360f) % 360f, dx = tx, dz = tz)
        // Fit indicator: distance from each new wall point to the nearest saved wall point (trimmed, so clutter doesn't dominate).
        val cs = 0.075f
        fun key(x: Int, y: Int, z: Int) = ((x.toLong() and 0x1FFFFF) shl 42) or ((y.toLong() and 0x1FFFFF) shl 21) or (z.toLong() and 0x1FFFFF)
        val grid = HashMap<Long, ArrayList<P3>>(sp.size); for (p in sp) grid.getOrPut(key(floor(p.x / cs).toInt(), floor(p.y / cs).toInt(), floor(p.z / cs).toInt())) { ArrayList(2) } += p
        val ds = ArrayList<Float>(fp.size)
        for (s in fp) { val p = out.apply(s); val ix = floor(p.x / cs).toInt(); val iy = floor(p.y / cs).toInt(); val iz = floor(p.z / cs).toInt(); var bd = 0.12f; var hit = false
            for (u in -1..1) for (v in -1..1) for (w in -1..1) grid[key(ix + u, iy + v, iz + w)]?.forEach { q -> val d = p.dist(q); if (d < bd) { bd = d; hit = true } }
            if (hit) ds += bd }
        if (ds.size < 100) return out
        ds.sort(); val k = ds.take((ds.size * 0.75f).toInt().coerceAtLeast(1))
        return out.copy(rmsCm = sqrt(k.sumOf { (it * it).toDouble() }.toFloat() / k.size) * 100f, matched = ds.size)
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
