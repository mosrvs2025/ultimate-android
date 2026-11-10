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
        assertTrue(said.size in 1..4); assertTrue(said.any { it.contains("sleep") }); assertTrue("low activity is said first and plainly: $said", said.first().startsWith("Your activity is low")); assertTrue(said.any { it.contains("Resting heart rate is up") })
        assertTrue("never says what to take or that anything is wrong", said.none { it.contains("diagnos") || it.contains("medicat") })
        val fine = days(87L..100L) { n -> HealthDay(n, steps = 9000, sleepMin = 450, restingHr = 58) }
        val ok = HealthDepth.worthDoing(fine, today); assertTrue(ok.any { it.contains("in a row") || it.contains("Steady") }); assertFalse(ok.any { it.contains("down") })
        assertTrue(HealthDepth.worthDoing(emptyList(), today).isEmpty())
    }
}

class FoodJournalTest {
    private fun e(day: Long, kcal: Int?, at: Long = day * 1000) = app.ultimate.health.FoodEntry("$day-$at", day, at, "Lunch", "Soup", kcal)

    @Test fun aDayAddsUpAndKeepsOrder() {
        val d = app.ultimate.health.FoodJournal.day(listOf(e(5, 300, 20), e(5, 450, 10), e(5, null, 30), e(4, 999)), mapOf(5L to 3), 5)
        assertEquals(3, d.entries.size); assertEquals(10L, d.entries.first().at); assertEquals(750, d.kcal); assertEquals(3, d.glasses); assertTrue(d.logged)
        assertNull("no calories given means no total, not zero", app.ultimate.health.FoodJournal.day(listOf(e(5, null)), emptyMap(), 5).kcal)
        assertFalse(app.ultimate.health.FoodJournal.day(emptyList(), emptyMap(), 5).logged)
    }

    @Test fun theWeekHasSevenDaysAndOnlySpeaksOnceThereIsEnoughToSay() {
        val j = app.ultimate.health.FoodJournal
        assertEquals(7, j.week(emptyList(), emptyMap(), 100).size)
        assertNull(j.summary(j.week(listOf(e(100, 500)), emptyMap(), 100)))
        val s = j.summary(j.week(listOf(e(98, 500), e(99, 700), e(100, 600)), mapOf(98L to 6, 99L to 8, 100L to 4), 100))!!
        assertTrue(s, s.contains("3 of 7") && s.contains("600 kcal") && s.contains("6.0"))
    }
}

class LowActivityTest {
    @Test fun lowStepsAreCalledOutEvenWhenNothingChangedFromLastWeek() {
        val steady = (87L..100L).map { HealthDay(it, steps = 3200) }
        val said = HealthDepth.worthDoing(steady, 100)
        assertTrue(said.toString(), said.first().contains("Your activity is low") && said.first().contains("3,200"))
        assertTrue("never the old, wrong 'nothing worth doing'", said.none { it.contains("Nothing stands out") })
    }

    @Test fun nearlyThereIsSaidGentlyAndMeetingTheGoalIsNotNagged() {
        val near = HealthDepth.worthDoing((87L..100L).map { HealthDay(it, steps = 6500) }, 100)
        assertTrue(near.first().contains("short of 8,000"))
        val fine = HealthDepth.worthDoing((87L..100L).map { HealthDay(it, steps = 9500) }, 100)
        assertTrue(fine.none { it.contains("low") || it.contains("short of") })
    }
}
