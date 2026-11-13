package app.ultimate.fusion


/** A reply to a question about a recording: the words, and exactly which measurements they came from. */
class Answer(val text: String, val measurements: List<Quantity>, val basis: Basis, val understood: Boolean = true)

/**
 * Answers questions about a recording from its report, with no language model in the loop: the numbers come from the measurements and the
 * sentences are fixed templates around them, so the same question about the same recording always gets the same answer and no figure
 * is ever invented. Each answer names its sensors and says what was observed, what was derived by calculation, and what is only a
 * judgement. A language model, if the user has one, can be given [factsBlock] to phrase things more freely; that text is then a model's
 * explanation of these facts, never a source of them.
 */
object MotionQA {
    fun answer(question: String, r: MovementReport): Answer {
        val q = question.lowercase()
        fun has(vararg w: String) = w.any { q.contains(it) }
        return when {
            has("disagree", "which sensor", "wrong", "outlier", "contradict", "inconsisten", "trust") -> disagreement(r)
            has("tracking fail", "lost track", "where did tracking", "tracking lost", "fail", "blind", "gap") -> failures(r)
            has("lap") -> laps(r)
            has("turn") -> turns(r)
            has("step", "cadence") -> steps(r)
            has("stop", "paus", "rest") -> stops(r)
            has("heart", "bpm", "pulse") -> heart(r)
            has("climb", "elevation", "uphill", "altitude", "stairs", "floor") -> elevation(r)
            has("run", "jog", "walk", "gait") && !has("how far") -> gait(r)
            has("fast", "speed", "pace", "how quick") -> speed(r)
            has("accura", "how sure", "confiden", "uncertain", "reliable", "error") -> accuracy(r)
            has("how far", "distance", "travel", "long was", "how many metres", "how many meters", "how many km", "how many miles") -> distance(r)
            else -> Answer("I can answer questions about distance, speed, laps, turns, steps, stops, gait, heart rate, climbing, which sensor disagreed, where tracking failed and how accurate this is. " +
                "I only answer from the measurements, so I do not guess.", emptyList(), Basis.Derived, understood = false)
        }
    }

    private fun m(x: Double) = if (x >= 1000) "%.2f km".format(x / 1000) else "%.0f m".format(x)

    private fun distance(r: MovementReport): Answer {
        val d = r.distance ?: return Answer("None of the sensors in this recording saw enough of the movement to give a distance.", emptyList(), Basis.Derived)
        val parts = r.fusion.sources.joinToString("; ") { s -> "${s.name} ${m(s.total.value)} (saw ${(s.coverage * 100).toInt()}% of the time" + (if (!s.trusted) ", left out: it disagreed" else "") + ")" }
        val unobs = if (r.fusion.unobservedS > 1) " ${"%.0f".format(r.fusion.unobservedS)} seconds were not seen by any trusted sensor and are not counted, so the real distance is at least this." else ""
        val agree = if (r.fusion.contradictions.isEmpty()) "The sensors agree within their uncertainty." else "The sensors did NOT all agree (ask which sensor disagreed)."
        return Answer("DERIVED: you travelled ${m(d.value)}, give or take ${m(2 * d.sigma)} (95% band). $agree Per sensor: $parts.$unobs", listOf(d) + r.fusion.sources.map { it.total }, Basis.Derived)
    }

    private fun disagreement(r: MovementReport): Answer {
        if (r.fusion.contradictions.isEmpty()) {
            val n = r.fusion.sources.size
            return Answer(if (n < 2) "Only one sensor saw this movement, so there is nothing to compare it with." else "No sensor disagreed beyond its own uncertainty: ${r.fusion.sources.joinToString { it.name }} all fit together.", r.fusion.sources.map { it.total }, Basis.Derived)
        }
        return Answer(r.fusion.contradictions.joinToString(" ") { it.text }, r.fusion.sources.map { it.total }, Basis.Derived)
    }

    private fun failures(r: MovementReport): Answer {
        if (r.failures.isEmpty()) return Answer("Nothing failed: no lost tracking, no missing GPS, no unusable stretch was detected.", emptyList(), Basis.Observed)
        return Answer(r.failures.joinToString(" ") { it.text }, emptyList(), Basis.Observed)
    }

    private fun laps(r: MovementReport): Answer {
        if (r.laps.isEmpty()) return Answer("No lap was counted: the path never came back to where it started after going away from it.", emptyList(), Basis.Derived)
        val spread = r.lapConsistency?.let { " Lap lengths varied by ${"%.1f".format(it * 100)}%." } ?: ""
        val laps = r.laps.joinToString("; ") { "lap ${it.index}: ${"%.0f".format(it.metres)} m in ${"%d:%02d".format(it.seconds.toInt() / 60, it.seconds.toInt() % 60)}" }
        return Answer("DERIVED: ${r.laps.size} lap(s). $laps.$spread A lap is counted each time the path returns near where it began.", r.laps.map { Quantity(it.metres, 0.0, "m", Basis.Derived, "fused path") }, Basis.Derived)
    }

    private fun turns(r: MovementReport): Answer {
        val u = r.turns.count { it.uTurn }
        return Answer("DERIVED: ${r.turns.size} turn(s) of 50° or more" + (if (u > 0) ", $u of them U-turns" else "") + ". Left ${r.turns.count { it.left }}, right ${r.turns.count { !it.left }}.", emptyList(), Basis.Derived)
    }

    private fun steps(r: MovementReport): Answer {
        val s = r.steps ?: return Answer("No steady stepping was found in the inertial sensors.", emptyList(), Basis.Derived)
        val c = r.cadence?.let { " Cadence ${"%.0f".format(it.value)} steps a minute." } ?: ""
        return Answer("DERIVED from accelerometer peaks: ${"%.0f".format(s.value)} steps (±${"%.0f".format(2 * s.sigma)}).$c Steps are counted only in steady bouts, so a fidgety stretch is not counted.", listOfNotNull(s, r.cadence), Basis.Derived)
    }

    private fun stops(r: MovementReport): Answer {
        if (r.stops.isEmpty()) return Answer("No stop of 5 seconds or more was found.", emptyList(), Basis.Derived)
        return Answer("DERIVED: ${r.stops.size} stop(s): " + r.stops.joinToString(", ") { "%.0f s at %.0f s".format(it.span.length, it.span.from) } + ".", emptyList(), Basis.Derived)
    }

    private fun heart(r: MovementReport): Answer {
        val h = r.heart ?: return Answer("This recording has no heart-rate samples.", emptyList(), Basis.Observed)
        return Answer("OBSERVED: average ${"%.0f".format(h.average.value)} bpm, peak ${"%.0f".format(h.peak.value)} bpm over ${h.samples} samples.", listOf(h.average, h.peak), Basis.Observed)
    }

    private fun elevation(r: MovementReport): Answer {
        val e = r.elevation ?: return Answer("This recording has no barometer readings, so climbing cannot be measured.", emptyList(), Basis.Observed)
        return Answer("DERIVED from air pressure: climbed ${"%.0f".format(e.gain.value)} m and descended ${"%.0f".format(e.loss.value)} m (changes under 1.5 m ignored).", listOf(e.gain, e.loss), Basis.Derived)
    }

    private fun gait(r: MovementReport): Answer {
        if (r.gait.isEmpty()) return Answer("The inertial sensors did not record long enough to tell a gait.", emptyList(), Basis.Inferred)
        val by = r.gait.groupBy { it.gait }.mapValues { (_, v) -> v.sumOf { it.span.length } }
        return Answer("INFERRED from step rhythm: " + by.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key.label} ${"%.0f".format(it.value)} s" } +
            ". This is a rule-of-thumb judgement (about 2.5 steps a second or more is running), not a measurement.", emptyList(), Basis.Inferred)
    }

    private fun speed(r: MovementReport): Answer {
        val a = r.averageSpeed ?: return Answer("There is not enough observed movement to give a speed.", emptyList(), Basis.Derived)
        val pace = if (a.value > 0.3) { val s = 1000 / a.value; "%d:%02d per km".format(s.toInt() / 60, s.toInt() % 60) } else "—"
        val top = r.topSpeed?.let { " Fastest stretch ${"%.1f".format(it.value)} m/s (${"%.1f".format(it.value * 3.6)} km/h)." } ?: ""
        return Answer("DERIVED: average ${"%.2f".format(a.value)} m/s (${"%.1f".format(a.value * 3.6)} km/h, $pace) over the time it was observed.$top", listOfNotNull(a, r.topSpeed), Basis.Derived)
    }

    private fun accuracy(r: MovementReport): Answer {
        val d = r.distance ?: return Answer("There is no distance to rate.", emptyList(), Basis.Derived)
        val rel = if (d.value > 0) 100 * 2 * d.sigma / d.value else 0.0
        val cal = if (r.calibration.isEmpty()) "No calibration is being used." else r.calibration.joinToString(" ")
        return Answer("The distance is ${m(d.value)} ± ${"%.1f".format(rel)}% (95% band). That band comes from each sensor's modelled error, combined; it is a model, not a verdict. $cal " +
            (if (r.fusion.contradictions.isNotEmpty()) "The sensors disagreed, which widened it. " else "") + "The only way to know a sensor's real error is to walk a course whose length you know and enter it; calibration learns from that and nothing else.",
            listOf(d), Basis.Derived)
    }

    /** The recording's facts as plain labelled lines, for giving to a language model that will explain them. The model may phrase these; it must not change the numbers. */
    fun factsBlock(r: MovementReport): String = buildString {
        appendLine("MEASURED FACTS (do not change any number; say when something is only inferred):")
        r.distance?.let { appendLine("distance: ${it.text()} [${it.basis.label}, ${it.source}]") }
        r.fusion.sources.forEach { appendLine("sensor ${it.name}: ${it.total.text()} saw ${(it.coverage * 100).toInt()}% ${if (it.trusted) "" else "[DISAGREED]"}") }
        r.fusion.contradictions.forEach { appendLine("contradiction: ${it.text}") }
        r.averageSpeed?.let { appendLine("average speed: ${"%.2f".format(it.value)} m/s [DERIVED]") }
        r.steps?.let { appendLine("steps: ${"%.0f".format(it.value)} [DERIVED]") }
        appendLine("laps: ${r.laps.size} [DERIVED]; turns: ${r.turns.size} [DERIVED]; stops: ${r.stops.size} [DERIVED]")
        r.gait.forEach { appendLine("gait ${it.gait.label} from ${"%.0f".format(it.span.from)} s to ${"%.0f".format(it.span.to)} s [INFERRED]") }
        r.failures.forEach { appendLine("problem: ${it.text}") }
        r.heart?.let { appendLine("heart rate: avg ${"%.0f".format(it.average.value)}, peak ${"%.0f".format(it.peak.value)} bpm [OBSERVED]") }
    }
}
