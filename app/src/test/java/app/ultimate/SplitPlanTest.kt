package app.ultimate

import app.ultimate.audio.model.SplitPlan
import app.ultimate.audio.model.SplitPlan.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPlanTest {
    private val hour = 3_600_000L
    private val min = 60_000L

    @Test fun anEightHourFileInHourPartsIsEightParts() {
        val p = SplitPlan.plan(0, 8 * hour, hour)
        assertEquals(8, p.size)
        assertEquals((0 until 8).toList(), p.map { it.index })
        assertTrue(p.all { it.ms == hour })
        assertEquals("the parts run end to end", p.dropLast(1).map { it.endMs }, p.drop(1).map { it.startMs })
        assertEquals(0L, p.first().startMs); assertEquals(8 * hour, p.last().endMs)
    }

    @Test fun aSmallLeftoverJoinsTheLastPartAndABigOneStandsAlone() {
        val small = SplitPlan.plan(0, 8 * hour + 5 * min, hour)
        assertEquals(8, small.size); assertEquals(hour + 5 * min, small.last().ms)
        val big = SplitPlan.plan(0, 8 * hour + 20 * min, hour)
        assertEquals(9, big.size); assertEquals(20 * min, big.last().ms)
        // Exactly a quarter of a part is big enough to stand alone.
        assertEquals(9, SplitPlan.plan(0, 8 * hour + 15 * min, hour).size)
    }

    @Test fun aRangeNotMuchLongerThanOnePartIsNotCut() {
        assertEquals(1, SplitPlan.plan(0, 40 * min, hour).size)
        assertEquals("70 minutes in hour parts: the 10 left over joins the one part", listOf(Part(0, 0, 70 * min)), SplitPlan.plan(0, 70 * min, hour))
        assertEquals(2, SplitPlan.plan(0, 80 * min, hour).size)
        assertEquals("no length asked for means one file", 1, SplitPlan.plan(0, 5 * hour, 0).size)
        assertEquals(1, SplitPlan.plan(10, 10, hour).size)
    }

    @Test fun partsStartWhereTheTrimStarts() {
        val p = SplitPlan.plan(30 * min, 30 * min + 3 * hour, hour)
        assertEquals(listOf(30 * min, 90 * min, 150 * min), p.map { it.startMs })
        assertEquals(30 * min + 3 * hour, p.last().endMs)
    }

    /** Power per 100 ms slice: loud talking with a pause of [pauseHops] slices starting at slice [at]. */
    private fun talk(slices: Int, at: Int, pauseHops: Int, level: Double = 0.2, quiet: Double = 0.0001) =
        DoubleArray(slices) { if (it in at until at + pauseHops) quiet else level }

    @Test fun theQuietestPauseIsFoundAndTheNearerOfTwoWins() {
        // 60 s of slices with one pause around 41 s.
        val e = talk(600, at = 405, pauseHops = 8)
        val at = SplitPlan.quietestMs(e, 100, aroundMs = 30_000)!!
        assertTrue("found the pause near 41 s, got $at", at in 40_500..41_500)
        // Two equal pauses: the one nearer the planned cut wins.
        val two = talk(600, 100, 8).also { for (i in 450 until 458) it[i] = 0.0001 }
        assertTrue(SplitPlan.quietestMs(two, 100, aroundMs = 30_000)!! in 44_500..46_000)
        assertTrue(SplitPlan.quietestMs(two, 100, aroundMs = 12_000)!! in 10_000..11_500)
    }

    @Test fun silenceCountsAndTooLittleSoundGivesNothing() {
        val flat = DoubleArray(600) { 0.0 }
        val mid = SplitPlan.quietestMs(flat, 100, aroundMs = 20_000)!!
        assertTrue("all silent: stays close to where it was planned, got $mid", mid in 19_500..20_500)
        assertNull(SplitPlan.quietestMs(DoubleArray(2), 100, 0))
        assertNotNull(SplitPlan.quietestMs(DoubleArray(4) { 0.1 }, 100, 0))
    }

    @Test fun nudgingKeepsTheWholeAndNeverCrossesNeighbours() {
        val p = SplitPlan.plan(0, 3 * hour, hour)
        val moved = SplitPlan.nudge(p, listOf(hour + 20_000, null))
        assertEquals(hour + 20_000, moved[0].endMs); assertEquals(hour + 20_000, moved[1].startMs)
        assertEquals(2 * hour, moved[1].endMs); assertEquals(0L, moved.first().startMs); assertEquals(3 * hour, moved.last().endMs)
        // A wild cut is held to half way to the next one.
        val wild = SplitPlan.nudge(p, listOf(5 * hour, -5 * hour))
        assertEquals(hour + hour / 2, wild[0].endMs)
        assertTrue(wild.all { it.ms > 0 })
        assertEquals(p, SplitPlan.nudge(p, listOf(null, null)))
        assertEquals("a single part has nothing to move", p.take(1), SplitPlan.nudge(p.take(1), listOf(5L)))
    }

    @Test fun partsAreNamedSoTheySortInOrder() {
        assertEquals("Meeting - part 03 of 08", SplitPlan.partName("Meeting", 2, 8))
        assertEquals("Meeting - part 10 of 12", SplitPlan.partName("Meeting", 9, 12))
        assertEquals("Day - part 007 of 120", SplitPlan.partName("Day", 6, 120))
        assertEquals("1 h", SplitPlan.minutesLabel(60)); assertEquals("2 h", SplitPlan.minutesLabel(120)); assertEquals("30 min", SplitPlan.minutesLabel(30))
    }
}
