package app.ultimate.fusion

/** Something worth telling the rest of the app about while a movement recording is running: "source:kind" is always "movement:<kind>". */
data class MovementSignal(val kind: String, val label: String, val data: Map<String, String> = emptyMap())

/**
 * Turns two successive live reports into the events between them, so automations, the orb and anything else on the senses bus can react to
 * movement without knowing how it is measured ("when I finish a lap, ...", "when I start running, ..."). Pure and deterministic: an event
 * is raised once, when the newer report first shows it.
 */
object MovementSignals {
    fun between(before: MovementReport?, now: MovementReport): List<MovementSignal> {
        val out = ArrayList<MovementSignal>()
        val lapsBefore = before?.laps?.size ?: 0
        for (l in now.laps.drop(lapsBefore)) out += MovementSignal("lap", "Lap ${l.index}: ${"%.0f".format(l.metres)} m in ${"%d:%02d".format(l.seconds.toInt() / 60, l.seconds.toInt() % 60)}", mapOf("lap" to l.index.toString(), "metres" to "%.0f".format(l.metres), "seconds" to "%.0f".format(l.seconds)))
        val g0 = before?.gait?.lastOrNull()?.gait; val g1 = now.gait.lastOrNull()?.gait
        if (g1 != null && g1 != g0) when (g1) {
            Gait.Running -> out += MovementSignal("running", "Started running")
            Gait.Walking -> if (g0 == Gait.Running) out += MovementSignal("walking", "Slowed to a walk")
            Gait.Still -> if (g0 == Gait.Running || g0 == Gait.Walking) out += MovementSignal("stopped", "Stopped moving")
            Gait.Other -> Unit
        }
        val km0 = ((before?.distance?.value ?: 0.0) / 1000).toInt(); val km1 = ((now.distance?.value ?: 0.0) / 1000).toInt()
        if (km1 > km0) out += MovementSignal("kilometre", "$km1 km", mapOf("km" to km1.toString()))
        if (now.fusion.contradictions.isNotEmpty() && before?.fusion?.contradictions.isNullOrEmpty()) out += MovementSignal("disagree", now.fusion.contradictions.first().let { it.outlier?.let { o -> "$o disagrees with the other sensors" } ?: "${it.a} and ${it.b} disagree" })
        return out
    }
}
