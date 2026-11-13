package app.ultimate.fusion

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What a distance can be checked against. Only things a person measured or entered, or that an outdoor GPS track supports over a long
 * distance, are allowed to teach a correction. An estimate made by a model, or by any sensor of this same recording that is itself the
 * thing being corrected, is not truth and is refused: learning from it would just teach the system to agree with itself.
 */
enum class TruthKind(val label: String, val relSigma: Double) {
    Surveyed("a course measured with a tape, wheel or survey", 0.005),
    KnownCourse("a known course whose length you entered (a 400 m track lap, a marked trail)", 0.015),
    OpenSkyGps("GPS in the open over a long walk (weak: GPS error does not average out)", 0.04),
}

/** A distance that is really known. [basis] must be [Basis.Observed] (measured or entered by a person); anything inferred is rejected. */
data class Truth(val metres: Double, val kind: TruthKind, val basis: Basis = Basis.Observed)

/** What learning from one observation did. */
sealed interface Learned {
    data class Accepted(val key: String, val ratio: Double, val n: Int) : Learned
    data class Rejected(val why: String) : Learned
}

/** The correction for one thing (a person's stride, the camera's scale, GPS bias) in one setting, and how sure it is. */
data class Correction(val factor: Double, val relSigma: Double, val n: Int, val active: Boolean, val note: String)

/**
 * Learns systematic errors, one setting at a time, from verified truth only.
 *
 * Setting = device model + motion (walk/run) + sensor (stride, camera scale) + environment (indoor/outdoor). Each observation is the ratio of
 * the true distance to what the sensor measured (1.0 would be perfect; 1.1 means it under-measured by 9%). They are combined with
 * a Bayesian average that starts from "no bias, probably within 15%", weights each ratio by how well its truth was known, and then
 * reports TWO uncertainties honestly: how sure we are of the average (which shrinks with more runs) and how much the ratio changes from run
 * to run (which does not), because correcting the bias does not make any one run perfect. A correction is applied only when at least two
 * observations agree it is real; before that the factor stays at 1 and the uncertainty is not reduced, so the system never claims
 * accuracy it has not earned.
 */
class Calibrator(private val obs: MutableMap<String, MutableList<Pair<Double, Double>>> = HashMap()) {
    companion object {
        const val PRIOR_SIGMA = 0.15
        const val REPEATABILITY = 0.03
        const val MIN_RUNS = 2
        private const val KEEP = 60

        fun key(device: String, motion: String, sensor: String, environment: String = "any") = listOf(device.ifBlank { "?" }, motion, sensor, environment).joinToString("|")

        fun fromJson(text: String): Calibrator = runCatching {
            val o = JSONObject(text); val m = HashMap<String, MutableList<Pair<Double, Double>>>()
            for (k in o.keys()) { val a = o.getJSONArray(k); m[k] = MutableList(a.length()) { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } } }
            Calibrator(m)
        }.getOrElse { Calibrator() }
    }

    @Synchronized fun toJson(): String = JSONObject().also { o -> obs.forEach { (k, v) -> o.put(k, JSONArray().also { a -> v.forEach { a.put(JSONArray().put(it.first).put(it.second)) } }) } }.toString()

    /** Records that [measured] metres was seen where [truth] was real. */
    @Synchronized fun learn(key: String, measured: Double, truth: Truth): Learned {
        if (truth.basis == Basis.Inferred) return Learned.Rejected("An estimate is not ground truth: calibration only learns from distances that were measured or entered.")
        if (!truth.metres.isFinite() || truth.metres < 50.0) return Learned.Rejected("The known distance is too short (under 50 m) to tell a systematic error from noise.")
        if (!measured.isFinite() || measured < 20.0) return Learned.Rejected("The sensor measured too little to compare.")
        val ratio = truth.metres / measured
        if (ratio !in 0.4..2.5) return Learned.Rejected("The sensor and the truth differ by more than ×2.5: that is a failure to measure, not a calibration error.")
        val sigma = sqrt(truth.kind.relSigma * truth.kind.relSigma + REPEATABILITY * REPEATABILITY)
        val list = obs.getOrPut(key) { ArrayList() }
        list += ratio to sigma
        while (list.size > KEEP) list.removeAt(0)
        return Learned.Accepted(key, ratio, list.size)
    }

    @Synchronized fun correction(key: String, defaultRelSigma: Double): Correction {
        val list = obs[key].orEmpty()
        if (list.isEmpty()) return Correction(1.0, defaultRelSigma, 0, false, "not calibrated")
        val p0 = 1 / (PRIOR_SIGMA * PRIOR_SIGMA)
        var prec = p0; var num = p0 * 1.0
        for ((r, s) in list) { prec += 1 / (s * s); num += r / (s * s) }
        val mean = num / prec
        val sdMean = sqrt(1 / prec)
        val spread = if (list.size >= 3) { val m = list.map { it.first }.average(); sqrt(list.sumOf { (it.first - m) * (it.first - m) } / (list.size - 1)) } else 0.08
        val predictive = max(REPEATABILITY, sqrt(sdMean * sdMean + spread * spread)) // no sensor repeats better than its floor, however many runs agree
        val real = list.size >= MIN_RUNS && abs(mean - 1) >= 2 * sdMean
        return if (real) Correction(mean, predictive, list.size, true, "corrected ×%.3f from %d verified runs (run-to-run spread %.1f%%)".format(mean, list.size, spread * 100))
        else Correction(1.0, defaultRelSigma, list.size, false, "${list.size} verified run(s) so far, not enough to show a real bias; no correction applied")
    }

    fun strideModel(device: String, motion: String, environment: String = "any"): StrideModel {
        val c = correction(key(device, motion, "stride", environment), StrideModel.DEFAULT_REL_SIGMA)
        return StrideModel(c.factor, if (c.active) c.relSigma.coerceIn(0.03, 0.2) else c.relSigma)
    }
    fun cameraScale(device: String, environment: String = "any") = correction(key(device, "any", "camera scale", environment), 0.02)
    fun runs(key: String) = obs[key]?.size ?: 0
    fun keys(): List<String> = obs.keys.sorted()
}
