package app.ultimate.echo

import app.ultimate.core.Senses
import app.ultimate.core.Signal
import app.ultimate.rules.Event
import app.ultimate.rules.Rule
import app.ultimate.rules.RuleMatcher
import app.ultimate.rules.Trigger
import app.ultimate.rules.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EchoTest {
    private val win = EchoMath.window()

    /** The tone as the mic hears it, plus an echo shifted by [shift] Hz (positive: something approaching). */
    private fun frame(shift: Double, echo: Double, start: Int, noise: Double = 0.0005): EchoMath.Bands {
        val r = java.util.Random(start.toLong())
        val x = FloatArray(EchoMath.FRAME) { i ->
            val t = (start + i).toDouble() / EchoMath.RATE
            ((0.3 * sin(2 * PI * EchoMath.PILOT * t) + echo * sin(2 * PI * (EchoMath.PILOT + shift) * t) + noise * r.nextGaussian()) * win[i]).toFloat()
        }
        return EchoMath.bands(x)
    }

    @Test fun stillRoomReadsStillAndHearsTheTone() {
        val d = EchoDetector()
        var last: Pair<EchoReading, String?>? = null
        repeat(40) { last = d.feed(frame(0.0, 0.0, it * EchoMath.FRAME), it * 43L) }
        assertTrue(last!!.first.heard)
        assertEquals(EchoReading.Direction.Still, last!!.first.direction)
        assertNull(last!!.second)
    }

    @Test fun anEchoAboveTheToneIsSomethingApproachingAndBelowIsLeaving() {
        val d = EchoDetector()
        repeat(30) { d.feed(frame(0.0, 0.0, it * EchoMath.FRAME), it * 43L) }
        val near = d.feed(frame(94.0, 0.03, 31 * EchoMath.FRAME), 2_000)
        assertEquals(EchoReading.Direction.Approach, near.first.direction)
        assertEquals("approach", near.second)
        // Too soon after the last report: sensed, but not reported again.
        assertNull(d.feed(frame(94.0, 0.03, 32 * EchoMath.FRAME), 2_100).second)
        val away = d.feed(frame(-117.0, 0.03, 40 * EchoMath.FRAME), 4_000)
        assertEquals(EchoReading.Direction.Recede, away.first.direction)
        assertEquals("recede", away.second)
    }

    @Test fun silenceIsNotHeard() {
        val b = EchoMath.bands(FloatArray(EchoMath.FRAME) { (java.util.Random(it.toLong()).nextGaussian() * 0.001).toFloat() })
        assertFalse(EchoDetector().feed(b, 0).first.heard)
    }

    @Test fun theToneLoopsWithoutAClick() {
        val t = EchoMath.tone()
        // The sample after the last one is the first again: an exact whole number of cycles.
        assertEquals(0, t.first().toInt())
        assertTrue(kotlin.math.abs(t.last().toInt() - (sin(2 * PI * EchoMath.PILOT * (t.size - 1) / EchoMath.RATE) * 0.35 * Short.MAX_VALUE).toInt()) <= 1)
        assertEquals(0.0, (EchoMath.PILOT * t.size / EchoMath.RATE) % 1.0, 1e-9)
    }

    private fun rule(key: String) = Rule("r", "r", true, Trigger(TriggerType.Sensed, contains = key), false, emptyList())

    @Test fun rulesMatchSignalsBySourceAndKind() {
        val approach = Event.Sensed("echo:approach", "came closer")
        assertTrue(RuleMatcher.starts(rule("echo:approach"), approach))
        assertFalse(RuleMatcher.starts(rule("echo:recede"), approach))
        assertTrue(RuleMatcher.starts(rule("echo:"), approach))
        assertTrue(RuleMatcher.starts(rule(""), approach))
        assertFalse(RuleMatcher.starts(rule("vision:"), approach))
    }

    @Test fun sessionsStopAndLeaveTheListEvenWhenStoppingFails() {
        val s = Senses(); var stopped = false
        s.begin("a", "echo", "listening", true) { stopped = true }
        s.begin("b", "x", "broken", true) { error("boom") }
        assertEquals(2, s.sessions.value.size)
        s.stopAll()
        assertTrue(stopped); assertTrue(s.sessions.value.isEmpty())
        s.publish(Signal("echo", "approach", "x"))
        assertEquals("echo:approach", s.recent.value.single().key)
    }

    @Test fun plainSentencesBecomeSenseRules() {
        fun trig(s: String) = (app.ultimate.rules.RuleParser.parse(s, { null }, { "id" }) as app.ultimate.rules.RuleParser.Result.Ok).rule.trigger
        assertEquals("echo:approach", trig("When something comes closer to the phone, turn on the flashlight").contains)
        assertEquals("echo:recede", trig("when I walk away from the phone, turn the flashlight off").contains)
        assertEquals(TriggerType.Sensed, trig("when I wave at the phone, vibrate").type)
        assertEquals("echo:push", trig("When I push toward the phone, turn on the flashlight").contains)
        assertEquals("echo:hover", trig("when I hover over the phone, vibrate").contains)
        assertEquals("echo:pull", trig("when I pull my hand away from the phone, turn the flashlight off").contains)
    }

    /** Talking near the phone (S10): a hiss across the band must not read as something approaching in motion mode either. */
    @Test fun aHissIsNotMotion() {
        val d = EchoDetector()
        repeat(30) { d.feed(frame(0.0, 0.0, it * EchoMath.FRAME), it * 43L) }
        val r = java.util.Random(4)
        var events = 0
        for (k in 30 until 60) {
            val tones = List(160) { Triple(16_000.0 + 5_000.0 * r.nextDouble(), r.nextDouble() * 6.283, 0.01) }
            val x = FloatArray(EchoMath.FRAME) { i ->
                val t = (k * EchoMath.FRAME + i).toDouble() / EchoMath.RATE
                ((0.3 * sin(2 * PI * EchoMath.PILOT * t) + tones.sumOf { (f, p, a) -> a * sin(2 * PI * f * t + p) }) * win[i]).toFloat()
            }
            if (d.feed(EchoMath.bands(x), k * 43L).second != null) events++
        }
        assertEquals(0, events)
    }
}
