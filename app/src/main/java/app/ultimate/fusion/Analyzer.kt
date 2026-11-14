package app.ultimate.fusion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

data class HeartSummary(val average: Quantity, val peak: Quantity, val samples: Int)
data class Elevation(val gain: Quantity, val loss: Quantity)

/** The watch's own step count against the phone's, over the time both were counting: two independent counts of the same steps. */
data class StepCheck(val watch: Quantity, val phone: Quantity, val agree: Boolean, val text: String)

/** Everything the analysis found, each number with how it was obtained. Pure data: the screen, the questions and the exports all read this. */
class MovementReport(
    val algo: Int, val durationS: Double, val sources: List<String>, val clockNote: String,
    val fusion: FusionResult, val path: FusedPath?, val pdr: Pdr.Result?,
    val speeds: List<SpeedPoint>, val averageSpeed: Quantity?, val topSpeed: Quantity?,
    val steps: Quantity?, val cadence: Quantity?,
    val gait: List<GaitSegment>, val stops: List<Stop>, val turns: List<Turn>, val laps: List<Lap>, val lapConsistency: Double?,
    val failures: List<Finding>, val heart: HeartSummary?, val elevation: Elevation?, val calibration: List<String>,
    val stepCheck: StepCheck? = null,
) {
    val distance: Quantity? get() = fusion.distance
}

/**
 * The one entry point: a [Trace] in, a [MovementReport] out. Deterministic: the same trace, calibration and code version always produce the
 * same report, so a report can be regenerated whenever the algorithm improves ([ALGO] tells a stored report from a stale one).
 */
object MovementAnalyzer {
    const val ALGO = 1

    fun analyze(trace: Trace, calibrator: Calibrator? = null, device: String = "", motion: String = "any", environment: String = "any"): MovementReport {
        val dur = trace.durationS
        val notes = ArrayList<String>()
        val stride = calibrator?.strideModel(device, motion, environment) ?: StrideModel()
        calibrator?.correction(Calibrator.key(device, motion, "stride", environment), StrideModel.DEFAULT_REL_SIGMA)?.let { notes += "Stride: ${it.note}" }
        val camera = calibrator?.cameraScale(device, environment)
        camera?.let { notes += "Camera scale: ${it.note}" }

        val pdr = if (trace.hasImu) Pdr.run(trace.imu, stride) else null
        val sources = ArrayList<DistanceSource>()
        if (trace.hasPoses) sources += PoseSource(trace, camera?.factor ?: 1.0, camera?.relSigma ?: 0.02)
        GpsSource.of(trace)?.let { sources += it }
        if (pdr != null) PdrSource(pdr, dur).takeIf { it.usable }?.let { sources += it }
        val fusion = Fusion.run(sources, dur)
        // The path is drawn only by sensors that were trusted: a sensor ruled out for disagreeing must not draw the map either.
        val path = FusedPath.build(sources.filter { it.name !in fusion.excluded }, dur)

        val speeds = path?.let { Events.speeds(it) }.orEmpty()
        val stops = Events.stops(speeds)
        val turns = path?.let { Events.turns(it, speeds) }.orEmpty()
        val pdrOnly = sources.none { it.name != "inertial steps" }
        val gate = if (pdrOnly && path != null) max(12.0, 0.04 * path.length) else 12.0
        val laps = path?.let { Events.laps(it, gate, max(30.0, 2.5 * gate)) }.orEmpty()
        val gait = pdr?.let { Events.gaitSegments(it.windows) }.orEmpty()

        val observedS = dur - fusion.unobservedS
        val avg = fusion.distance?.takeIf { observedS > 5 }?.let { Quantity(it.value / observedS, it.sigma / observedS, "m/s", Basis.Derived, it.source, "over the %.0f s that were observed".format(observedS)) }
        val top = speeds.maxByOrNull { it.metresPerSecond }?.let { Quantity(it.metresPerSecond, max(0.3, 0.1 * it.metresPerSecond), "m/s", Basis.Derived, "fused path", "5 s window around %.0f s".format(it.t)) }
        val steps = pdr?.steps?.size?.takeIf { it > 0 }?.let { Quantity(it.toDouble(), max(1.0, it * 0.03), "steps", Basis.Derived, "accelerometer peaks", "counted in steady bouts only") }
        val cadence = pdr?.steps?.takeIf { it.size > 8 }?.let { s -> Quantity((s.size - 1) / (s.last().t - s.first().t).coerceAtLeast(1.0) * 60, 4.0, "steps/min", Basis.Derived, "accelerometer peaks") }

        val failures = ArrayList<Finding>()
        sources.filterIsInstance<PoseSource>().firstOrNull()?.let { p ->
            for (b in p.blind) failures += Finding("tracking", b, "Camera tracking was lost for %.0f s (%.0f–%.0f s). Distance in that stretch comes from the other sensors, or is not counted.".format(b.length, b.from, b.to), Basis.Observed, 0.95)
            if (p.jumps > 0) failures += Finding("tracking", Span(0.0, dur), "${p.jumps} sudden position jump(s) in the camera track were ignored (a re-found place, not walking).", Basis.Derived, 0.8)
        }
        sources.firstOrNull { it.name == "GPS" }?.let { g ->
            for (b in g.blind) failures += Finding("gps", b, "No usable GPS fix for %.0f s (%.0f–%.0f s).".format(b.length, b.from, b.to), Basis.Observed, 0.9)
            val worst = trace.fixes.count { it.accuracyM > GpsSource.MAX_ACC_M }
            if (worst > 0) failures += Finding("gps", Span(0.0, dur), "$worst GPS fix(es) were worse than ${GpsSource.MAX_ACC_M.toInt()} m and were not used.", Basis.Observed, 0.9)
        }
        if (trace.hasImu && !trace.imu.hasGyro) failures += Finding("inertial", Span(0.0, dur), "This recording has no gyroscope, so the direction of steps (and the shape of the path from steps) is unavailable; their count and length are unaffected.", Basis.Observed, 0.9)
        if (pdr != null && pdr.gyroUsed && abs(pdr.gyroBiasDegPerS) > 1.5) failures += Finding("inertial", Span(0.0, dur), "The gyroscope looked biased by %.1f°/s while the phone was still (corrected), which is large; turning angles from it may be unreliable.".format(pdr.gyroBiasDegPerS), Basis.Derived, 0.6)

        val heart = trace.heart.takeIf { it.size >= 5 }?.let { h ->
            val v = h.map { it.v }.filter { it in 30.0..230.0 }
            if (v.size < 5) null else HeartSummary(Quantity(v.average(), 2.0, "bpm", Basis.Observed, "heart-rate samples"), Quantity(v.max(), 3.0, "bpm", Basis.Observed, "heart-rate samples"), v.size)
        }
        val elevation = elevationOf(trace)
        val stepCheck = stepCheckOf(trace, pdr)
        if (stepCheck != null && !stepCheck.agree) failures += Finding("steps", Span(trace.watchSteps.first().t, trace.watchSteps.last().t), stepCheck.text, Basis.Observed, 0.8)
        return MovementReport(ALGO, dur, trace.sources(), trace.poseClock, fusion, path, pdr, speeds, avg, top, steps, cadence, gait, stops, turns, laps, Events.lapConsistency(laps), failures, heart, elevation, notes, stepCheck)
    }

    /**
     * Compares the watch's step counter with the phone's step detection over the time the watch was reporting. They are separate
     * devices on separate parts of the body, so agreement is real evidence, and a large gap usually means one of them is double- or
     * under-counting. The watch's batches arrive up to half a minute late, so the comparison allows a few steps either way.
     */
    fun stepCheckOf(trace: Trace, pdr: Pdr.Result?): StepCheck? {
        val w = trace.watchSteps
        if (w.size < 2 || pdr == null) return null
        val from = w.first().t; val to = w.last().t
        if (to - from < 30) return null
        val watch = (w.last().v - w.first().v).coerceAtLeast(0.0)
        val phone = pdr.steps.count { it.t > from && it.t <= to }.toDouble()
        val wq = Quantity(watch, max(3.0, 0.02 * watch), "steps", Basis.Observed, "watch step counter")
        val pq = Quantity(phone, max(3.0, 0.04 * phone), "steps", Basis.Derived, "phone accelerometer")
        val agree = wq.zAgainst(pq) <= 3.0
        val text = if (agree) "Your watch counted ${"%.0f".format(watch)} steps and the phone ${"%.0f".format(phone)} over the same time: they agree."
            else "Your watch counted ${"%.0f".format(watch)} steps but the phone ${"%.0f".format(phone)} over the same time." + when {
                phone > 1.6 * watch -> " The phone is probably counting a swing or a shake as an extra step."
                watch > 1.6 * phone -> " The phone missed steps: it may have been in a bag, or held still in a hand."
                else -> " One of them is miscounting; the watch, on your wrist all the time, is usually the steadier."
            }
        return StepCheck(wq, pq, agree, text)
    }

    /** Height gained and lost from air pressure, ignoring changes under 1.5 m (a barometer breathes with the weather and with a door opening). */
    fun elevationOf(trace: Trace): Elevation? {
        val p = trace.pressure
        if (p.size < 30) return null
        val smooth = Pdr.ma(DoubleArray(p.size) { p[it].v }, 15)
        val h = DoubleArray(p.size) { 44330.0 * (1 - (smooth[it] / smooth[0]).pow(0.1903)) }
        var ref = h[0]; var gain = 0.0; var loss = 0.0
        for (x in h) { if (x - ref >= 1.5) { gain += x - ref; ref = x } else if (ref - x >= 1.5) { loss += ref - x; ref = x } }
        return Elevation(Quantity(gain, 1.0 + 0.05 * gain, "m", Basis.Derived, "barometer"), Quantity(loss, 1.0 + 0.05 * loss, "m", Basis.Derived, "barometer"))
    }

    /**
     * What each sensor measured of a course whose true length is known, for teaching calibration: each only if it saw at least 85% of the
     * recording (a sensor that missed a third of the walk would teach a false bias). Distances here are uncorrected (scale 1).
     */
    fun calibrationObservations(trace: Trace): Map<String, Double> {
        val dur = trace.durationS
        val out = HashMap<String, Double>()
        val epochs = (0 until (dur / Fusion.EPOCH_S).toInt()).map { Span(it * Fusion.EPOCH_S, (it + 1) * Fusion.EPOCH_S) }
        if (epochs.isEmpty()) return out
        fun covered(s: DistanceSource) = epochs.count { s.length(it) != null }.toDouble() / epochs.size
        if (trace.hasImu) {
            val pdr = Pdr.run(trace.imu, StrideModel(1.0))
            PdrSource(pdr, dur).takeIf { it.usable && covered(it) >= 0.85 }?.let { s -> out["stride"] = pdr.steps.sumOf { it.length } }
        }
        if (trace.hasPoses) PoseSource(trace, 1.0).takeIf { it.usable && covered(it) >= 0.85 }?.let { out["camera scale"] = it.fullLength() }
        return out
    }
}
