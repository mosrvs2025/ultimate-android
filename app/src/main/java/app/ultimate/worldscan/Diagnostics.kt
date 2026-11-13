package app.ultimate.worldscan

import kotlin.math.hypot

/**
 * Says what is going wrong with the depth, from what the depth filters have actually been rejecting over the last few seconds, instead of
 * guessing from one frame. Fed the integrator's running totals; looks at the change since the last look. Pure, so it is tested.
 *
 * Standing still is not a problem (a picture too like the last one is skipped on purpose), so only the two things a person can fix are
 * reported: moving or turning too fast, and depth the camera is not confident about (dark, plain or too far away).
 */
class MappingHealth(private val windowMs: Long = 5000, private val minFrames: Int = 15) {
    private var at = 0L; private var frames = 0; private var fast = 0; private var pixels = 0L; private var weak = 0L
    /** The advice for the last full window, or null when depth is fine (or there is not enough to say). */
    var advice: String? = null; private set
    /** Share of depth pixels in the last window that were too weak or too far to use (0..1), or -1 before there is a window. */
    var weakShare = -1f; private set
    var fastShare = -1f; private set

    fun update(nowMs: Long, frames: Int, tooFast: Int, pixels: Long, lowConfidence: Long, outOfRange: Long) {
        if (at == 0L) { at = nowMs; this.frames = frames; fast = tooFast; this.pixels = pixels; weak = lowConfidence + outOfRange; return }
        if (nowMs - at < windowMs) return
        val df = frames - this.frames; val dFast = tooFast - fast; val dPix = pixels - this.pixels; val dWeak = lowConfidence + outOfRange - weak
        at = nowMs; this.frames = frames; fast = tooFast; this.pixels = pixels; weak = lowConfidence + outOfRange
        if (df < minFrames) { advice = null; return }       // standing still or too few pictures: nothing to say
        fastShare = dFast.toFloat() / df
        weakShare = if (dPix > 0) dWeak.toFloat() / dPix else 0f
        advice = when {
            fastShare >= 0.30f -> "Slow down and keep the phone steady: ${"%.0f".format(fastShare * 100)}% of the last few seconds of depth was thrown away for moving or turning too fast"
            weakShare >= 0.75f -> "Depth is weak here (${"%.0f".format(weakShare * 100)}% unusable): more light, things with texture, and surfaces 1 to 5 m away"
            else -> null
        }
    }
}

/** How the walk and GPS disagreed over the course of a walk: the evidence for drift (a steady rise) versus GPS noise (spikes). */
object DriftSeries {
    /**
     * For each GPS fix, the distance in metres between where the walk says the phone was at that moment and where GPS says, as
     * (seconds since the walk began, metres). Both must already be in the same frame (see [GeoRegistration.toLocal]) and on the same clock.
     * Fixes with no tracked pose within [maxGapNs] are left out.
     */
    fun of(t: LongArray, x: FloatArray, z: FloatArray, tracked: BooleanArray, fixT: LongArray, fixX: FloatArray, fixZ: FloatArray, maxGapNs: Long = 1_500_000_000L): List<Pair<Double, Double>> {
        if (t.size < 2) return emptyList()
        val out = ArrayList<Pair<Double, Double>>()
        for (k in fixT.indices) {
            val ft = fixT[k]
            if (ft < t.first() || ft > t.last()) continue
            var lo = 0; var hi = t.size - 1
            while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (t[mid] <= ft) lo = mid else hi = mid }
            if (!tracked[lo] || !tracked[hi] || t[hi] - t[lo] > maxGapNs) continue
            val f = if (t[hi] == t[lo]) 0f else ((ft - t[lo]).toDouble() / (t[hi] - t[lo])).toFloat()
            val px = x[lo] + (x[hi] - x[lo]) * f; val pz = z[lo] + (z[hi] - z[lo]) * f
            out += ((ft - t.first()) / 1e9) to hypot((px - fixX[k]).toDouble(), (pz - fixZ[k]).toDouble())
        }
        return out
    }

    /** Median of the residuals in the first and in the last third of the walk: a rise from one to the other is drift (or a bad fit), not noise. */
    fun firstAndLastThird(series: List<Pair<Double, Double>>): Pair<Double, Double>? {
        if (series.size < 9) return null
        val third = series.size / 3
        fun med(l: List<Pair<Double, Double>>) = l.map { it.second }.sorted().let { it[it.size / 2] }
        return med(series.take(third)) to med(series.takeLast(third))
    }

}
