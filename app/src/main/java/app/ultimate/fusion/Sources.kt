package app.ultimate.fusion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One witness to how far the phone travelled. Each sensor measures something different and fails differently, which is the whole
 * point of keeping them separate: where they agree the answer is far more believable than any one of them, and where they do not,
 * the pattern of disagreement says which one to doubt.
 *
 *  - Camera tracking (ARCore): smooth and accurate over metres, scale and heading drift slowly, and it can lose its place entirely.
 *  - GPS: knows where on Earth, but wanders by metres, so the path it draws is jittery and its length is inflated while slow.
 *  - Inertial steps: always available, but each step length is a model and every error accumulates.
 */
interface DistanceSource {
    val name: String
    val basis: Basis
    /** Relative 1-sigma error of a long distance (0.03 is 3%). */
    val relSigma: Double
    /** Error that does not grow with distance, metres (a start-up wobble, a fix's offset). */
    val absSigma: Double
    /** Metres moved in [span], or null if this sensor did not see all of it (so it never guesses). */
    fun length(span: Span): Double?
    /** Spans where this sensor was blind (lost tracking, no fix, no steady gait). */
    val blind: List<Span>
    /** A position (metres, in this sensor's own frame) at time [t], or null. */
    fun position(t: Double): DoubleArray?
    val usable: Boolean
}

/** Ordinary distance bookkeeping that every source shares. */
abstract class PolylineSource(final override val name: String, final override val basis: Basis, final override val relSigma: Double, final override val absSigma: Double,
                              protected val t: DoubleArray, protected val e: DoubleArray, protected val n: DoubleArray, final override val blind: List<Span>,
                              /** Steps longer than this per second are a jump (a relocalisation, a GPS glitch) and are not walked. */
                              private val maxSpeed: Double) : DistanceSource {
    override val usable: Boolean get() = t.size >= 3
    var jumps = 0; private set

    override fun position(t0: Double): DoubleArray? {
        if (t.size < 2 || t0 < t.first() || t0 > t.last()) return null
        if (blind.any { t0 in it }) return null
        var lo = 0; var hi = t.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (t[mid] <= t0) lo = mid else hi = mid }
        val f = if (t[hi] == t[lo]) 0.0 else (t0 - t[lo]) / (t[hi] - t[lo])
        return doubleArrayOf(e[lo] + (e[hi] - e[lo]) * f, n[lo] + (n[hi] - n[lo]) * f)
    }

    override fun length(span: Span): Double? {
        if (t.size < 2 || span.from < t.first() - 1e-9 || span.to > t.last() + 1e-9) return null
        if (blind.any { it.overlaps(span) }) return null
        val a = position(span.from) ?: return null
        var px = a[0]; var py = a[1]; var pt = span.from
        var total = 0.0
        var i = 0
        while (i < t.size && t[i] <= span.from) i++
        while (i < t.size && t[i] < span.to) { total += seg(px, py, pt, e[i], n[i], t[i]); px = e[i]; py = n[i]; pt = t[i]; i++ }
        val b = position(span.to) ?: return null
        total += seg(px, py, pt, b[0], b[1], span.to)
        return total
    }

    private fun seg(x0: Double, y0: Double, t0: Double, x1: Double, y1: Double, t1: Double): Double {
        val d = hypot(x1 - x0, y1 - y0)
        return if (t1 > t0 && d / (t1 - t0) > maxSpeed) 0.0 else d
    }

    /** Counts the jumps over the whole recording (for the report). */
    protected fun countJumps(): Int { var c = 0; for (i in 1 until t.size) { val dt = t[i] - t[i - 1]; if (dt > 0 && hypot(e[i] - e[i - 1], n[i] - n[i - 1]) / dt > maxSpeed) c++ }; jumps = c; return c }
    fun fullLength(): Double = if (t.size < 2) 0.0 else length(Span(t.first(), t.last())) ?: sumAll()
    private fun sumAll(): Double { var s = 0.0; for (i in 1 until t.size) if (blind.none { it.overlaps(Span(t[i - 1], t[i])) }) s += seg(e[i - 1], n[i - 1], t[i - 1], e[i], n[i], t[i]); return s }
    /** Distance over everything this sensor could see, leaving out its blind spans. */
    fun total(): Quantity {
        val d = sumAll()
        return Quantity(d, sqrt((relSigma * d) * (relSigma * d) + absSigma * absSigma), "m", Basis.Derived, name, if (blind.isNotEmpty()) "excludes ${"%.0f".format(blind.sumOf { it.length })} s it could not see" else "")
    }
}

/** Camera motion tracking: positions on the horizontal plane, blind whenever tracking was lost. */
class PoseSource(trace: Trace, scale: Double = 1.0, relSigma: Double = 0.02) :
    PolylineSource("camera tracking", Basis.Derived, relSigma, 0.4, build(trace, scale).t, build(trace, scale).e, build(trace, scale).n, build(trace, scale).blind, 8.0) {
    init { countJumps() }
    private class Built(val t: DoubleArray, val e: DoubleArray, val n: DoubleArray, val blind: List<Span>)
    companion object {
        private fun build(trace: Trace, scale: Double): Built {
            val ps = trace.poses
            val tt = ArrayList<Double>(); val ee = ArrayList<Double>(); val nn = ArrayList<Double>(); val blind = ArrayList<Span>()
            var lostFrom: Double? = null
            for (p in ps) {
                if (p.tracking) {
                    lostFrom?.let { if (p.t - it > 0.5) blind += Span(it, p.t); lostFrom = null }
                    tt += p.t; ee += p.x * scale; nn += -p.z * scale   // ARCore: x right, z toward the viewer; so (x, -z) is (east, north) when the walk frame is unrotated
                } else if (lostFrom == null) lostFrom = p.t
            }
            lostFrom?.let { blind += Span(it, (ps.lastOrNull()?.t ?: it)) }
            return Built(tt.toDoubleArray(), ee.toDoubleArray(), nn.toDoubleArray(), blind)
        }
    }
}

/**
 * GPS, smoothed. A fix is a noisy point; chaining raw fixes adds the noise as extra distance (a phone lying still can "walk" a
 * kilometre a day). A constant-velocity Kalman smoother (forwards, then backwards) gives each fix a position consistent with the
 * others and weights each by its own stated accuracy. Fixes worse than [MAX_ACC_M] are ignored. The reported accuracy is taken as
 * one sigma, which is optimistic for some receivers; the calibration layer is where that would be learned.
 */
class GpsSource(trace: Trace, relSigma: Double = 0.04, private val anchorLat: Double, private val anchorLon: Double, private val sm: Smoothed) :
    PolylineSource("GPS", Basis.Derived, relSigma, max(3.0, sm.medianAcc), sm.t, sm.e, sm.n, sm.blind, 12.0) {
    init { countJumps() }
    class Smoothed(val t: DoubleArray, val e: DoubleArray, val n: DoubleArray, val blind: List<Span>, val medianAcc: Double, val latRef: Double, val lonRef: Double)
    /** Metres east/north of the first accepted fix → latitude/longitude. */
    fun toLatLon(e: Double, n: Double): Pair<Double, Double> = (anchorLat + Math.toDegrees(n / R)) to (anchorLon + Math.toDegrees(e / (R * cos(Math.toRadians(anchorLat)))))

    companion object {
        const val MAX_ACC_M = 30.0
        private const val R = 6_371_000.0
        fun of(trace: Trace): GpsSource? {
            val fx = trace.fixes.filter { it.accuracyM <= MAX_ACC_M && it.lat.isFinite() && it.lon.isFinite() }
            if (fx.size < 4) return null
            val lat0 = fx.first().lat; val lon0 = fx.first().lon
            val k = cos(Math.toRadians(lat0))
            val ex = DoubleArray(fx.size) { Math.toRadians(fx[it].lon - lon0) * R * k }
            val ny = DoubleArray(fx.size) { Math.toRadians(fx[it].lat - lat0) * R }
            val tt = DoubleArray(fx.size) { fx[it].t }
            val acc = DoubleArray(fx.size) { max(3.0, fx[it].accuracyM) }
            val se = smooth(tt, ex, acc); val sn = smooth(tt, ny, acc)
            val blind = ArrayList<Span>()
            for (i in 1 until fx.size) if (tt[i] - tt[i - 1] > 10.0) blind += Span(tt[i - 1], tt[i])
            val med = acc.sorted()[acc.size / 2]
            val sm = Smoothed(tt, se, sn, blind, med, lat0, lon0)
            return GpsSource(trace, 0.04, lat0, lon0, sm)
        }

        /** Constant-velocity Kalman filter + Rauch-Tung-Striebel smoother on one axis. */
        fun smooth(t: DoubleArray, z: DoubleArray, sigma: DoubleArray, accelSigma: Double = 1.2): DoubleArray {
            val n = z.size
            val xs = Array(n) { DoubleArray(2) }; val ps = Array(n) { DoubleArray(3) } // P = [p00, p01, p11]
            val xp = Array(n) { DoubleArray(2) }; val pp = Array(n) { DoubleArray(3) }
            var x0 = z[0]; var v0 = 0.0; var p00 = sigma[0] * sigma[0]; var p01 = 0.0; var p11 = 4.0
            xs[0][0] = x0; xs[0][1] = v0; ps[0][0] = p00; ps[0][1] = p01; ps[0][2] = p11
            xp[0][0] = x0; xp[0][1] = v0; pp[0][0] = p00; pp[0][1] = p01; pp[0][2] = p11
            for (i in 1 until n) {
                val dt = (t[i] - t[i - 1]).coerceIn(0.05, 30.0)
                val q = accelSigma * accelSigma
                // predict
                val px = x0 + v0 * dt; val pv = v0
                val a00 = p00 + 2 * dt * p01 + dt * dt * p11 + q * dt * dt * dt * dt / 4
                val a01 = p01 + dt * p11 + q * dt * dt * dt / 2
                val a11 = p11 + q * dt * dt
                xp[i][0] = px; xp[i][1] = pv; pp[i][0] = a00; pp[i][1] = a01; pp[i][2] = a11
                // update with a position measurement
                val r = sigma[i] * sigma[i]
                val s = a00 + r
                val k0 = a00 / s; val k1 = a01 / s
                val innov = z[i] - px
                x0 = px + k0 * innov; v0 = pv + k1 * innov
                p00 = (1 - k0) * a00; p01 = (1 - k0) * a01; p11 = a11 - k1 * a01
                xs[i][0] = x0; xs[i][1] = v0; ps[i][0] = p00; ps[i][1] = p01; ps[i][2] = p11
            }
            // smooth backwards
            val out = DoubleArray(n); out[n - 1] = xs[n - 1][0]
            var sx = xs[n - 1][0]; var sv = xs[n - 1][1]
            for (i in n - 2 downTo 0) {
                val dt = (t[i + 1] - t[i]).coerceIn(0.05, 30.0)
                val pa = ps[i]; val pn = pp[i + 1]
                val det = pn[0] * pn[2] - pn[1] * pn[1]
                if (abs(det) < 1e-12) { out[i] = xs[i][0]; continue }
                // C = P_i F^T P_pred^{-1}
                val f00 = 1.0; val f01 = dt
                val pf00 = pa[0] * f00 + pa[1] * f01; val pf01 = pa[1] * f00 + pa[2] * f01   // first row of P F^T? (P F^T)[0][*]
                val pf10 = pa[1]; val pf11 = pa[2]
                val i00 = pn[2] / det; val i01 = -pn[1] / det; val i11 = pn[0] / det
                val c00 = pf00 * i00 + pf01 * i01; val c01 = pf00 * i01 + pf01 * i11
                val c10 = pf10 * i00 + pf11 * i01; val c11 = pf10 * i01 + pf11 * i11
                val dx = sx - xp[i + 1][0]; val dv = sv - xp[i + 1][1]
                sx = xs[i][0] + c00 * dx + c01 * dv
                sv = xs[i][1] + c10 * dx + c11 * dv
                out[i] = sx
            }
            return out
        }
    }
}

/** Inertial steps, as a distance source. Blind where the phone moved without a steady gait (so a bicycle or a fidgeting hand is not read as standing still). */
class PdrSource(val result: Pdr.Result, private val dur: Double) :
    PolylineSource("inertial steps", Basis.Derived, result.stride.relSigma, 1.0, times(result), result.east, result.north, blindSpans(result, dur), 9.0) {
    init { countJumps() }
    /** Steps carry their lengths exactly, so distance over a span is their sum, not a straight-line chord. */
    override fun length(span: Span): Double? {
        if (blind.any { it.overlaps(span) }) return null
        if (result.steps.isEmpty() || span.from < -1e-9 || span.to > dur + 1e-9) return null
        var s = 0.0
        for (st in result.steps) if (st.t > span.from && st.t <= span.to) s += st.length
        return s
    }
    override val usable: Boolean get() = result.steps.size >= 8
    companion object {
        private fun times(r: Pdr.Result) = DoubleArray(r.steps.size) { r.steps[it].t }
        private fun blindSpans(r: Pdr.Result, dur: Double): List<Span> {
            val out = ArrayList<Span>()
            for (w in r.windows) if (w.gait == Gait.Other) { val l = out.lastOrNull(); if (l != null && w.span.from <= l.to + 1e-9) out[out.size - 1] = Span(l.from, w.span.to) else out += w.span }
            return out
        }
    }
    @Suppress("unused") private val unusedPi = PI
}
