package app.ultimate.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepActivityTest {
    /** Feeds a step counter every 30 s (how often the hardware batch arrives) at [spm] steps a minute for [minutes]. */
    private fun run(a: StepActivity, from: Long, start: Long, spm: Int, minutes: Int): Pair<Long, List<StepActivity.Change>> {
        var t = from; var c = start; val out = ArrayList<StepActivity.Change>()
        repeat(minutes * 2) { t += 30_000; c += spm / 2; a.feed(c, t)?.let { out += it } }
        return c to out
    }

    @Test fun walkingStartsAfterAboutAMinuteAndStopsAfterTwoOfRest() {
        val a = StepActivity()
        a.feed(1000, 0)
        val (c1, w) = run(a, 0, 1000, 110, 3)
        assertEquals(listOf(StepActivity.State.Walking), w.map { it.state })
        assertEquals(StepActivity.State.Walking, a.state)
        assertEquals(110.0, a.spm, 1.0)
        val (_, s) = run(a, 180_000, c1, 0, 4)
        assertEquals(listOf(StepActivity.State.Still), s.map { it.state })
    }

    @Test fun aFewStepsAcrossARoomStartNothing() {
        val a = StepActivity()
        a.feed(0, 0)
        assertNull(a.feed(15, 30_000)); assertNull(a.feed(15, 60_000)); assertNull(a.feed(30, 90_000)); assertNull(a.feed(30, 120_000))
        assertEquals(StepActivity.State.Still, a.state)
    }

    @Test fun runningIsToldFromWalkingByCadence() {
        val a = StepActivity()
        a.feed(0, 0)
        val (_, ch) = run(a, 0, 0, 165, 3)
        assertEquals(StepActivity.State.Running, ch.last().state)
    }

    @Test fun aRebootedCounterDoesNotLookLikeAMarathon() {
        val a = StepActivity()
        a.feed(50_000, 0); a.feed(50_050, 30_000)
        assertNull(a.feed(10, 60_000))
        assertTrue(a.spm < 200)
    }

    @Test fun thePhonesRecordingStatusIsRead() {
        assertTrue(StepActivity.fromPhone("movement:start", 1))
        assertTrue(StepActivity.fromPhone("movement:status:1234.5:600", 2))
        assertEquals(1234.5, StepActivity.phoneRecording.value!!.metres, 0.0)
        assertEquals(600, StepActivity.phoneRecording.value!!.seconds)
        assertTrue(StepActivity.fromPhone("movement:stop", 3))
        assertNull(StepActivity.phoneRecording.value)
        assertTrue(!StepActivity.fromPhone("stream:on"))
    }
}
