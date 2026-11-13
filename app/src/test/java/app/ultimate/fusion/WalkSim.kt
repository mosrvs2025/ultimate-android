package app.ultimate.fusion

import app.ultimate.worldscan.LogLines
import app.ultimate.worldscan.TrackingQuality
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A SIMULATOR for tests: it walks a known course and writes the sensor log those sensors would have produced, in the real World Scan
 * log format, so tests read it with the real reader. Everything it makes is labelled simulation and is used only to test; the app never
 * fabricates sensor data. Because the course is known, every distance, lap and turn the analysis reports can be checked against the truth.
 */
class SimConfig(
    val seed: Long = 1,
    /** A closed polygon, metres east/north. The walker goes round it [laps] times. */
    val course: List<DoubleArray> = square(100.0),
    val laps: Int = 1,
    val cadenceHz: Double = 1.8,
    /** True stride relative to the default model: 1.12 means this person's steps are 12% longer than the model thinks. */
    val strideScale: Double = 1.0,
    val bounceAmp: Double = 3.0,
    /** Bounces per real step: 2 mimics a swinging arm that shakes the phone twice a step. */
    val bouncesPerStep: Int = 1,
    val imu: Boolean = true, val gyro: Boolean = true, val gyroBias: Double = 0.01,
    val camera: Boolean = true, val camScale: Double = 1.0, val camRotDeg: Double = 37.0, val camLoss: List<ClosedFloatingPointRange<Double>> = emptyList(), val camJump: Double = 3.0,
    val gps: Boolean = true, val gpsSigma: Double = 4.0, val gpsRho: Double = 0.9, val gpsReportedAcc: Double = 5.0, val gpsDropout: ClosedFloatingPointRange<Double>? = null,
    val startStillS: Double = 6.0, val endStillS: Double = 3.0, val heart: Boolean = false, val stillOnly: Boolean = false, val stillSeconds: Double = 120.0,
) {
    companion object {
        fun square(side: Double) = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(side, 0.0), doubleArrayOf(side, side), doubleArrayOf(0.0, side))
    }
}

class SimOut(val lines: List<String>, val truthMetres: Double, val durationS: Double)

object WalkSim {
    private const val G = 9.81
    private const val BOOT_NS = 1_000_000_000_000L
    private const val LAT0 = 40.0; private const val LON0 = -74.0
    private const val R = 6_371_000.0

    fun run(c: SimConfig): SimOut {
        val rnd = Random(c.seed)
        val lines = ArrayList<String>()
        val stride = StrideModel(1.0).length(c.cadenceHz) * c.strideScale
        val speed = if (c.stillOnly) 0.0 else c.cadenceHz * stride
        // Legs of the closed course.
        val pts = ArrayList<DoubleArray>(); repeat(c.laps) { pts += c.course }; pts += c.course.first()
        val legs = (0 until pts.size - 1).map { Triple(pts[it], pts[it + 1], hypot(pts[it + 1][0] - pts[it][0], pts[it + 1][1] - pts[it][1])) }
        val total = if (c.stillOnly) 0.0 else legs.sumOf { it.third }
        val walkS = if (c.stillOnly) c.stillSeconds else total / speed
        val dur = c.startStillS + walkS + c.endStillS
        val dt = 0.02
        var psi = if (legs.isNotEmpty()) bearing(legs[0]) else 0.0
        var gpsE = 0.0; var gpsN = 0.0
        val frameOff = 5_000_000_000L  // the camera's own clock, a different base from the sensors'
        var jumpApplied = 0.0
        var nextPose = 0.0; var nextGps = 0.0; var nextHr = 0.0
        var lostBefore = false
        var step = 0
        val n = (dur / dt).toInt()
        for (k in 0..n) {
            val t = k * dt
            val walking = !c.stillOnly && t >= c.startStillS && t < c.startStillS + walkS
            // Position along the course.
            val d = if (walking) (t - c.startStillS) * speed else if (t >= c.startStillS + walkS) total else 0.0
            val (e, nn, target) = at(legs, d)
            val prevPsi = psi
            if (walking) psi += (target - psi).let { wrap(it) } * (dt / 0.5)
            val yawRateCompass = wrap(psi - prevPsi) / dt            // clockwise positive
            val tNs = BOOT_NS + (t * 1e9).toLong()
            if (c.imu) {
                val f = c.cadenceHz * c.bouncesPerStep
                val bounce = if (walking) c.bounceAmp * sin(2 * PI * f * (t - c.startStillS)) else 0.0
                val sway = if (walking) 0.4 * sin(PI * c.cadenceHz * (t - c.startStillS)) else 0.0
                val ax = sway + rnd.nextGaussian() * 0.05; val ay = rnd.nextGaussian() * 0.05; val az = G + bounce + rnd.nextGaussian() * 0.05
                lines += LogLines.sensor(1, tNs, 3, floatArrayOf(ax.toFloat(), ay.toFloat(), az.toFloat()))
                if (c.gyro) lines += LogLines.sensor(4, tNs + 2_000_000L, 3, floatArrayOf((rnd.nextGaussian() * 0.003).toFloat(), (rnd.nextGaussian() * 0.003).toFloat(), (-yawRateCompass + c.gyroBias + rnd.nextGaussian() * 0.004).toFloat()))
            }
            if (c.camera && t >= nextPose) {
                nextPose += 1.0 / 15
                val lost = c.camLoss.any { t in it }
                if (lostBefore && !lost) jumpApplied += c.camJump
                lostBefore = lost
                val th = Math.toRadians(c.camRotDeg)
                val pe = if (lost) (lastE) else e; val pn = if (lost) lastN else nn
                if (!lost) { lastE = e; lastN = nn }
                val x = c.camScale * (cos(th) * pe - sin(th) * pn) + jumpApplied + rnd.nextGaussian() * 0.01
                val z = -c.camScale * (sin(th) * pe + cos(th) * pn) + rnd.nextGaussian() * 0.01
                val q = if (lost) TrackingQuality.NOT_TRACKING else TrackingQuality.TRACKING
                lines += LogLines.pose(tNs + frameOff, tNs + 35_000_000L + (rnd.nextDouble() * 15_000_000).toLong(), x.toFloat(), 0f, z.toFloat(), 0f, 0f, 0f, 1f, q)
            }
            if (c.gps && t >= nextGps) {
                nextGps += 1.0
                gpsE = c.gpsRho * gpsE + sqrt1(1 - c.gpsRho * c.gpsRho) * c.gpsSigma * rnd.nextGaussian()
                gpsN = c.gpsRho * gpsN + sqrt1(1 - c.gpsRho * c.gpsRho) * c.gpsSigma * rnd.nextGaussian()
                val drop = c.gpsDropout?.contains(t) == true
                if (!drop) {
                    val lat = LAT0 + Math.toDegrees((nn + gpsN) / R); val lon = LON0 + Math.toDegrees((e + gpsE) / (R * cos(Math.toRadians(LAT0))))
                    lines += LogLines.location(tNs, lat, lon, c.gpsReportedAcc.toFloat(), null, null, null, "gps")
                }
            }
            if (c.heart && t >= nextHr) { nextHr += 1.0; lines += "{\"type\":\"hr\",\"t\":$tNs,\"bpm\":${120 + 30 * (if (walking) 1 else 0) + rnd.nextGaussian() * 2}}" }
            step++
        }
        return SimOut(lines, total, dur)
    }

    private var lastE = 0.0; private var lastN = 0.0
    private fun sqrt1(x: Double) = Math.sqrt(x.coerceAtLeast(0.0))
    private fun bearing(l: Triple<DoubleArray, DoubleArray, Double>) = Math.atan2(l.second[0] - l.first[0], l.second[1] - l.first[1])
    private fun wrap(a: Double): Double { var x = a; while (x > PI) x -= 2 * PI; while (x < -PI) x += 2 * PI; return x }
    private fun at(legs: List<Triple<DoubleArray, DoubleArray, Double>>, d: Double): Triple<Double, Double, Double> {
        if (legs.isEmpty()) return Triple(0.0, 0.0, 0.0)
        var rem = d
        for (l in legs) { if (rem <= l.third) { val f = rem / l.third; return Triple(l.first[0] + (l.second[0] - l.first[0]) * f, l.first[1] + (l.second[1] - l.first[1]) * f, bearing(l)) }; rem -= l.third }
        val l = legs.last(); return Triple(l.second[0], l.second[1], bearing(l))
    }
}
