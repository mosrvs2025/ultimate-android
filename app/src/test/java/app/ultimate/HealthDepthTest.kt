package app.ultimate

import app.ultimate.health.HealthDay
import app.ultimate.health.HealthDepth
import app.ultimate.health.HealthDepth.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthDepthTest {
    private val today = 100L
    private fun days(range: LongRange, f: (Long) -> HealthDay) = range.map(f)

    @Test fun thisWeekAgainstLastSaysHowMuchAndWhetherItIsTheGoodWay() {
        val d = days(87L..100L) { n -> if (n >= 94) HealthDay(n, steps = 9000, sleepMin = 380, restingHr = 62) else HealthDay(n, steps = 7000, sleepMin = 440, restingHr = 58) }
        val w = HealthDepth.weekly(d, today).associateBy { it.metric }
        assertEquals(9000.0, w.getValue(Metric.Steps).now, 0.1); assertEquals("+29%", HealthDepth.pctText(w.getValue(Metric.Steps))); assertEquals(true, w.getValue(Metric.Steps).good)
        assertEquals("less sleep is the bad way", false, w.getValue(Metric.Sleep).good)
        assertEquals("a higher resting heart rate is the bad way", false, w.getValue(Metric.RestingHr).good)
        assertNull("no HRV recorded, so none is invented", w[Metric.Hrv])
        assertEquals(14, w.getValue(Metric.Steps).daily.size)
    }

    @Test fun smallChangesAreAboutTheSameAndTooLittleDataIsLeftOut() {
        val d = days(87L..100L) { n -> HealthDay(n, steps = if (n >= 94) 8100 else 8000) }
        val s = HealthDepth.weekly(d, today).single { it.metric == Metric.Steps }
        assertTrue(s.flat); assertEquals("about the same", HealthDepth.pctText(s)); assertNull(s.good)
        assertTrue(HealthDepth.weekly(listOf(HealthDay(100, steps = 9000), HealthDay(99, steps = 9000)), today).isEmpty())
    }

    @Test fun theStepGoalCountsDaysAndTheRunEndingToday() {
        val d = listOf(94L to 5000L, 95L to 9000L, 96L to 8100L, 97L to 8000L, 98L to 9500L, 99L to 8800L, 100L to 9000L).map { (n, s) -> HealthDay(n, steps = s) }
        val g = HealthDepth.stepGoal(d, today)
        assertEquals(6, g.hit); assertEquals(6, g.streak); assertEquals(7, g.days.size); assertEquals(false, g.days.first().second)
        // Today unfinished and short: the run is counted back from yesterday instead of being broken.
        val early = d.dropLast(1) + HealthDay(100, steps = 1200)
        assertEquals(5, HealthDepth.stepGoal(early, today).streak)
        assertNull(HealthDepth.stepGoal(emptyList(), today).days.first().second)
    }

    @Test fun sleepStagesAddUpAndOddDataIsKeptInRange() {
        val d = listOf(HealthDay(99, sleepMin = 420, deepMin = 70, remMin = 90), HealthDay(100, sleepMin = 300, deepMin = 400, remMin = 50), HealthDay(98))
        val n = HealthDepth.nights(d, today)
        assertEquals(2, n.size); assertEquals(260L, n[0].light); assertEquals(420L, n[0].total)
        assertTrue("stages can't exceed the night", n[1].total == 300L && n[1].light >= 0 && n[1].deep <= 300)
    }

    @Test fun worthDoingOnlySpeaksWhenTheNumbersSupportItAndStaysGentle() {
        val tired = days(87L..100L) { n -> HealthDay(n, steps = if (n >= 94) 4000 else 9000, sleepMin = if (n >= 94) 320 else 450, restingHr = if (n >= 94) 66 else 60) }
        val said = HealthDepth.worthDoing(tired, today)
        assertTrue(said.size in 1..4); assertTrue(said.any { it.contains("sleep") }); assertTrue(said.any { it.contains("Steps are down") }); assertTrue(said.any { it.contains("Resting heart rate is up") })
        assertTrue("never says what to take or that anything is wrong", said.none { it.contains("diagnos") || it.contains("medicat") })
        val fine = days(87L..100L) { n -> HealthDay(n, steps = 9000, sleepMin = 450, restingHr = 58) }
        val ok = HealthDepth.worthDoing(fine, today); assertTrue(ok.any { it.contains("in a row") || it.contains("Steady") }); assertFalse(ok.any { it.contains("down") })
        assertTrue(HealthDepth.worthDoing(emptyList(), today).isEmpty())
    }
}
