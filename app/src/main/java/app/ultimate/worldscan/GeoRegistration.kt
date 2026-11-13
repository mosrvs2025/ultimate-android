package app.ultimate.worldscan

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class GeoLevel { GOOD, WEAK, NONE }

/**
 * How a walk's own frame sits on the Earth: a turn about the vertical and a shift, found by comparing where motion tracking says the
 * phone was with where GPS says it was. Motion tracking is smooth and accurate over metres but starts facing an arbitrary way; GPS
 * knows north but wanders by metres. Each covers for the other. [yawSigmaDeg] is the heading uncertainty that the geometry of *this*
 * walk allows (a short walk cannot fix a heading to better than the GPS noise over its length) and [level] says whether it is good
 * enough to rely on. Pure, so it is tested.
 *
 * The walk's horizontal plane is (x, -z) with x east and -z north when [thetaRad] is 0: ARCore's frame is right-handed with Y up, so
 * looking down from above, x points right and z points toward you (south).
 */
class GeoRegistration(
    val anchor: GeoAnchor, val thetaRad: Double, val tx: Double, val ty: Double,
    val yawSigmaDeg: Double, val posSigmaM: Double, val rmsM: Double, val maxResidualM: Double,
    val used: Int, val total: Int, val scale: Double, val chi: Double, val level: GeoLevel, val reason: String,
    /** How strongly one fix's error carries over to the next (0 = independent, 0.95 = nearly the same error for a long time), estimated from the fit's own residuals; the uncertainties above already include it. */
    val rho: Double = 0.0,
) {
    /** East and north (metres from the anchor) of a point of the walk's frame. */
    fun toEnu(x: Float, z: Float): Pair<Double, Double> {
        val ax = x.toDouble(); val ay = -z.toDouble(); val c = cos(thetaRad); val s = sin(thetaRad)
        return (c * ax - s * ay + tx) to (s * ax + c * ay + ty)
    }
    /** The walk-frame (x, z) of an east/north position. */
    fun toLocal(east: Double, north: Double): Pair<Float, Float> {
        val c = cos(thetaRad); val s = sin(thetaRad); val ex = east - tx; val ny = north - ty
        return ((c * ex + s * ny).toFloat()) to (-(-s * ex + c * ny)).toFloat()
    }
    fun toLatLon(x: Float, z: Float): Pair<Double, Double> = toEnu(x, z).let { anchor.unproject(it.first, it.second) }
    /** Compass bearing (degrees clockwise from north) of the walk frame's -Z axis. */
    val northOffsetDeg: Double get() = ((-Math.toDegrees(thetaRad)) % 360.0 + 360.0) % 360.0
    val usable: Boolean get() = level != GeoLevel.NONE
    fun summary(): String = when (level) {
        GeoLevel.NONE -> "Not placed on the map: $reason"
        else -> "Placed on the map to ±%.1f° heading, ±%.0f m position (GPS fit %.1f m, %d of %d fixes)".format(yawSigmaDeg, posSigmaM, rmsM, used, total)
    }
}

/** The walk position at a GPS fix, matched in time. Plane coordinates are (x, -z). */
class GeoPair(val tNs: Long, val ax: Double, val ay: Double, val e: Double, val n: Double, val sigma: Double)

object GeoFit {
    /** GPS noise is correlated from fix to fix (a bias, not fresh noise each second), so no fix is trusted to better than this. */
    const val SIGMA_FLOOR_M = 2.0

    /** Matches fixes to the walk in time: only fixes the walk was tracked around. [track] must be on the elapsed-realtime clock (see [ClockSync]). */
    fun pairs(track: WalkTrack, fixes: List<GeoFix>, anchor: GeoAnchor, maxAccuracyM: Float = 25f): List<GeoPair> {
        val out = ArrayList<GeoPair>()
        for (f in fixes) {
            if (!f.isUsable(maxAccuracyM)) continue
            val p = track.at(f.elapsedRealtimeNanos) ?: continue
            val enu = anchor.project(f)
            // Android's accuracy is a 68%-confidence radius: about 1.5 sigma for a circular normal error.
            out += GeoPair(f.elapsedRealtimeNanos, p.x.toDouble(), -p.z.toDouble(), enu.eastMeters, enu.northMeters, max(SIGMA_FLOOR_M, f.horizontalAccuracyMeters / 1.5))
        }
        return out
    }

    /**
     * Best turn and shift taking the walk onto GPS, by weighted least squares with a Huber loss so that a fix thrown off by a building
     * (multipath) does not drag the whole fit. A rigid fit only: motion tracking is metric, so a fitted *scale* far from 1 is reported as
     * evidence that motion tracking or GPS misbehaved, not used to rescale the map.
     */
    fun fit(pairs: List<GeoPair>, anchor: GeoAnchor, total: Int = pairs.size): GeoRegistration {
        if (pairs.size < 4) return none(anchor, total, "only ${pairs.size} usable GPS fix${if (pairs.size == 1) "" else "es"} during the walk (needs 4+; walk outdoors in the open)")
        val n = pairs.size; val mult = DoubleArray(n) { 1.0 }
        var theta = 0.0; var tx = 0.0; var ty = 0.0
        var w = DoubleArray(n)
        repeat(12) {
            for (i in 0 until n) w[i] = mult[i] / (pairs[i].sigma * pairs[i].sigma)
            val sw = w.sum()
            val cax = (0 until n).sumOf { w[it] * pairs[it].ax } / sw; val cay = (0 until n).sumOf { w[it] * pairs[it].ay } / sw
            val ce = (0 until n).sumOf { w[it] * pairs[it].e } / sw; val cn = (0 until n).sumOf { w[it] * pairs[it].n } / sw
            var dot = 0.0; var cross = 0.0
            for (i in 0 until n) { val ax = pairs[i].ax - cax; val ay = pairs[i].ay - cay; val ex = pairs[i].e - ce; val ey = pairs[i].n - cn; dot += w[i] * (ax * ex + ay * ey); cross += w[i] * (ax * ey - ay * ex) }
            theta = atan2(cross, dot)
            val c = cos(theta); val s = sin(theta)
            tx = ce - (c * cax - s * cay); ty = cn - (s * cax + c * cay)
            for (i in 0 until n) {
                val r = hypot(c * pairs[i].ax - s * pairs[i].ay + tx - pairs[i].e, s * pairs[i].ax + c * pairs[i].ay + ty - pairs[i].n)
                val k = 1.5 * pairs[i].sigma
                mult[i] = if (r <= k) 1.0 else k / r
            }
        }
        for (i in 0 until n) w[i] = mult[i] / (pairs[i].sigma * pairs[i].sigma)
        val sw = w.sum()
        val c = cos(theta); val s = sin(theta)
        val res = DoubleArray(n) { hypot(c * pairs[it].ax - s * pairs[it].ay + tx - pairs[it].e, s * pairs[it].ax + c * pairs[it].ay + ty - pairs[it].n) }
        val inlier = BooleanArray(n) { res[it] <= 3.0 * pairs[it].sigma }
        val used = inlier.count { it }
        val cax = (0 until n).sumOf { w[it] * pairs[it].ax } / sw; val cay = (0 until n).sumOf { w[it] * pairs[it].ay } / sw
        // Fisher information about the heading: how widely the walk spreads, relative to GPS noise. sigma = 1/sqrt(information).
        val info = (0 until n).sumOf { val dx = pairs[it].ax - cax; val dy = pairs[it].ay - cay; w[it] * (dx * dx + dy * dy) }
        // GPS errors are not fresh noise at every fix: a bias (buildings, which satellites are in view) lasts many seconds, so 140 fixes are worth far fewer
        // independent ones. How much one fix's error carries over to the next is read off this fit's own residuals (lag-1 correlation of the east/north
        // residual vectors), and the uncertainties are widened by sqrt((1+rho)/(1-rho)), the exact factor for an AR(1) error. White noise leaves them as they were.
        var lagNum = 0.0; var lagDen = 0.0
        for (i in 1 until n) {
            val ex = c * pairs[i].ax - s * pairs[i].ay + tx - pairs[i].e; val ey = s * pairs[i].ax + c * pairs[i].ay + ty - pairs[i].n
            val fx = c * pairs[i - 1].ax - s * pairs[i - 1].ay + tx - pairs[i - 1].e; val fy = s * pairs[i - 1].ax + c * pairs[i - 1].ay + ty - pairs[i - 1].n
            lagNum += ex * fx + ey * fy; lagDen += fx * fx + fy * fy
        }
        val rho = if (lagDen > 0) (lagNum / lagDen).coerceIn(0.0, 0.95) else 0.0
        val inflate = sqrt((1 + rho) / (1 - rho))
        val yawSigma = if (info > 0) Math.toDegrees(1.0 / sqrt(info)) * inflate else 180.0
        val medSigma = pairs.map { it.sigma }.sorted()[n / 2]
        val posSigma = max(1.0 / sqrt(sw), 0.5 * medSigma) * inflate
        val inl = (0 until n).filter { inlier[it] }
        val rms = if (inl.isEmpty()) Double.NaN else sqrt(inl.sumOf { res[it] * res[it] } / inl.size)
        val chi = if (inl.isEmpty()) Double.NaN else sqrt(inl.sumOf { (res[it] / pairs[it].sigma).let { z -> z * z } } / inl.size)
        // Free-scale fit, for the diagnostic only.
        var num = 0.0; var den = 0.0
        for (i in 0 until n) { val ax = pairs[i].ax - cax; val ay = pairs[i].ay - cay; val ex = pairs[i].e - ((0 until n).sumOf { w[it] * pairs[it].e } / sw); val ey = pairs[i].n - ((0 until n).sumOf { w[it] * pairs[it].n } / sw); num += w[i] * ((c * ax - s * ay) * ex + (s * ax + c * ay) * ey); den += w[i] * (ax * ax + ay * ay) }
        val scale = if (den > 0) num / den else 1.0
        val share = used.toDouble() / n
        val (level, reason) = when {
            used < 4 || share < 0.5 -> GeoLevel.NONE to "GPS and the walk disagree (only $used of $n fixes fit)"
            yawSigma > 20.0 -> GeoLevel.NONE to "the walk is too short or too straight in one place to tell which way it faced (±%.0f°); walk farther".format(yawSigma)
            yawSigma <= 4.0 && share >= 0.7 && abs(scale - 1.0) <= 0.08 && chi <= 2.5 -> GeoLevel.GOOD to "ok"
            else -> GeoLevel.WEAK to when { yawSigma > 4.0 -> "heading known only to ±%.0f°".format(yawSigma); abs(scale - 1.0) > 0.08 -> "walk length disagrees with GPS by %.0f%%".format(abs(scale - 1.0) * 100); else -> "GPS is noisy here" }
        }
        return GeoRegistration(anchor, theta, tx, ty, yawSigma, posSigma, rms, res.maxOrNull() ?: 0.0, used, total, scale, chi, level, reason, rho)
    }

    fun fit(track: WalkTrack, fixes: List<GeoFix>, anchor: GeoAnchor, maxAccuracyM: Float = 25f): GeoRegistration {
        val p = pairs(track, fixes, anchor, maxAccuracyM)
        return fit(p, anchor, fixes.size)
    }

    private fun none(anchor: GeoAnchor, total: Int, why: String) =
        GeoRegistration(anchor, 0.0, 0.0, 0.0, 180.0, Double.NaN, Double.NaN, Double.NaN, 0, total, 1.0, Double.NaN, GeoLevel.NONE, why)

    /** Smallest angle between two bearings, degrees (0..180). */
    fun angleBetween(a: Double, b: Double): Double { val d = abs(a - b) % 360.0; return min(d, 360.0 - d) }
}
