package app.ultimate

import app.ultimate.health.ActivityMath
import app.ultimate.health.ActivityMath.Goals
import app.ultimate.health.ActivityMath.Past
import app.ultimate.health.ActivityMath.Raw
import app.ultimate.health.ActivityMath.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityMathTest {
    /** A day of minutes with [rate] steps a minute from [from] to [to] (minute of the day). */
    private fun minutes(vararg spans: Triple<Int, Int, Int>): IntArray = IntArray(ActivityMath.MINUTES).also { a -> spans.forEach { (f, t, r) -> for (m in f until t) a[m] = r } }

    @Test fun activeMinutesCountBriskMinutesAndSessionsOnceEach() {
        val m = minutes(Triple(480, 540, 95), Triple(600, 620, 20))
        assertEquals("only the brisk hour counts; the 20-a-minute pottering doesn't", 60, ActivityMath.activeMinutes(m, emptyList()))
        // A session over the end of the walk adds only the minutes not already counted.
        assertEquals(60 + 30, ActivityMath.activeMinutes(m, listOf(Session(520, 570))))
        assertEquals("a session with no step data still counts", 30, ActivityMath.activeMinutes(null, listOf(Session(0, 30))))
        assertNull("nothing known is not zero", ActivityMath.activeMinutes(null, emptyList()))
        assertEquals("a session running past midnight is cut at the day's end", 10, ActivityMath.activeMinutes(null, listOf(Session(1430, 1500))))
    }

    @Test fun hoursAddUpTheMinutes() {
        val h = ActivityMath.hours(minutes(Triple(60, 120, 10), Triple(125, 130, 100)))
        assertEquals(600, h[1]); assertEquals(500, h[2]); assertEquals(0, h[0]); assertEquals(24, h.size)
    }

    @Test fun aDayBecomesItsWalksAndShortPausesDontSplitOne() {
        // 8:00-8:30 walking with a 2-minute stop at a crossing, a 3-minute fidget at noon, and a 12:30-13:10 run.
        val m = minutes(Triple(480, 495, 100), Triple(497, 510, 100), Triple(720, 723, 30), Triple(750, 790, 160))
        val b = ActivityMath.bursts(m, emptyList(), 0.75)
        assertEquals(2, b.size)
        assertEquals("Morning walk", b[0].label); assertEquals(480, b[0].startMin); assertEquals(510, b[0].endMin)
        assertEquals(28 * 100, b[0].steps); assertEquals(0.75 * 2800, b[0].metres!!, 0.01)
        assertEquals("Midday run", b[1].label); assertEquals(40, b[1].minutes)
    }

    @Test fun aLongPauseEndsAWalkAndTinyBurstsAreDropped() {
        val two = ActivityMath.bursts(minutes(Triple(480, 490, 100), Triple(500, 510, 100)), emptyList(), null)
        assertEquals("a 9-minute stop splits it", 2, two.size); assertNull(two[0].metres)
        assertTrue("5 minutes of walking is not a walk", ActivityMath.bursts(minutes(Triple(480, 485, 100)), emptyList(), null).isEmpty())
        assertTrue("slow shuffling never starts one", ActivityMath.bursts(minutes(Triple(480, 540, 10)), emptyList(), null).isEmpty())
        assertTrue(ActivityMath.bursts(null, emptyList(), null).isEmpty())
    }

    @Test fun anExerciseSessionNamesTheWalkItCovers() {
        val m = minutes(Triple(1080, 1110, 110))
        assertEquals("Hiking", ActivityMath.bursts(m, listOf(Session(1075, 1115, "Hiking")), null).single().label)
        assertEquals("a session over only a sliver of it doesn't rename it", "Evening walk", ActivityMath.bursts(m, listOf(Session(1100, 1200, "Cycling")), null).single().label)
        assertEquals("unnamed sessions are ignored", "Evening walk", ActivityMath.bursts(m, listOf(Session(1075, 1115, " ")), null).single().label)
    }

    @Test fun strideIsOnlyBelievedWhenItIsHuman() {
        assertEquals(0.75, ActivityMath.stride(10_000, 7_500.0)!!, 1e-9)
        assertNull("12 m a step is a bad sample", ActivityMath.stride(1_000, 12_000.0))
        assertNull("too few steps to say", ActivityMath.stride(50, 40.0))
        assertNull(ActivityMath.stride(null, 5_000.0))
    }

    private fun past(day: Long, perHour: Int, steps: Long = perHour * 24L) = Past(day, steps, null, null, null, IntArray(24) { perHour })

    @Test fun theUsualDayPrefersTheSameWeekdayAndShrugsOffOutliers() {
        val today = 100L
        val weekdays = listOf(93L, 86L, 79L).map { past(it, 400) } + past(95, 100)
        val u = ActivityMath.usual(weekdays, today)!!
        assertTrue(u.sameWeekday); assertEquals(3, u.basedOn); assertEquals(400.0, u.hours[10], 0.001)
        // Without three of the same weekday it uses recent days of any kind, with the middle value, so one huge day is ignored.
        val mixed = listOf(99L, 98L, 97L, 96L, 95L).map { past(it, 300) } + past(94, 3000)
        val m = ActivityMath.usual(mixed, today)!!
        assertFalse(m.sameWeekday); assertEquals(300.0, m.hours[5], 0.001)
        assertNull("four days is too few to call anything usual", ActivityMath.usual(listOf(99L, 98L, 97L, 96L).map { past(it, 300) }, today))
        assertNull("days with no steps are not evidence", ActivityMath.usual((90L..99L).map { past(it, 0, 0) }, today))
        assertNull("today and the future don't count", ActivityMath.usual((100L..110L).map { past(it, 300) }, today))
    }

    @Test fun paceSaysAheadOrBehindAndWhereTheDayWillEnd() {
        val usual = ActivityMath.Usual(DoubleArray(24) { if (it in 8..19) 800.0 else 0.0 }, 5, false) // 9,600 a day, 800 an hour from 8
        assertEquals("8-9, 9-10 and half of 10-11", 800.0 + 800.0 + 400.0, ActivityMath.expectedBy(usual, 10 * 60 + 30), 0.001)
        val p = ActivityMath.pace(3_000, usual, 10 * 60 + 30, 10_000)!!
        assertEquals(2_000.0, p.expected, 0.001)
        assertEquals(1_000.0, p.ahead, 0.001)
        assertEquals("3,000 + the 7,600 you'd usually still do", 3_000 + 7_600, p.projected)
        assertNotNull("it would cross 10,000 later in the day", p.goalEtaMin)
        // 7,000 more at 800 an hour: about 8 h 45 min on, a little after 7 PM.
        assertTrue("was ${p.goalEtaMin}", p.goalEtaMin!! in (19 * 60)..(19 * 60 + 30))
        assertTrue("words say ahead", ActivityMath.paceWords(p, 10 * 60 + 30)!!.contains("ahead"))
        assertTrue(ActivityMath.paceWords(ActivityMath.pace(100, usual, 10 * 60 + 30, 10_000), 10 * 60 + 30)!!.contains("behind"))
        assertNull("no goal time once it's reached", ActivityMath.pace(10_500, usual, 12 * 60, 10_000)!!.goalEtaMin)
    }

    @Test fun paceStaysQuietWhenItWouldBeNoise() {
        val usual = ActivityMath.Usual(DoubleArray(24) { 400.0 }, 5, false)
        assertNull("no history", ActivityMath.pace(1_000, null, 600, 10_000))
        assertNull("too early in the day to compare", ActivityMath.paceWords(ActivityMath.pace(10, usual, 6 * 60, 10_000), 6 * 60))
        val near = ActivityMath.pace(4_900, usual, 12 * 60, 10_000)!! // expected 4,800
        assertTrue(ActivityMath.paceWords(near, 12 * 60)!!.startsWith("Right on"))
        assertNull("a near-empty usual day says nothing", ActivityMath.pace(1_000, ActivityMath.Usual(DoubleArray(24) { 10.0 }, 5, false), 600, 10_000))
    }

    @Test fun clockTimesAreReadable() {
        assertEquals("12 AM", ActivityMath.clock(0)); assertEquals("12 PM", ActivityMath.clock(720)); assertEquals("5 PM", ActivityMath.clock(17 * 60))
        assertEquals("9:35 AM", ActivityMath.clock(9 * 60 + 34)); assertEquals("11:55 PM", ActivityMath.clock(1439))
        assertEquals("5 PM – 6 PM", ActivityMath.hourRange(17)); assertEquals("11 PM – 12 AM", ActivityMath.hourRange(23))
    }

    @Test fun theBusiestHourIsFoundOrNone() {
        assertEquals(17, ActivityMath.peakHour(IntArray(24).also { it[17] = 3000; it[9] = 2000 }))
        assertNull(ActivityMath.peakHour(IntArray(24)))
    }

    @Test fun ringsFillPastOneForLapsAndStayNullWhenUnknown() {
        assertEquals(2.6889f, ActivityMath.fill(26_889.0, 10_000)!!, 1e-4f)
        assertEquals(0f, ActivityMath.fill(0.0, 90)!!, 0f)
        assertNull(ActivityMath.fill(null, 500))
    }

    @Test fun aWholeDayIsPutTogetherFromWhatIsThere() {
        val m = minutes(Triple(480, 540, 100), Triple(1020, 1080, 110))
        val raw = Raw(100, 12_600, 9_000.0, 640.0, 2_200.0, 4.0, m, null, emptyList(), 123L, listOf("Samsung Health"))
        val d = ActivityMath.build(raw, emptyList(), Goals(), 20 * 60, true)!!
        assertEquals(12_600, d.steps); assertEquals(120, d.activeMin); assertEquals(2, d.bursts.size); assertEquals(17, d.peak)
        assertEquals(4, d.floors); assertNull("no history, so no pace", d.pace); assertEquals(listOf("Samsung Health"), d.sources)
        val (s, a, k) = d.rings(Goals())
        assertEquals(1.26f, s!!, 1e-3f); assertEquals(1.333f, a!!, 1e-3f); assertEquals(1.28f, k!!, 1e-3f)
        // Only minutes known: the total comes from them. Nothing at all: no day.
        assertEquals(12_600L, ActivityMath.build(Raw(100, null, null, null, null, null, m, null), emptyList(), Goals(), 0, false)!!.steps)
        assertNull(ActivityMath.build(Raw(100, null, null, null, null, null, null, null), emptyList(), Goals(), 0, false))
        // A finished day carries no live pace.
        assertNull(ActivityMath.build(raw, (1L..20L).map { past(100 - it, 300) }, Goals(), 20 * 60, false)!!.pace)
        assertNotNull(ActivityMath.build(raw, (1L..20L).map { past(100 - it, 300) }, Goals(), 20 * 60, true)!!.pace)
    }

    @Test fun theWeekCountsClosedDaysAndTheStreak() {
        val goals = Goals(steps = 8_000)
        val past = listOf(94L to 5_000L, 95L to 9_000L, 96L to 8_100L, 97L to 8_000L, 98L to 9_500L, 99L to 8_800L).map { (d, s) -> Past(d, s, null, null, null, null) }
        val noon = ActivityMath.build(Raw(100, 2_000, null, null, null, null, null, IntArray(24).also { it[9] = 2000 }), emptyList(), goals, 12 * 60, true)
        val w = ActivityMath.week(past, noon, goals, 100)
        assertEquals(7, w.days.size); assertEquals(5, w.closed)
        assertEquals("today unfinished: the run is counted back from yesterday", 5, w.streak)
        assertEquals(9_500L, w.best!!.steps); assertEquals(((5000 + 9000 + 8100 + 8000 + 9500 + 8800 + 2000) / 7).toLong(), w.avgSteps)
        assertNull("too few days to average", ActivityMath.week(past.take(1), null, goals, 100).avgSteps)
        assertNull(ActivityMath.week(emptyList(), null, goals, 100).best)
    }

    @Test fun wordsAndUnitsAreHonest() {
        val g = Goals()
        fun day(steps: Long, active: Int? = null) = ActivityMath.Day(1, steps, null, null, null, null, active, IntArray(24), emptyList(), null, null, null, null, emptyList())
        assertEquals("Huge day: 2.7× your step goal.", ActivityMath.headline(day(26_889), g))
        assertEquals("Step goal done, and your active minutes too.", ActivityMath.headline(day(10_000, 95), g))
        assertEquals("Step goal done.", ActivityMath.headline(day(10_000, 20), g))
        assertEquals("Nearly there: 1,500 steps to go.", ActivityMath.headline(day(8_500), g))
        assertEquals("6,000 steps to your goal.", ActivityMath.headline(day(4_000), g))
        assertEquals("No steps yet today.", ActivityMath.headline(day(0), g))
        assertEquals("12.88 mi", ActivityMath.distance(20_730.0, true)); assertEquals("20.73 km", ActivityMath.distance(20_730.0, false)); assertEquals("450 m", ActivityMath.distance(450.0, false))
        assertTrue(ActivityMath.milesFor("US")); assertFalse(ActivityMath.milesFor("DE")); assertFalse(ActivityMath.milesFor(null))
        assertEquals("45 min", ActivityMath.minutesWords(45)); assertEquals("4 h 47 min", ActivityMath.minutesWords(287))
        assertEquals(Goals(1_000, 10, 50), Goals(-5, 0, 0).sane()); assertEquals(Goals(100_000, 600, 5_000), Goals(10_000_000, 9_999, 99_999).sane())
    }
}
