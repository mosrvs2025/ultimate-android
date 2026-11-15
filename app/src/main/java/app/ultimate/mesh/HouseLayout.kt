package app.ultimate.mesh

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where your devices are, from nothing but the distances between them.
 *
 * Every pair of devices that has measured its distance (by sound, see [PeerRange]) gives one number. Three or more devices
 * that have measured each other pin down a shape, and that shape is the map: the same idea as GPS finding a position from
 * distances to satellites, run the other way round. This file is that arithmetic and nothing else, so it is tested on
 * made-up rooms with known answers.
 *
 * What distances alone can never say, and the map says so instead of guessing: which way is north (the map can be turned
 * any way), and left from right (a map and its mirror image have exactly the same distances). A room scan or the phones'
 * compasses can settle both later. The map is flat (2-D): devices at different heights show at their floor positions,
 * with a little error.
 */
object HouseLayout {
    data class Measure(val a: String, val b: String, val metres: Double, val weight: Double = 1.0)
    data class Point(val x: Double, val y: Double) { fun to(o: Point) = hypot(x - o.x, y - o.y) }

    sealed interface Result {
        /**
         * [error] is each device's typical disagreement between the map and what was measured, in metres; [doubtful] are
         * measurements that disagree with all the others (usually an echo was heard instead of the direct sound).
         */
        data class Map(val at: kotlin.collections.Map<String, Point>, val error: kotlin.collections.Map<String, Double>, val rms: Double, val doubtful: List<Measure>) : Result
        data class NotEnough(val why: String, val missing: List<String>) : Result
    }

    fun solve(devices: List<String>, measures: List<Measure>, sweeps: Int = 400): Result {
        val ids = devices.distinct()
        val n = ids.size
        if (n < 2) return Result.NotEnough("A map needs at least two devices.", ids)
        val index = ids.withIndex().associate { it.value to it.index }
        // Repeated measurements of one pair are averaged, weighted by how sure each was.
        val sum = Array(n) { DoubleArray(n) }; val wsum = Array(n) { DoubleArray(n) }
        for (m in measures) {
            val i = index[m.a] ?: continue; val j = index[m.b] ?: continue
            if (i == j || !m.metres.isFinite() || m.metres < 0 || m.weight <= 0) continue
            sum[i][j] += m.metres * m.weight; sum[j][i] += m.metres * m.weight
            wsum[i][j] += m.weight; wsum[j][i] += m.weight
        }
        val d = Array(n) { i -> DoubleArray(n) { j -> if (wsum[i][j] > 0) sum[i][j] / wsum[i][j] else Double.NaN } }
        val w = Array(n) { i -> DoubleArray(n) { j -> wsum[i][j] } }

        // Enough to pin the shape down? Everyone must be linked to everyone through measurements, and in a flat map each
        // device needs at least two measured neighbours (with only one, it could swing round in a circle).
        if (!connected(n, w)) {
            val reach = reachable(n, w, 0)
            return Result.NotEnough("Some devices have not measured their distance to any of the others' group yet.", ids.filterIndexed { i, _ -> i !in reach })
        }
        if (n >= 3) {
            val loose = ids.filterIndexed { i, _ -> (0 until n).count { j -> w[i][j] > 0 } < 2 }
            if (loose.isNotEmpty()) return Result.NotEnough("Each device needs its distance measured to at least two others; these have only one.", loose)
        }

        // A measurement that disagrees with all the others (an echo heard instead of the direct sound) would bend the
        // whole map if it were kept. Each measurement in turn is left out and predicted from the rest; the one that the
        // rest disagree with most, by far more than the map's usual error, is set aside and the map solved again.
        val doubtful = ArrayList<Measure>()
        var p = layout(n, d, w, sweeps)
        while (true) {
            val edges = (0 until n).flatMap { i -> (i + 1 until n).filter { j -> w[i][j] > 0 }.map { j -> i to j } }
            if (edges.size <= 2 * n - 3 || doubtful.size >= edges.size / 3) break
            var worst = -1 to -1; var worstGap = 0.0; var worstRms = 0.0
            for ((i, j) in edges) {
                val wi = Array(n) { w[it].copyOf() }; wi[i][j] = 0.0; wi[j][i] = 0.0
                if (!pinned(n, wi)) continue
                val q = layout(n, d, wi, sweeps / 2, start = p)
                val gap = abs(hypot(q[i].first - q[j].first, q[i].second - q[j].second) - d[i][j])
                if (gap > worstGap) { worstGap = gap; worst = i to j; worstRms = fit(n, d, wi, q) }
            }
            if (worst.first < 0 || worstGap < max(0.3, 5 * worstRms)) break
            // And it must also misfit the map as it stands, clearly more than the others do: a pair that the rest of the
            // map only loosely pins down can be "predicted" badly without being wrong, and is kept.
            val (i, j) = worst
            val own = abs(hypot(p[i].first - p[j].first, p[i].second - p[j].second) - d[i][j])
            val others = fit(n, d, Array(n) { a -> DoubleArray(n) { b -> if ((a == i && b == j) || (a == j && b == i)) 0.0 else w[a][b] } }, p)
            // (An echo bends the whole map, so its own misfit can hide; then the giveaway is that everything else fits
            // markedly better once it is left out.)
            if (own < max(0.15, 3 * others) && others - worstRms < 0.06) break
            doubtful += Measure(ids[i], ids[j], d[i][j])
            w[i][j] = 0.0; w[j][i] = 0.0
            p = layout(n, d, w, sweeps, start = p)
        }

        // A tidy frame: the first device at the centre, the second straight to its right, the third above the line.
        val o = p[0]
        var pts = p.map { (it.first - o.first) to (it.second - o.second) }
        val ref = pts.drop(1).firstOrNull { hypot(it.first, it.second) > 1e-6 }
        if (ref != null) {
            val a = kotlin.math.atan2(ref.second, ref.first); val c = kotlin.math.cos(-a); val s = kotlin.math.sin(-a)
            pts = pts.map { (x, y) -> (x * c - y * s) to (x * s + y * c) }
            val third = pts.drop(2).firstOrNull { abs(it.second) > 1e-6 }
            if (third != null && third.second < 0) pts = pts.map { (x, y) -> x to -y }
        }
        val at = ids.indices.associate { ids[it] to Point(pts[it].first, pts[it].second) }

        // How well it fits.
        val res = ArrayList<Triple<Int, Int, Double>>()
        for (i in 0 until n) for (j in i + 1 until n) if (w[i][j] > 0) res += Triple(i, j, at.getValue(ids[i]).to(at.getValue(ids[j])) - d[i][j])
        val rms = if (res.isEmpty()) 0.0 else sqrt(res.sumOf { it.third * it.third } / res.size)
        val error = ids.indices.associate { i ->
            val mine = res.filter { it.first == i || it.second == i }
            ids[i] to if (mine.isEmpty()) 0.0 else sqrt(mine.sumOf { it.third * it.third } / mine.size)
        }
        return Result.Map(at, error, rms, doubtful)
    }

    /**
     * Where something is from its distances to devices already on the map (at least three, not all in a line), by least
     * squares; null if it cannot be placed. Used for "where am I" from the watch's distance to each phone.
     */
    fun locate(map: kotlin.collections.Map<String, Point>, distances: kotlin.collections.Map<String, Double>): Point? {
        val known = distances.filter { (k, v) -> k in map && v.isFinite() && v >= 0 }
        if (known.size < 3) return null
        val pts = known.keys.map { map.getValue(it) }
        var x = pts.map { it.x }.average(); var y = pts.map { it.y }.average()
        repeat(200) {
            var gx = 0.0; var gy = 0.0; var h = 0.0
            for ((k, r) in known) {
                val p = map.getValue(k); val dx = x - p.x; val dy = y - p.y; val len = max(1e-9, hypot(dx, dy))
                val e = len - r
                gx += e * dx / len; gy += e * dy / len; h += 1.0
            }
            x -= gx / h; y -= gy / h
        }
        // In a line, a point and its mirror across the line fit equally: refuse rather than pick one at random.
        val spread = spread(pts)
        return if (spread < 0.05) null else Point(x, y)
    }

    private fun spread(pts: List<Point>): Double {
        // Smallest width of the points (the minor axis), in metres.
        val mx = pts.map { it.x }.average(); val my = pts.map { it.y }.average()
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (p in pts) { sxx += (p.x - mx) * (p.x - mx); syy += (p.y - my) * (p.y - my); sxy += (p.x - mx) * (p.y - my) }
        val tr = (sxx + syy) / pts.size; val det = (sxx * syy - sxy * sxy) / (pts.size.toDouble() * pts.size)
        val minor = tr / 2 - sqrt(max(0.0, tr * tr / 4 - det))
        return sqrt(max(0.0, minor))
    }

    /** Classical scaling over shortest paths as a start, then each device moved in turn to where its neighbours put it. */
    private fun layout(n: Int, d: Array<DoubleArray>, w: Array<DoubleArray>, sweeps: Int, start: Array<Pair<Double, Double>>? = null): Array<Pair<Double, Double>> {
        val p = start?.copyOf() ?: run {
            val dm = Array(n) { i -> DoubleArray(n) { j -> if (w[i][j] > 0) d[i][j] else Double.NaN } }
            classical(shortestPaths(n, dm))
        }
        repeat(sweeps) {
            for (i in 0 until n) {
                var sx = 0.0; var sy = 0.0; var sw = 0.0
                for (j in 0 until n) {
                    if (w[i][j] <= 0) continue
                    val dx = p[i].first - p[j].first; val dy = p[i].second - p[j].second
                    val len = hypot(dx, dy)
                    val ux = if (len < 1e-9) 1.0 else dx / len; val uy = if (len < 1e-9) 0.0 else dy / len
                    sx += w[i][j] * (p[j].first + d[i][j] * ux); sy += w[i][j] * (p[j].second + d[i][j] * uy); sw += w[i][j]
                }
                if (sw > 0) p[i] = sx / sw to sy / sw
            }
        }
        return p
    }

    /** Root-mean-square disagreement between a layout and the measurements it was given. */
    private fun fit(n: Int, d: Array<DoubleArray>, w: Array<DoubleArray>, p: Array<Pair<Double, Double>>): Double {
        var s = 0.0; var c = 0
        for (i in 0 until n) for (j in i + 1 until n) if (w[i][j] > 0) { val e = hypot(p[i].first - p[j].first, p[i].second - p[j].second) - d[i][j]; s += e * e; c++ }
        return if (c == 0) 0.0 else sqrt(s / c)
    }

    /** Linked up, and in a flat map every device (when three or more) has at least two measured neighbours. */
    private fun pinned(n: Int, w: Array<DoubleArray>) = connected(n, w) && (n < 3 || (0 until n).all { i -> (0 until n).count { j -> w[i][j] > 0 } >= 2 })

    private fun reachable(n: Int, w: Array<DoubleArray>, from: Int): Set<Int> {
        val seen = hashSetOf(from); val todo = ArrayDeque(listOf(from))
        while (todo.isNotEmpty()) { val i = todo.removeFirst(); for (j in 0 until n) if (w[i][j] > 0 && seen.add(j)) todo += j }
        return seen
    }

    private fun connected(n: Int, w: Array<DoubleArray>) = reachable(n, w, 0).size == n

    private fun shortestPaths(n: Int, d: Array<DoubleArray>): Array<DoubleArray> {
        val s = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else if (d[i][j].isNaN()) Double.POSITIVE_INFINITY else d[i][j] } }
        for (k in 0 until n) for (i in 0 until n) for (j in 0 until n) if (s[i][k] + s[k][j] < s[i][j]) s[i][j] = s[i][k] + s[k][j]
        return s
    }

    /** Classical multidimensional scaling to 2-D: the best flat picture of a full distance table. */
    private fun classical(dist: Array<DoubleArray>): Array<Pair<Double, Double>> {
        val n = dist.size
        val sq = Array(n) { i -> DoubleArray(n) { j -> dist[i][j] * dist[i][j] } }
        val row = DoubleArray(n) { i -> sq[i].average() }; val all = row.average()
        val b = Array(n) { i -> DoubleArray(n) { j -> -0.5 * (sq[i][j] - row[i] - row[j] + all) } }
        val (l1, v1) = top(b, null); val (l2, v2) = top(b, v1 to l1)
        val s1 = sqrt(max(0.0, l1)); val s2 = sqrt(max(0.0, l2))
        // A tiny nudge off the line, so devices that start exactly in a row can still move apart.
        return Array(n) { i -> v1[i] * s1 to v2[i] * s2 + 1e-3 * ((i * 7919) % 13 - 6) }
    }

    /** The largest eigenvalue and its vector by power iteration, with an earlier one removed. */
    private fun top(b: Array<DoubleArray>, without: Pair<DoubleArray, Double>?): Pair<Double, DoubleArray> {
        val n = b.size
        var v = DoubleArray(n) { 1.0 + (0.37 * it) % 1.3 }
        var lambda = 0.0
        repeat(300) {
            val u = DoubleArray(n) { i -> (0 until n).sumOf { j -> b[i][j] * v[j] } }
            // Deflation: B v minus the part along the first eigenvector, l1 (e . v) e.
            if (without != null) { val (e, l) = without; val k = l * (0 until n).sumOf { v[it] * e[it] }; for (i in 0 until n) u[i] -= k * e[i] }
            val norm = sqrt(u.sumOf { it * it })
            if (norm < 1e-12) return 0.0 to DoubleArray(n)
            lambda = (0 until n).sumOf { u[it] * v[it] } / max(1e-12, v.sumOf { it * it })
            v = DoubleArray(n) { u[it] / norm }
        }
        return lambda to v
    }
}

/** The measured distances and device names, as text for keeping between runs. One line per entry, tab-separated. */
object HouseStore {
    fun encode(names: Map<String, String>, measured: List<HouseLayout.Measure>): String = buildString {
        names.forEach { (id, name) -> append("n\t").append(clean(id)).append('\t').append(clean(name)).append('\n') }
        measured.forEach { m -> append("m\t").append(clean(m.a)).append('\t').append(clean(m.b)).append('\t').append(m.metres).append('\t').append(m.weight).append('\n') }
    }

    fun decode(text: String): Pair<Map<String, String>, List<HouseLayout.Measure>>? {
        if (text.isBlank()) return null
        val names = LinkedHashMap<String, String>(); val measured = ArrayList<HouseLayout.Measure>()
        for (line in text.lines()) {
            val p = line.split('\t')
            when (p.firstOrNull()) {
                "n" -> if (p.size >= 3) names[p[1]] = p[2]
                "m" -> if (p.size >= 5) { val m = p[3].toDoubleOrNull(); val w = p[4].toDoubleOrNull(); if (m != null && w != null) measured += HouseLayout.Measure(p[1], p[2], m, w) }
            }
        }
        return names to measured.filter { it.a in names && it.b in names }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
}
