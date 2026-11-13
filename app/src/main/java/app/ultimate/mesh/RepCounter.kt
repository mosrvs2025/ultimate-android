package app.ultimate.mesh

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Counts repetitions and finds sets of effort in a phone's acceleration, wherever the phone is: in a pocket, strapped to
 * an arm, held, or clipped to a bar. It sees only how the phone moved, so it can say "12 repetitions at about 24 a
 * minute", never which exercise it was; the numbers are ESTIMATES that depend on where the phone sat and are not
 * validated against a human count in this build.
 *
 * Method, all plain arithmetic: take the size of the acceleration, subtract its slow average (gravity and posture),
 * smooth it, find stretches of effort, and in each stretch count the peaks that stand clear of the rest, no closer
 * together than about half the dominant rhythm.
 */
object RepCounter {
    const val RATE = 50.0
    /** Below this much movement (m/s², root mean square) a half-second is rest. A phone lying still reads about 0.03. */
    const val ACTIVE_RMS = 0.45
    /** A gap of rest this long ends a set. */
    const val SET_GAP_S = 4.0
    const val MIN_SET_S = 3.0

    data class EffortSet(val startS: Double, val endS: Double, val reps: Int, val perMinute: Double, val intensity: Double)
    data class Summary(val seconds: Double, val activeSeconds: Double, val rms: Double, val peak: Double, val sets: List<EffortSet>) {
        val reps: Int get() = sets.sumOf { it.reps }
    }

    fun analyze(timeS: DoubleArray, axes: Array<DoubleArray>): Summary {
        require(axes.size == 3 && axes.all { it.size == timeS.size }) { "three acceleration axes of the same length" }
        if (timeS.size < 3) return Summary(0.0, 0.0, 0.0, 0.0, emptyList())
        val len = (timeS.last() - timeS.first())
        val u = Array(3) { resample(timeS, axes[it]) }
        if (u[0].size < RATE * 2) return Summary(len, 0.0, 0.0, 0.0, emptyList())
        // Per axis: remove the slow part (gravity and posture; 3 s so slow movements are kept), then smooth.
        val s = Array(3) { k ->
            val slow = movingAverage(u[k], (RATE * 3.0).toInt() or 1)
            movingAverage(DoubleArray(u[k].size) { i -> u[k][i] - slow[i] }, (RATE * 0.12).toInt() or 1)
        }
        val n = s[0].size
        val size = DoubleArray(n) { i -> sqrt(s[0][i] * s[0][i] + s[1][i] * s[1][i] + s[2][i] * s[2][i]) }

        val win = (RATE * 0.5).toInt()
        val windows = n / win
        val activeWin = BooleanArray(windows) { w -> rms(size, w * win, (w + 1) * win) > ACTIVE_RMS }
        var peak = 0.0
        for (x in size) peak = max(peak, x)

        // Stretches of effort, joined across short rests.
        val sets = mutableListOf<EffortSet>()
        var w = 0
        while (w < windows) {
            if (!activeWin[w]) { w++; continue }
            var end = w; var gap = 0
            var k = w
            while (k < windows) {
                if (activeWin[k]) { end = k; gap = 0 } else { gap++; if (gap * 0.5 > SET_GAP_S) break }
                k++
            }
            val from = w * win; val to = (end + 1) * win
            if ((to - from) / RATE >= MIN_SET_S) sets += measureSet(s, from, to)
            w = max(end + 1, w + 1)
        }
        return Summary((n - 1) / RATE, activeWin.count { it } * 0.5, rms(size, 0, n), peak, sets.filter { it.reps > 0 })
    }

    /**
     * Counts one set. The phone's movement is projected onto its main direction of travel (the principal axis of the
     * movement in this stretch) so one back-and-forth is one cycle however the phone is oriented; the size of the
     * acceleration alone would count each swing twice, or barely move, depending on how the phone is held.
     */
    private fun measureSet(s: Array<DoubleArray>, from: Int, to: Int): EffortSet {
        val axis = principalAxis(s, from, to)
        var seg = DoubleArray(to - from) { i -> s[0][from + i] * axis[0] + s[1][from + i] * axis[1] + s[2][from + i] * axis[2] }
        // Orient it so the longer tail is positive: the same movement then counts the same whichever way the axis came out.
        if (seg.sumOf { it * it * it } < 0) seg = DoubleArray(seg.size) { -seg[it] }
        val amp = percentile(seg.map { abs(it) }.sorted(), 0.95)
        val threshold = max(0.25, 0.45 * amp)
        val period = dominantPeriod(seg)
        val minSep = max((RATE * 0.35).toInt(), (period * 0.55).toInt())
        val peaks = mutableListOf<Int>()
        var i = 1
        while (i < seg.size - 1) {
            if (seg[i] >= threshold && seg[i] >= seg[i - 1] && seg[i] > seg[i + 1]) {
                if (peaks.isEmpty() || i - peaks.last() >= minSep) peaks += i
                else if (seg[i] > seg[peaks.last()]) peaks[peaks.size - 1] = i // a taller peak inside the dead zone replaces the earlier one
            }
            i++
        }
        val reps = peaks.size
        val span = if (reps > 1) (peaks.last() - peaks.first()) / RATE else 0.0
        val perMin = if (reps > 1 && span > 0) (reps - 1) / span * 60.0 else 0.0
        return EffortSet(from / RATE, to / RATE, reps, perMin, rms(seg, 0, seg.size))
    }

    /** The direction the movement mostly goes along: the top eigenvector of the 3x3 covariance, by power iteration. */
    private fun principalAxis(s: Array<DoubleArray>, from: Int, to: Int): DoubleArray {
        val c = Array(3) { DoubleArray(3) }
        for (i in from until to) for (a in 0..2) for (b in 0..2) c[a][b] += s[a][i] * s[b][i]
        var v = doubleArrayOf(1.0, 0.7, 0.4)
        repeat(40) {
            val nv = DoubleArray(3) { a -> c[a][0] * v[0] + c[a][1] * v[1] + c[a][2] * v[2] }
            val norm = sqrt(nv[0] * nv[0] + nv[1] * nv[1] + nv[2] * nv[2])
            if (norm > 0) v = DoubleArray(3) { nv[it] / norm }
        }
        return v
    }

    /** The repeating rhythm of a stretch, in samples: the strongest autocorrelation between 0.35 s and 4 s, or 1 s if none. */
    private fun dominantPeriod(x: DoubleArray): Int {
        val lo = (RATE * 0.35).toInt(); val hi = min((RATE * 4).toInt(), x.size / 2)
        if (hi <= lo) return RATE.toInt()
        var best = 0; var bestV = 0.0
        for (lag in lo..hi) {
            var sum = 0.0
            for (i in 0 until x.size - lag) sum += x[i] * x[i + lag]
            if (sum > bestV) { bestV = sum; best = lag }
        }
        return if (best == 0) RATE.toInt() else best
    }

    private fun resample(t: DoubleArray, m: DoubleArray): DoubleArray {
        val n = ((t.last() - t.first()) * RATE).toInt() + 1
        val out = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val x = t.first() + i / RATE
            while (j < t.size - 2 && t[j + 1] < x) j++
            val dt = t[j + 1] - t[j]
            val f = if (dt <= 0) 0.0 else ((x - t[j]) / dt).coerceIn(0.0, 1.0)
            out[i] = m[j] + (m[j + 1] - m[j]) * f
        }
        return out
    }

    private fun movingAverage(x: DoubleArray, width: Int): DoubleArray {
        val pre = DoubleArray(x.size + 1)
        for (i in x.indices) pre[i + 1] = pre[i] + x[i]
        val h = width / 2
        return DoubleArray(x.size) { i -> val a = max(0, i - h); val b = min(x.size, i + h + 1); (pre[b] - pre[a]) / (b - a) }
    }

    private fun rms(x: DoubleArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / max(1, to - from))
    }

    private fun percentile(sorted: List<Double>, p: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()]
}
