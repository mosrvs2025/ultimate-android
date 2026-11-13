package app.ultimate.fusion

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Two sensors disagreeing about the same stretch of the recording, and what the pattern says. */
data class Contradiction(val a: String, val b: String, val distanceA: Double, val distanceB: Double, val z: Double, val outlier: String?, val text: String)

/** How one sensor's distance compares with the fused answer. */
data class SourceSummary(val name: String, val total: Quantity, val coverage: Double, val vsFused: Double?, val trusted: Boolean, val note: String)

data class EpochFusion(val span: Span, val metres: Double?, val perSource: Map<String, Double>)

class FusionResult(
    val distance: Quantity?, val unobservedS: Double, val epochs: List<EpochFusion>,
    val contradictions: List<Contradiction>, val excluded: Set<String>, val sources: List<SourceSummary>,
)

/**
 * Combines several witnesses to the same walk into one distance, and says when they cannot all be right.
 *
 * Method: slice the recording into 10 s epochs. Each sensor reports the distance it saw in each epoch it saw completely (never a guess for
 * the rest). Sensors are compared like for like, over the epochs both saw: two honest sensors differ by no more than their combined
 * uncertainty (a z-score under 3). A pair that differs more is a contradiction. With three or more sensors, if removing one makes
 * the rest agree, that one is the outlier and is left out of the fused distance; with only two, nothing can say which is wrong, so both
 * are kept and the uncertainty is widened to cover the gap. The fused distance counts only epochs at least one trusted sensor saw: the rest
 * is reported as unobserved, not filled in.
 */
object Fusion {
    const val EPOCH_S = 10.0
    private const val Z_LIMIT = 3.0
    private const val MIN_COMMON_S = 30.0

    fun run(sources: List<DistanceSource>, durationS: Double): FusionResult {
        val live = sources.filter { it.usable }
        val epochs = ArrayList<Span>()
        var t = 0.0
        while (t + EPOCH_S <= durationS + 1e-9) { epochs += Span(t, t + EPOCH_S); t += EPOCH_S }
        val per = live.map { s -> epochs.map { s.length(it) } }
        fun sigmaOf(s: DistanceSource, d: Double) = sqrt((s.relSigma * d) * (s.relSigma * d) + s.absSigma * s.absSigma)

        // Pairwise comparison over the epochs both sensors saw.
        class Pair2(val i: Int, val j: Int, val di: Double, val dj: Double, val z: Double, val common: Double)
        val pairs = ArrayList<Pair2>()
        for (i in live.indices) for (j in i + 1 until live.size) {
            var di = 0.0; var dj = 0.0; var common = 0.0
            for (k in epochs.indices) { val a = per[i][k]; val b = per[j][k]; if (a != null && b != null) { di += a; dj += b; common += EPOCH_S } }
            if (common < MIN_COMMON_S) continue
            val sa = sigmaOf(live[i], di); val sb = sigmaOf(live[j], dj)
            pairs += Pair2(i, j, di, dj, abs(di - dj) / sqrt(sa * sa + sb * sb), common)
        }
        val bad = pairs.filter { it.z > Z_LIMIT }

        // Who is the outlier? One sensor that is in every bad pair while the pairs it is not in agree.
        val excluded = HashSet<String>()
        val contradictions = ArrayList<Contradiction>()
        for (p in bad) {
            val a = live[p.i]; val b = live[p.j]
            val others = live.indices.filter { it != p.i && it != p.j }
            var outlier: String? = null
            if (others.isNotEmpty()) {
                // The third sensor sides with one of the two.
                val sidesWithI = others.count { o -> pairs.any { (it.i == min(o, p.i) && it.j == max(o, p.i)) && it.z <= Z_LIMIT } }
                val sidesWithJ = others.count { o -> pairs.any { (it.i == min(o, p.j) && it.j == max(o, p.j)) && it.z <= Z_LIMIT } }
                if (sidesWithI > 0 && sidesWithJ == 0) outlier = b.name else if (sidesWithJ > 0 && sidesWithI == 0) outlier = a.name
            }
            if (outlier != null) excluded += outlier
            val ratio = if (p.dj > 0) p.di / p.dj else Double.NaN
            val inflated = if (ratio >= 1.6) a.name else if (ratio > 0 && ratio <= 0.625) b.name else null
            val hint = if (inflated == "inertial steps") " A bounce counted twice per step (an arm swing, a shaking phone) would inflate step distance like this." else ""
            contradictions += Contradiction(a.name, b.name, p.di, p.dj, p.z, outlier,
                "Over the %.0f s that both could see, ${a.name} measured %.0f m and ${b.name} %.0f m (%.1f combined uncertainties apart).".format(p.common, p.di, p.dj, p.z) +
                    (if (outlier != null) " The other sensors side with the rest, so $outlier is the odd one out and is left out of the total." else " With nothing else to side with, there is no way to say which is wrong, so both are kept and the uncertainty is widened.") + hint)
        }

        // Per-epoch fusion over trusted sensors; unobserved epochs stay unobserved.
        val trusted = live.filter { it.name !in excluded }
        val out = ArrayList<EpochFusion>()
        var total = 0.0; var unobserved = 0.0
        for ((k, ep) in epochs.withIndex()) {
            val vals = HashMap<String, Double>()
            for ((i, s) in live.withIndex()) per[i][k]?.let { vals[s.name] = it }
            var num = 0.0; var den = 0.0
            for ((i, s) in live.withIndex()) {
                if (s.name in excluded) continue
                val d = per[i][k] ?: continue
                val sg = sqrt((s.relSigma * d) * (s.relSigma * d) + (s.absSigma * 0.3) * (s.absSigma * 0.3) + 1e-4)
                num += d / (sg * sg); den += 1 / (sg * sg)
            }
            if (den == 0.0) { unobserved += EPOCH_S; out += EpochFusion(ep, null, vals) } else { val m = num / den; total += m; out += EpochFusion(ep, m, vals) }
        }
        // The partial last epoch is not counted anywhere (it is shorter than a window).
        val tail = max(0.0, durationS - epochs.size * EPOCH_S)

        // Total uncertainty: independent sensors combine; a contradiction we could not resolve widens it.
        val fusedSigma: Double? = run {
            if (trusted.isEmpty()) return@run null
            var inv = 0.0
            for (s in trusted) { val d = sumOver(s, epochs, live.indexOf(s), per); val sg = sigmaOf(s, d).coerceAtLeast(0.3); inv += 1 / (sg * sg) }
            var sg = sqrt(1 / inv)
            val unresolved = bad.filter { live[it.i].name !in excluded && live[it.j].name !in excluded }
            if (unresolved.isNotEmpty()) sg = sqrt(sg * sg + unresolved.maxOf { (abs(it.di - it.dj) / 2).let { x -> x * x } })
            sg
        }
        val distance = if (trusted.isEmpty() || out.all { it.metres == null }) null else Quantity(total, fusedSigma ?: 0.0, "m", Basis.Derived, "fusion of " + trusted.joinToString(", ") { it.name },
            (if (unobserved > 0) "%.0f s not seen by any trusted sensor are not counted. ".format(unobserved + tail) else "") + if (excluded.isNotEmpty()) "left out: ${excluded.joinToString()}" else "")

        val summaries = live.mapIndexed { i, s ->
            val seen = per[i].count { it != null }
            val d = sumOver(s, epochs, i, per)
            val q = Quantity(d, sigmaOf(s, d), "m", Basis.Derived, s.name, (s as? PolylineSource)?.let { if (it.jumps > 0) "${it.jumps} jump(s) ignored" else "" } ?: "")
            SourceSummary(s.name, q, if (epochs.isEmpty()) 0.0 else seen.toDouble() / epochs.size,
                distance?.let { f -> if (f.value > 0) d / (epochs.indices.sumOf { k -> if (per[i][k] != null) (out[k].metres ?: 0.0) else 0.0 }.takeIf { it > 0 } ?: f.value) else null },
                s.name !in excluded, if (s.name in excluded) "disagrees with the others" else "")
        }
        return FusionResult(distance, unobserved + tail, out, contradictions, excluded, summaries)
    }

    private fun sumOver(s: DistanceSource, epochs: List<Span>, i: Int, per: List<List<Double?>>): Double {
        var d = 0.0
        for (k in epochs.indices) per.getOrNull(i)?.getOrNull(k)?.let { d += it }
        return d
    }
}

/** A rigid fit (turn and shift) of one track onto another, found from the times both existed. */
class Alignment2D(val theta: Double, val tx: Double, val ty: Double, val rms: Double, val n: Int) {
    fun apply(x: Double, y: Double) = doubleArrayOf(cos(theta) * x - sin(theta) * y + tx, sin(theta) * x + cos(theta) * y + ty)
    /** Rotates a displacement (no shift). */
    fun turn(dx: Double, dy: Double) = doubleArrayOf(cos(theta) * dx - sin(theta) * dy, sin(theta) * dx + cos(theta) * dy)

    companion object {
        val IDENTITY = Alignment2D(0.0, 0.0, 0.0, 0.0, 0)

        /** Least-squares turn + shift taking [from] onto [to] (Kabsch in 2-D). Needs 5+ pairs spread over some distance, else null. */
        fun fit(from: List<DoubleArray>, to: List<DoubleArray>): Alignment2D? {
            val n = min(from.size, to.size)
            if (n < 5) return null
            val fx = from.take(n).map { it[0] }.average(); val fy = from.take(n).map { it[1] }.average()
            val tx = to.take(n).map { it[0] }.average(); val ty = to.take(n).map { it[1] }.average()
            var sxx = 0.0; var sxy = 0.0; var syx = 0.0; var syy = 0.0
            for (i in 0 until n) {
                val ax = from[i][0] - fx; val ay = from[i][1] - fy; val bx = to[i][0] - tx; val by = to[i][1] - ty
                sxx += ax * bx; sxy += ax * by; syx += ay * bx; syy += ay * by
            }
            if (abs(sxx) + abs(sxy) + abs(syx) + abs(syy) < 1e-6) return null
            val theta = atan2(sxy - syx, sxx + syy)
            val c = cos(theta); val s = sin(theta)
            val ox = tx - (c * fx - s * fy); val oy = ty - (s * fx + c * fy)
            var sq = 0.0
            for (i in 0 until n) { val p = doubleArrayOf(c * from[i][0] - s * from[i][1] + ox, s * from[i][0] + c * from[i][1] + oy); sq += (p[0] - to[i][0]) * (p[0] - to[i][0]) + (p[1] - to[i][1]) * (p[1] - to[i][1]) }
            return Alignment2D(theta, ox, oy, sqrt(sq / n), n)
        }
    }
}

/** Where the person went, second by second, on one map frame, with which sensor drew each stretch. */
class FusedPath(val t: DoubleArray, val e: DoubleArray, val n: DoubleArray, val from: Array<String>, val frame: String, val registrationRms: Double?) {
    val size get() = t.size
    fun lengthBetween(a: Int, b: Int): Double { var d = 0.0; for (i in a + 1..b) d += hypot(e[i] - e[i - 1], n[i] - n[i - 1]); return d }
    val length: Double get() = if (size < 2) 0.0 else lengthBetween(0, size - 1)

    companion object {
        /**
         * Builds the path: the frame is GPS north-up if GPS exists, else the camera's, else the steps'. Each second takes the movement of the
         * best sensor that saw it (camera tracking, then steps, then GPS) after fitting that sensor onto the frame, and adds it to where the
         * path already was, so switching sensor never makes the path jump.
         */
        fun build(sources: List<DistanceSource>, durationS: Double): FusedPath? {
            val live = sources.filter { it.usable }
            if (live.isEmpty() || durationS < 3) return null
            val gps = live.firstOrNull { it.name == "GPS" }
            val frameSrc = gps ?: live.first()
            // Fit every other source onto the frame source over the times both existed.
            val fits = HashMap<String, Alignment2D>(); var frameRms: Double? = null
            for (s in live) {
                if (s === frameSrc) { fits[s.name] = Alignment2D.IDENTITY; continue }
                val a = ArrayList<DoubleArray>(); val b = ArrayList<DoubleArray>()
                var t = 0.0
                while (t <= durationS) { val p = s.position(t); val q = frameSrc.position(t); if (p != null && q != null) { a += p; b += q }; t += 1.0 }
                val f = Alignment2D.fit(a, b)
                if (f != null) { fits[s.name] = f; if (s.name == "camera tracking") frameRms = f.rms }
            }
            val order = listOf("camera tracking", "inertial steps", "GPS").mapNotNull { n -> live.firstOrNull { it.name == n } }.filter { fits.containsKey(it.name) }
            if (order.isEmpty()) return null
            val ts = ArrayList<Double>(); val es = ArrayList<Double>(); val ns = ArrayList<Double>(); val who = ArrayList<String>()
            val start = frameSrc.position(firstTime(live)) ?: doubleArrayOf(0.0, 0.0)
            var cx = start[0]; var cy = start[1]
            var t = firstTime(live)
            ts += t; es += cx; ns += cy; who += order.first().name
            while (t + 1.0 <= durationS + 1e-9) {
                val t1 = t + 1.0
                var moved = false
                for (s in order) {
                    val a = s.position(t); val b = s.position(t1)
                    if (a != null && b != null) {
                        val f = fits.getValue(s.name)
                        val d = f.turn(b[0] - a[0], b[1] - a[1])
                        val len = hypot(d[0], d[1])
                        if (len / 1.0 > 12.0) continue // a jump, not a movement
                        cx += d[0]; cy += d[1]; ts += t1; es += cx; ns += cy; who += s.name; moved = true; break
                    }
                }
                if (!moved) { ts += t1; es += cx; ns += cy; who += "none" }
                t = t1
            }
            return FusedPath(ts.toDoubleArray(), es.toDoubleArray(), ns.toDoubleArray(), who.toTypedArray(),
                if (gps != null) "north-up (GPS)" else if (frameSrc.name == "camera tracking") "the camera's frame" else "the steps' frame (direction relative to the start)", frameRms)
        }

        private fun firstTime(live: List<DistanceSource>): Double {
            var t = 0.0
            while (t < 600.0) { if (live.any { it.position(t) != null }) return t; t += 1.0 }
            return 0.0
        }
    }
}
