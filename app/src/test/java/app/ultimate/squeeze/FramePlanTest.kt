package app.ultimate.squeeze.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePlanTest {
    @Test fun timestampsAreEvenlySpreadAcrossTheWholeClip() {
        val t = FramePlan.timestampsUs(60_000, 12)
        assertEquals(12, t.size)
        assertEquals(2_500_000L, t.first())      // middle of the first 5 s slice, not the opening frame
        assertEquals(57_500_000L, t.last())      // and well clear of the end of the stream
        t.zipWithNext().forEach { (a, b) -> assertEquals(5_000_000L, b - a) }
        assertTrue(t.all { it in 1 until 60_000_000L })
    }

    @Test fun noDurationOrCountMeansNoFrames() {
        assertTrue(FramePlan.timestampsUs(0).isEmpty())
        assertTrue(FramePlan.timestampsUs(-5).isEmpty())
        assertTrue(FramePlan.timestampsUs(1000, 0).isEmpty())
    }

    @Test fun scaledSizeKeepsAspectAndNeverScalesUp() {
        assertEquals(320 to 180, FramePlan.scaledSize(1920, 1080))
        assertEquals(320 to 180, FramePlan.scaledSize(3840, 2160))
        assertEquals(180 to 320, FramePlan.scaledSize(1080, 1920))
        assertEquals(160 to 90, FramePlan.scaledSize(160, 90))
    }

    @Test fun sidewaysRotationSwapsTheSides() {
        assertEquals(180 to 320, FramePlan.scaledSize(1920, 1080, rotation = 90))
        assertEquals(180 to 320, FramePlan.scaledSize(1920, 1080, rotation = 270))
        assertEquals(320 to 180, FramePlan.scaledSize(1920, 1080, rotation = 180))
    }

    @Test fun unusableDimensionsGiveNoSize() {
        assertNull(FramePlan.scaledSize(0, 1080))
        assertNull(FramePlan.scaledSize(1920, -1))
        assertNull(FramePlan.scaledSize(1920, 1080, maxEdge = 0))
    }

    @Test fun stepHoldsThenBlendsIntoTheNextFrame() {
        val hold = FramePlan.step(3.2f, 12)
        assertEquals(3, hold.from); assertEquals(4, hold.to); assertEquals(0f, hold.mix, 1e-6f)
        val mid = FramePlan.step(3.675f, 12)
        assertEquals(0.5f, mid.mix, 1e-3f)
        val late = FramePlan.step(3.99f, 12)
        assertTrue(late.mix > 0.99f)
    }

    @Test fun stepWrapsAroundTheEndAndBeforeTheStart() {
        val last = FramePlan.step(11.9f, 12)
        assertEquals(11, last.from); assertEquals(0, last.to)
        assertEquals(2, FramePlan.step(14.1f, 12).from)
        assertEquals(11, FramePlan.step(-0.5f, 12).from)
    }

    @Test fun stepWithOneOrNoFramesStaysPut() {
        assertEquals(FramePlan.Step(0, 0, 0f), FramePlan.step(5f, 1))
        assertEquals(FramePlan.Step(0, 0, 0f), FramePlan.step(5f, 0))
    }

    @Test fun framesDoneTracksProgress() {
        assertEquals(0, FramePlan.framesDone(0f, 12))
        assertEquals(6, FramePlan.framesDone(0.5f, 12))
        assertEquals(12, FramePlan.framesDone(1f, 12))
        assertEquals(12, FramePlan.framesDone(7f, 12))
        assertEquals(0, FramePlan.framesDone(-1f, 12))
    }
}
