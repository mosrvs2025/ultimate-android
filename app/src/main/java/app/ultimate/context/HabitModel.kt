package app.ultimate.context

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.pow

/**
 * What the phone's situation is, as a few coarse words (never coordinates, names or contents). Features are
 * categorical on purpose: "part=evening", "charging=yes", "headphones=no", "prev=terminal". Pure, so it is tested.
 */
data class Situation(val features: Map<String, String>) {
    companion object {
        fun part(hour: Int) = when (hour) { in 5..8 -> "early"; in 9..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night" }

        /** Builds a situation from plain facts. [prev] is the tool opened just before (null: none lately). */
        fun of(hour: Int, weekday: Int, charging: Boolean, headphones: Boolean, wifi: Boolean?, watch: String? = null, prev: String? = null, motion: String? = null) = Situation(buildMap {
            if (motion != null) put("motion", motion)
            put("part", part(hour))
            put("days", if (weekday == 1 || weekday == 7) "weekend" else "weekday")
            put("charging", if (charging) "yes" else "no")
            put("headphones", if (headphones) "yes" else "no")
            if (wifi != null) put("wifi", if (wifi) "yes" else "no")
            if (watch != null) put("watch", watch)
            put("prev", prev ?: "none")
        })
    }
}

/** One thing the model thinks you will want, with how sure it is and the plain reasons ("charging", "evening"). */
data class Prediction(val tool: String, val probability: Double, val because: List<String>)

/**
 * An explainable habit model: "in a situation like this, which tool do I open?" It is naive Bayes over counts, with older
 * observations fading (half-life [halfLifeDays]) so it follows your life rather than your history. No neural network is
 * needed: a handful of coarse facts and a few hundred opens is far too little data to train one, and counts can say
 * *why*. It stores only tool ids and coarse words, at most [cap] observations, and can be wiped by [clear].
 *
 * Calm by design: [suggest] stays silent (null) unless the evidence is real, the answer is clear and it clearly beats
 * the runner-up. [rank] always answers, for places that merely order things.
 */
class HabitModel(private val halfLifeDays: Double = 21.0, private val alpha: Double = 0.5, private val cap: Int = 1500) {
    private class Obs(val tool: String, val features: Map<String, String>, val at: Long)
    private val log = ArrayList<Obs>()
    private val vetoes = ArrayList<Obs>()

    /**
     * "Not now": you turned a suggestion down. For [VETO_DAYS] days that tool is not suggested again at the same part of the day
     * with the same charging state, and it counts against it everywhere a little (three in a month silence it until then).
     */
    fun veto(tool: String, s: Situation, at: Long) { vetoes += Obs(tool, s.features, at); if (vetoes.size > 200) vetoes.removeAt(0) }

    private fun vetoed(tool: String, s: Situation, now: Long): Boolean {
        val mine = vetoes.filter { it.tool == tool && now - it.at in 0..VETO_DAYS * 86_400_000L }
        if (vetoes.count { it.tool == tool && now - it.at in 0..30 * 86_400_000L } >= 3) return true
        return mine.any { v -> v.features["part"] == s.features["part"] && v.features["charging"] == s.features["charging"] }
    }

    val size: Int get() = log.size

    fun observe(tool: String, s: Situation, at: Long) {
        log += Obs(tool, s.features, at)
        if (log.size > cap) log.subList(0, log.size - cap).clear()
    }

    fun clear() { log.clear(); vetoes.clear() }

    private fun weight(at: Long, now: Long) = 0.5.pow(((now - at).coerceAtLeast(0)) / (halfLifeDays * 86_400_000.0))

    private class Fit(val tools: List<String>, val total: Map<String, Double>, val all: Double, val joint: Map<String, Double>, val values: Map<String, Int>)

    private fun fit(now: Long): Fit {
        val total = HashMap<String, Double>(); val joint = HashMap<String, Double>(); val vals = HashMap<String, HashSet<String>>()
        var all = 0.0
        for (o in log) {
            val w = weight(o.at, now)
            total[o.tool] = (total[o.tool] ?: 0.0) + w; all += w
            for ((f, v) in o.features) { joint["${o.tool}|$f=$v"] = (joint["${o.tool}|$f=$v"] ?: 0.0) + w; vals.getOrPut(f) { HashSet() } += v }
        }
        return Fit(total.keys.toList(), total, all, joint, vals.mapValues { maxOf(2, it.value.size) })
    }

    /** log P(feature=value | tool), smoothed. */
    private fun logLik(fit: Fit, tool: String, f: String, v: String) =
        ln(((fit.joint["$tool|$f=$v"] ?: 0.0) + alpha) / ((fit.total[tool] ?: 0.0) + alpha * (fit.values[f] ?: 2)))

    /** Every tool in [available] that has ever been opened, most likely first. Tools never seen are not guessed. */
    fun rank(s: Situation, now: Long, available: Collection<String>? = null): List<Prediction> {
        val fit = fit(now)
        val tools = fit.tools.filter { available == null || it in available }
        if (tools.isEmpty()) return emptyList()
        val scores = tools.associateWith { t ->
            ln(((fit.total[t] ?: 0.0) + alpha) / (fit.all + alpha * fit.tools.size)) + s.features.entries.sumOf { (f, v) -> logLik(fit, t, f, v) }
        }
        val top = scores.values.max()
        val z = scores.values.sumOf { exp(it - top) }
        return tools.sortedByDescending { scores.getValue(it) }.map { t ->
            val p = exp(scores.getValue(t) - top) / z
            Prediction(t, p, reasons(fit, t, s, tools))
        }
    }

    /** Features that make [tool] likelier than the others are the reasons; only clear ones (1.5x or more) are named. */
    private fun reasons(fit: Fit, tool: String, s: Situation, tools: List<String>): List<String> {
        val others = tools.filter { it != tool }
        return s.features.entries.mapNotNull { (f, v) ->
            val mine = logLik(fit, tool, f, v)
            val rest = if (others.isEmpty()) mine else others.map { logLik(fit, it, f, v) }.average()
            val lift = exp(mine - rest)
            if (lift >= 1.5 && !(f == "prev" && v == "none")) Triple(f, v, lift) else null
        }.sortedByDescending { it.third }.take(3).map { (f, v, _) -> if (f == "prev") "after $v" else if (f == "charging" || f == "headphones" || f == "wifi") (if (v == "yes") f else "not $f") else v }
    }

    /**
     * The one tool worth surfacing right now, or null. Needs [minEvidence] fading-weighted opens of that tool, a probability
     * of at least [minProbability], and a lead over the runner-up of [minLead] times.
     */
    fun suggest(s: Situation, now: Long, available: Collection<String>? = null, minEvidence: Double = 5.0, minProbability: Double = 0.45, minLead: Double = 1.5): Prediction? {
        val r = rank(s, now, available)
        val best = r.firstOrNull() ?: return null
        // Turned down here lately: stay quiet rather than offer the runner-up.
        if (vetoed(best.tool, s, now)) return null
        val evidence = log.filter { it.tool == best.tool }.sumOf { weight(it.at, now) }
        val lead = if (r.size > 1 && r[1].probability > 0) best.probability / r[1].probability else Double.MAX_VALUE
        return if (evidence >= minEvidence && best.probability >= minProbability && lead >= minLead) best else null
    }

    fun toJson(): String = JSONArray().also { a ->
        log.forEach { o -> a.put(JSONObject().put("t", o.tool).put("at", o.at).put("f", JSONObject(o.features as Map<*, *>))) }
        vetoes.forEach { o -> a.put(JSONObject().put("t", o.tool).put("at", o.at).put("v", true).put("f", JSONObject(o.features as Map<*, *>))) }
    }.toString()

    companion object {
        const val VETO_DAYS = 14L

        fun fromJson(json: String, halfLifeDays: Double = 21.0): HabitModel = HabitModel(halfLifeDays).also { m ->
            runCatching {
                val a = JSONArray(json)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i); val f = o.getJSONObject("f")
                    val s = Situation(f.keys().asSequence().associateWith { f.getString(it) })
                    if (o.optBoolean("v")) m.veto(o.getString("t"), s, o.getLong("at")) else m.observe(o.getString("t"), s, o.getLong("at"))
                }
            }
        }
    }
}
