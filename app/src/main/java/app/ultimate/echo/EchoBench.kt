package app.ultimate.echo

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A repeatable test of Echo on this phone, so changes can be compared with numbers rather than impressions:
 * - noise: how often a still room is mistaken for a target (false detections);
 * - accuracy: you hold a hand at 10, 20 and 30 cm, and Echo's readings are compared with that;
 * - latency: how long each frame takes to process, on top of the 42.7 ms each measurement takes;
 * - battery: the current drawn while sensing against idle, where the phone reports it.
 * The hand distances are only as exact as your hand, so read the error as "within a couple of centimetres", not better.
 */
class EchoBench(private val engine: SonarEngine) {
    sealed interface Step {
        data object Idle : Step
        data class Prompt(val text: String, val secondsLeft: Int) : Step
        data class Done(val report: BenchReport) : Step
        data class Failed(val why: String) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step

    suspend fun run(model: String) {
        val idle = mutableListOf<Double>()
        for (s in 4 downTo 1) { _step.value = Step.Prompt("Measuring the battery with Echo off. Leave the phone still.", s); engine.currentMa()?.let(idle::add); delay(1000) }
        engine.start()
        val sensing = mutableListOf<Double>()
        val frames = mutableListOf<SonarFrame>()
        suspend fun collect(text: String, seconds: Int): List<SonarFrame> {
            val got = mutableListOf<SonarFrame>()
            val end = System.currentTimeMillis() + seconds * 1000L
            var shownLeft = -1
            while (System.currentTimeMillis() < end) {
                (engine.state.value as? SonarState.Failed)?.let { _step.value = Step.Failed(it.why); return emptyList() }
                (engine.state.value as? SonarState.Running)?.frame?.let { if (got.lastOrNull() !== it) got += it }
                val left = ((end - System.currentTimeMillis()) / 1000).toInt() + 1
                if (left != shownLeft) { shownLeft = left; _step.value = Step.Prompt(text, left); engine.currentMa()?.let(sensing::add) }
                delay(40)
            }
            frames += got
            return got
        }
        collect("Learning the room. Keep the phone flat and your hands away.", 3)
        val still = collect("Stay still: counting false alarms.", 5)
        if (_step.value is Step.Failed) { engine.stop(); return }
        val holds = listOf(10, 20, 30).map { cm ->
            collect("Get ready to hold your hand $cm cm above the top of the phone.", 2)
            cm to collect("Hold your hand flat about $cm cm above the top edge, and move it a little.", 4)
        }
        engine.stop()
        if (_step.value is Step.Failed) return
        _step.value = Step.Done(BenchMath.report(model, still, holds, frames.map { it.processMs }, idle, sensing, frames.firstOrNull()?.stereo == true))
    }

    fun reset() { _step.value = Step.Idle }
}

data class BenchReport(val text: String, val falseRate: Double, val rows: List<BenchMath.Row>)

object BenchMath {
    data class Row(val heldCm: Int, val medianCm: Double?, val spreadCm: Double?, val detectRate: Double)

    fun median(x: List<Double>): Double? = if (x.isEmpty()) null else x.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** Half the range between the 25th and 75th percentiles: robust to the odd wild reading. */
    fun spread(x: List<Double>): Double? = if (x.size < 4) null else x.sorted().let { s -> (s[s.size * 3 / 4] - s[s.size / 4]) / 2 }

    fun row(heldCm: Int, frames: List<SonarFrame>): Row {
        val r = frames.filter { !it.phoneMoving }.map { it.fix.rangeM * 100 }.filter { !it.isNaN() }
        return Row(heldCm, median(r), spread(r), if (frames.isEmpty()) 0.0 else r.size.toDouble() / frames.size)
    }

    fun report(model: String, still: List<SonarFrame>, holds: List<Pair<Int, List<SonarFrame>>>, procMs: List<Float>, idleMa: List<Double>, sensingMa: List<Double>, stereo: Boolean): BenchReport {
        val falseRate = if (still.isEmpty()) 0.0 else still.count { !it.fix.rangeM.isNaN() }.toDouble() / still.size
        val rows = holds.map { (cm, f) -> row(cm, f) }
        val text = buildString {
            appendLine("Echo bench · $model · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}")
            appendLine("Signal: chirp 17-20 kHz, 10 ms every 42.7 ms. Microphones: ${if (stereo) "two distinct" else "one"}.")
            appendLine("Direct-path link: ${median(still.map { it.fix.linkSnr })?.let { "%.0f× above the noise".format(it) } ?: "not measured"}")
            appendLine("False detections, still room: %.0f%% of frames".format(falseRate * 100))
            rows.forEach { r ->
                appendLine("Hand at ${r.heldCm} cm: " + (r.medianCm?.let { "read %.1f cm (error %+.1f), spread ±%s, detected in %.0f%% of frames".format(it, it - r.heldCm, r.spreadCm?.let { s -> "%.1f cm".format(s) } ?: "?", r.detectRate * 100) } ?: "not detected"))
            }
            appendLine("Processing: median %s ms a frame, slowest %s ms (each measurement also takes 42.7 ms of sound)".format(median(procMs.map { it.toDouble() })?.let { "%.1f".format(it) } ?: "?", procMs.maxOrNull()?.let { "%.1f".format(it) } ?: "?"))
            val i = median(idleMa); val s = median(sensingMa)
            appendLine("Battery: " + if (i != null && s != null) "%.0f mA idle, %.0f mA sensing (%+.0f mA; differences under ~30 mA are within the phone's own fluctuation)".format(i, s, s - i) else "this phone doesn't report its current")
        }
        return BenchReport(text, falseRate, rows)
    }
}
