package app.ultimate.fusion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Step length from how fast the steps come: about 0.53 m at 1.2 steps a second (a stroll), 0.72 m at 1.8 (a walk), 1.0 m at 2.7 (a
 * jog). [scale] is what calibration learns for one person: a taller stride is a scale above 1. Uncalibrated the model is only good to
 * roughly [DEFAULT_REL_SIGMA] of the distance, and says so.
 */
class StrideModel(val scale: Double = 1.0, val relSigma: Double = DEFAULT_REL_SIGMA) {
    fun length(cadenceHz: Double): Double = (0.72 + 0.31 * (cadenceHz - 1.8)).coerceIn(0.35, 1.5) * scale
    companion object { const val DEFAULT_REL_SIGMA = 0.12 }
}

enum class Gait(val label: String) { Still("still"), Walking("walking"), Running("running"), Other("moving, not a steady gait") }

/**
 * Pedestrian dead reckoning from the phone's own inertial sensors: finds steps in the vertical bounce of the accelerometer, gives each a
 * length from the stride model and a direction from the gyroscope's turning about the vertical. It needs no camera and no signal, which
 * makes it the sensor that is always there, and the one that drifts: every error in step length and heading accumulates. It is therefore
 * used as one witness among several, never alone when something better exists.
 *
 * Honest limits: the count assumes one bounce per step (right for a phone on the body or in a hand; an arm-swinging phone can show two,
 * which the comparison with other sensors flags when the distance comes out about double); the heading is relative to where the recording
 * began (a compass or another sensor must supply north); and none of the thresholds have been checked against many people's real walks yet.
 */
object Pdr {
    const val FS = 50.0
    private const val MIN_STEP_GAP_S = 0.27
    private const val MAX_STEP_GAP_S = 1.0

    class Step(val t: Double, val peak: Double, val cadenceHz: Double, val length: Double, val headingRad: Double)
    class Window(val span: Span, val gait: Gait, val cadenceHz: Double?, val bounceRms: Double, val confidence: Double)
    class Result(
        val steps: List<Step>, val windows: List<Window>, val gyroUsed: Boolean, val gyroBiasDegPerS: Double,
        /** East/north of each step end (metres from where the recording began, heading measured from the starting direction). */
        val east: DoubleArray, val north: DoubleArray,
        val stride: StrideModel,
    ) {
        val distance: Quantity get() {
            val d = steps.sumOf { it.length }
            val n = steps.size.coerceAtLeast(1)
            val sigma = d * sqrt(stride.relSigma * stride.relSigma + 0.05 * 0.05 / n)
            return Quantity(d, sigma, "m", Basis.Derived, "inertial steps", "${steps.size} steps × a stride model" + if (stride.scale != 1.0) ", calibrated ×%.2f".format(stride.scale) else ", not calibrated")
        }
    }

    fun run(imu: Trace.Imu, stride: StrideModel = StrideModel()): Result {
        if (imu.size < FS * 2) return Result(emptyList(), emptyList(), false, 0.0, DoubleArray(0), DoubleArray(0), stride)
        val g = resample(imu)
        val n = g.n
        // Gravity direction: the 1 s average of the accelerometer.
        val w = (FS).toInt() or 1
        val mx = ma(g.ax, w); val my = ma(g.ay, w); val mz = ma(g.az, w)
        val vert = DoubleArray(n); val yaw = DoubleArray(n)
        for (i in 0 until n) {
            val m = sqrt(mx[i] * mx[i] + my[i] * my[i] + mz[i] * mz[i]).coerceAtLeast(1e-6)
            val ux = mx[i] / m; val uy = my[i] / m; val uz = mz[i] / m
            vert[i] = g.ax[i] * ux + g.ay[i] * uy + g.az[i] * uz - m
            yaw[i] = g.gx[i] * ux + g.gy[i] * uy + g.gz[i] * uz
        }
        val s = ma(vert, (FS * 0.1).toInt() or 1)
        val rms = sqrtOf(ma(DoubleArray(n) { s[it] * s[it] }, (FS * 2).toInt() or 1))

        // Gyro bias from the quietest stretches, then heading = minus the integral of the turn rate about up (clockwise positive, like a compass).
        val still = BooleanArray(n) { rms[it] < 0.12 }
        val quiet = ArrayList<Double>()
        for (i in 0 until n) if (still[i]) quiet += yaw[i]
        val bias = if (g.hasGyro && quiet.size > FS) quiet.sorted()[quiet.size / 2] else 0.0
        val heading = DoubleArray(n)
        if (g.hasGyro) { var h = 0.0; for (i in 1 until n) { h -= (yaw[i] - bias) / FS; heading[i] = h } }

        // Peaks of the bounce, strong relative to the bounce around them.
        val cand = ArrayList<Int>()
        for (i in 1 until n - 1) {
            if (s[i] > 0.7 && s[i] >= s[i - 1] && s[i] > s[i + 1] && s[i] >= 0.55 * sqrt(2.0) * rms[i]) {
                val gap = (MIN_STEP_GAP_S * FS).toInt()
                if (cand.isNotEmpty() && i - cand.last() < gap) { if (s[i] > s[cand.last()]) cand[cand.size - 1] = i } else cand += i
            }
        }
        // Keep only steady bouts: five or more peaks in a row, each a plausible stride apart, with an even rhythm.
        val keep = BooleanArray(cand.size)
        var a = 0
        while (a < cand.size) {
            var b = a
            while (b + 1 < cand.size && (cand[b + 1] - cand[b]) / FS in MIN_STEP_GAP_S..MAX_STEP_GAP_S) b++
            if (b - a + 1 >= 5) {
                val iv = DoubleArray(b - a) { (cand[a + it + 1] - cand[a + it]) / FS }
                val mean = iv.average(); val sd = sqrt(iv.sumOf { (it - mean) * (it - mean) } / iv.size)
                if (sd / mean < 0.35) for (k in a..b) keep[k] = true
            }
            a = b + 1
        }
        val peaks = cand.filterIndexed { i, _ -> keep[i] }
        val steps = ArrayList<Step>(); val east = ArrayList<Double>(); val north = ArrayList<Double>()
        var e = 0.0; var nn = 0.0
        for ((k, i) in peaks.withIndex()) {
            // Cadence: the median of the neighbouring intervals, so one odd step does not change a stride.
            val ivs = ArrayList<Double>()
            for (d in -2..1) { val p = k + d; if (p >= 0 && p + 1 < peaks.size && (peaks[p + 1] - peaks[p]) / FS in MIN_STEP_GAP_S..MAX_STEP_GAP_S) ivs += (peaks[p + 1] - peaks[p]) / FS }
            if (ivs.isEmpty()) continue
            val f = 1.0 / ivs.sorted()[ivs.size / 2]
            val len = stride.length(f)
            e += len * sin(heading[i]); nn += len * cos(heading[i])
            steps += Step(g.t0 + i / FS, s[i], f, len, heading[i]); east += e; north += nn
        }

        // Gait windows: 4 s, every 2 s.
        val windows = ArrayList<Window>()
        var t = 0.0
        val dur = (n - 1) / FS
        while (t + 4.0 <= dur + 1e-9) {
            val from = g.t0 + t; val to = from + 4.0
            val inW = steps.filter { it.t in from..to }
            val i0 = (t * FS).toInt(); val i1 = min(n, ((t + 4.0) * FS).toInt())
            var sum = 0.0; for (i in i0 until i1) sum += s[i] * s[i]
            val bounce = sqrt(sum / max(1, i1 - i0))
            windows += classify(Span(from, to), inW, bounce)
            t += 2.0
        }
        return Result(steps, windows, g.hasGyro, Math.toDegrees(bias), east.toDoubleArray(), north.toDoubleArray(), stride)
    }

    /**
     * Still, walking, running, or something else, from the step rhythm and the size of the bounce. The rhythm decides: nearly everybody
     * walks below about 2.2 steps a second and runs above about 2.5; in between, a strong bounce means running. These are rules of thumb
     * (the confidence says how far from a boundary the window is), not a trained classifier.
     */
    fun classify(span: Span, steps: List<Step>, bounceRms: Double): Window {
        if (bounceRms < 0.15 && steps.size < 3) return Window(span, Gait.Still, null, bounceRms, 0.9)
        if (steps.size < 4) return Window(span, Gait.Other, null, bounceRms, 0.5)
        val f = (steps.size - 1) / (steps.last().t - steps.first().t).coerceAtLeast(0.5)
        return when {
            f >= 2.5 -> Window(span, Gait.Running, f, bounceRms, min(0.95, 0.6 + (f - 2.5)))
            f >= 2.15 && bounceRms >= 4.0 -> Window(span, Gait.Running, f, bounceRms, 0.6)
            f >= 1.1 -> Window(span, Gait.Walking, f, bounceRms, min(0.95, 0.6 + 0.3 * min(1.0, (2.15 - f).coerceAtLeast(0.0))))
            else -> Window(span, Gait.Other, f, bounceRms, 0.4)
        }
    }

    private class Grid(val t0: Double, val n: Int, val ax: DoubleArray, val ay: DoubleArray, val az: DoubleArray, val gx: DoubleArray, val gy: DoubleArray, val gz: DoubleArray, val hasGyro: Boolean)

    private fun resample(imu: Trace.Imu): Grid {
        val t0 = imu.t.first(); val dur = imu.t.last() - t0
        val n = (dur * FS).toInt() + 1
        val ax = DoubleArray(n); val ay = DoubleArray(n); val az = DoubleArray(n); val gx = DoubleArray(n); val gy = DoubleArray(n); val gz = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val x = t0 + i / FS
            while (j < imu.size - 2 && imu.t[j + 1] < x) j++
            val dt = imu.t[j + 1] - imu.t[j]
            val f = if (dt <= 0) 0.0 else ((x - imu.t[j]) / dt).coerceIn(0.0, 1.0)
            fun lerp(a: FloatArray) = a[j] + (a[j + 1] - a[j]) * f
            ax[i] = lerp(imu.ax); ay[i] = lerp(imu.ay); az[i] = lerp(imu.az); gx[i] = lerp(imu.gx); gy[i] = lerp(imu.gy); gz[i] = lerp(imu.gz)
        }
        return Grid(t0, n, ax, ay, az, gx, gy, gz, imu.hasGyro)
    }

    /** Centred moving average. */
    fun ma(x: DoubleArray, width: Int): DoubleArray {
        val pre = DoubleArray(x.size + 1)
        for (i in x.indices) pre[i + 1] = pre[i] + x[i]
        val h = width / 2
        return DoubleArray(x.size) { i -> val a = max(0, i - h); val b = min(x.size, i + h + 1); (pre[b] - pre[a]) / (b - a) }
    }
    private fun sqrtOf(x: DoubleArray) = DoubleArray(x.size) { sqrt(x[it].coerceAtLeast(0.0)) }
    internal fun wrap(a: Double): Double { var x = a % (2 * PI); if (x > PI) x -= 2 * PI; if (x < -PI) x += 2 * PI; return x }
    internal fun angleDiff(a: Double, b: Double) = abs(wrap(a - b))
}
