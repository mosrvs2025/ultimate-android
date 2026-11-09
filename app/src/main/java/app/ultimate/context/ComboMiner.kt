package app.ultimate.context

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** One thing that happened: a signal ("watch:flick_right", "echo:approach", "spatial:watch_near") or an action ("open:studio"). */
data class Ev(val at: Long, val key: String)

/**
 * "When [when] happen(s), [then] follows [medianLagMs] later, [confidence] of the time" — found by looking, not programmed.
 * [emergent] is true when two things together predict [then] much better than either alone: a combination that no single
 * sensor or feature contains.
 */
data class Combo(
    val when_: List<String>, val then: String, val support: Int, val confidence: Double, val lift: Double,
    val medianLagMs: Long, val days: Int, val emergent: Boolean, val alone: Double,
) {
    val score: Double get() = confidence * ln(lift.coerceAtLeast(1.0)) * sqrt(support.toDouble()) * (if (emergent) 1.3 else 1.0)
}

/**
 * Finds interactions nobody wrote down. Given a timeline of signals and actions, it looks for "X (and Y) shortly before Z"
 * that happens far more often than chance, many times, on more than one day. Chance is measured, not assumed: each Z's
 * ordinary rate sets what a coincidence would look like, and a combination of two only counts as emergent if it beats both of
 * its parts by [emergentMargin]. Plain statistics, no model: it can say exactly why it found something, and it stays silent
 * on data with nothing in it. Pure, so it is tested.
 */
class ComboMiner(
    private val windowMs: Long = 60_000,
    private val minSupport: Int = 5,
    private val minConfidence: Double = 0.6,
    private val minLift: Double = 4.0,
    private val minDays: Int = 2,
    private val emergentMargin: Double = 0.25,
    private val isOutcome: (String) -> Boolean = { it.startsWith("open:") },
) {
    private class Row(val trigger: Int, val ante: List<String>, val hits: Map<String, Long>, val day: Long)

    /** [events] in any order. Returns the discoveries, best first. */
    fun mine(events: List<Ev>, maxResults: Int = 10): List<Combo> {
        val ev = events.sortedBy { it.at }
        if (ev.size < 20) return emptyList()
        val span = (ev.last().at - ev.first().at).coerceAtLeast(windowMs)
        val countOf = ev.groupingBy { it.key }.eachCount()
        // How likely a window is to contain each outcome by chance alone (Poisson).
        fun chance(b: String) = (1 - exp(-(countOf.getValue(b).toDouble() * windowMs / span))).coerceIn(1e-6, 1.0)

        // For each event i: what came just before it (within the window), and the first time each outcome follows it.
        val rows = ArrayList<Row>(ev.size)
        var lo = 0
        for (i in ev.indices) {
            while (ev[i].at - ev[lo].at > windowMs) lo++
            val before = (lo until i).map { ev[it].key }.filter { it != ev[i].key }.distinct()
            val hits = HashMap<String, Long>()
            var j = i + 1
            while (j < ev.size && ev[j].at - ev[i].at <= windowMs) {
                val k = ev[j].key
                if (isOutcome(k) && k != ev[i].key && k !in hits) hits[k] = ev[j].at - ev[i].at
                j++
            }
            rows += Row(i, listOf(ev[i].key) + before, hits, ev[i].at / 86_400_000L)
        }

        class Acc { var n = 0; val hit = HashMap<String, MutableList<Long>>(); val hitDays = HashMap<String, MutableSet<Long>>() }
        val accs = HashMap<List<String>, Acc>()
        for (r in rows) {
            val trig = r.ante[0]
            val sets = ArrayList<List<String>>(); sets += listOf(trig)
            for (x in r.ante.drop(1)) sets += listOf(trig, x).sorted()
            for (s in sets) {
                val a = accs.getOrPut(s) { Acc() }
                a.n++
                for ((b, lag) in r.hits) { a.hit.getOrPut(b) { ArrayList() } += lag; a.hitDays.getOrPut(b) { HashSet() } += r.day }
            }
        }
        fun conf(s: List<String>, b: String): Double = accs[s]?.let { a -> (a.hit[b]?.size ?: 0).toDouble() / a.n } ?: 0.0

        val out = ArrayList<Combo>()
        for ((s, a) in accs) for ((b, lags) in a.hit) {
            val support = lags.size
            val days = a.hitDays.getValue(b).size
            val c = support.toDouble() / a.n
            val lift = c / chance(b)
            if (support < minSupport || c < minConfidence || lift < minLift || days < minDays) continue
            if (s.size == 1) { out += Combo(s, b, support, c, lift, lags.sorted()[lags.size / 2], days, false, c); continue }
            val alone = s.maxOf { conf(listOf(it), b) }
            if (c >= alone + emergentMargin) out += Combo(s, b, support, c, lift, lags.sorted()[lags.size / 2], days, true, alone)
        }
        // A single-antecedent rule that a stronger pair explains better is the same discovery told badly: drop it.
        val pairs = out.filter { it.emergent }
        val kept = out.filter { c -> c.emergent || pairs.none { p -> p.then == c.then && c.when_[0] in p.when_ && p.confidence > c.confidence + emergentMargin } }
        return kept.sortedByDescending { it.score }.take(maxResults)
    }
}
