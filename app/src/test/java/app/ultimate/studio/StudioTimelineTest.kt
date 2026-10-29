package app.ultimate.studio

import org.junit.Assert.*
import org.junit.Test

class StudioTimelineTest {
    private fun clip(speed: Float = 1f) = StudioClip(id = "a", uri = "content://video/1", name = "Clip", sourceMs = 10000, startMs = 1000, endMs = 9000, speed = speed)
    @Test fun splitMapsOutputClockBackToTrimmedSource() {
        val c = clip(2f)
        val parts = Timeline.split(c, 1500)
        assertEquals(4000, parts[0].endMs)
        assertEquals(4000, parts[1].startMs)
        assertEquals(c.endMs, parts[1].endMs)
        assertEquals(c.durationMs, parts.sumOf { it.durationMs })
        assertNotEquals(parts[0].id, parts[1].id)
    }
    @Test(expected = IllegalArgumentException::class) fun splitCannotCreateEmptyClip() { Timeline.split(clip(), 0) }
    @Test fun offsetsUseEditedDurations() {
        val a = clip(2f); val b = clip().copy(id = "b")
        assertEquals(4000, Timeline.offset(StudioProject(clips = listOf(a, b)), "b"))
    }
    @Test fun locateFindsTheClipUnderTheOutputClock() {
        val a = clip(2f)                    // 4000 ms on the output clock
        val b = clip().copy(id = "b")       // 8000 ms
        val p = StudioProject(clips = listOf(a, b))
        assertEquals("a" to 0L, Timeline.locate(p, 0)!!.let { it.first.id to it.second })
        assertEquals("a" to 3999L, Timeline.locate(p, 3999)!!.let { it.first.id to it.second })
        assertEquals("b" to 0L, Timeline.locate(p, 4000)!!.let { it.first.id to it.second })
        assertEquals("the very end belongs to the last clip", "b" to 8000L, Timeline.locate(p, 12_000)!!.let { it.first.id to it.second })
        assertEquals("b" to 8000L, Timeline.locate(p, 99_000)!!.let { it.first.id to it.second })
        assertNull(Timeline.locate(StudioProject(), 0))
        assertEquals(listOf(0L, 4000L, 12_000L), Timeline.boundaries(p))
    }

    @Test fun trimsStayInsideTheSourceAndKeepAMinimumLength() {
        val c = clip()                       // source 10000, kept 1000..9000
        assertEquals(0, Timeline.trimStart(c, -500).startMs)
        assertEquals(c.endMs - Timeline.MIN_CLIP_MS, Timeline.trimStart(c, 9500).startMs)
        assertEquals(10_000, Timeline.trimEnd(c, 20_000).endMs)
        assertEquals(c.startMs + Timeline.MIN_CLIP_MS, Timeline.trimEnd(c, 0).endMs)
        Timeline.validate(StudioProject(clips = listOf(Timeline.trimStart(c, 9500), Timeline.trimEnd(c, 0))))
    }

    @Test fun reorderMovesOneItemAndShiftsTheRest() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), Timeline.reorder(l, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), Timeline.reorder(l, 3, 0))
        assertEquals(listOf("a", "b", "c", "d"), Timeline.reorder(l, 1, 1))
        assertEquals("out-of-range targets clamp", listOf("b", "c", "d", "a"), Timeline.reorder(l, 0, 99))
        assertEquals("unknown source is a no-op", l, Timeline.reorder(l, -1, 2))
    }

    @Test fun reorderSlotFollowsTheFingerByWholeTilesAndStaysInRange() {
        assertEquals(2, Timeline.reorderSlot(2, 20f, 60f, 5))
        assertEquals(3, Timeline.reorderSlot(2, 31f, 60f, 5))
        assertEquals(0, Timeline.reorderSlot(2, -170f, 60f, 5))
        assertEquals(4, Timeline.reorderSlot(2, 999f, 60f, 5))
        assertEquals(0, Timeline.reorderSlot(0, 50f, 60f, 1))
    }

    @Test fun undoRedoPreserveEveryEditAndNewEditsInvalidateRedo() {
        val h = StudioHistory(); val a = StudioProject(name = "A"); val b = a.copy(name = "B")
        h.record(a); assertEquals(a, h.undo(b)); assertEquals(b, h.redo(a))
        h.undo(b); h.record(a); assertFalse(h.canRedo)
    }
    @Test fun beatsDoNotAccumulateIntegerRoundingDrift() {
        assertEquals(60000, Timeline.beatMs(126, 126))
        assertEquals(2500, Timeline.nearestBeat(2400, 120, 500))
    }
    @Test fun beatCutsRespectSpeedAndTrim() {
        val c = clip(2f); val p = StudioProject(clips = listOf(c), bpm = 120)
        val result = Timeline.cutToBeats(p, 4)
        assertEquals(2000, result.durationMs)
        assertEquals(5000, result.clips[0].endMs)
    }
    @Test(expected = IllegalArgumentException::class) fun shortSourceDoesNotSilentlyBreakBeatGrid() {
        Timeline.cutToBeats(StudioProject(clips = listOf(clip().copy(endMs = 1500))), 4)
    }
    @Test(expected = IllegalArgumentException::class) fun roundedDurationCannotOverrunSource() {
        Timeline.cutToBeats(StudioProject(clips = listOf(clip(2f).copy(endMs = 4999)), bpm = 120), 4)
    }
    @Test fun beatOffsetIsPreservedInFirstCut() {
        val result = Timeline.cutToBeats(StudioProject(clips = listOf(clip()), bpm = 120, beatOffsetMs = 500), 4)
        assertEquals(2500, result.durationMs)
    }
    @Test fun canvasDimensionsAreEvenAndMatchRatio() {
        val p = StudioProject(shortSide = 1080)
        assertEquals(1080, p.width); assertEquals(1920, p.height)
        assertEquals(1920, p.copy(shape = CanvasShape.Landscape).width)
        assertEquals(1080, p.copy(shape = CanvasShape.Square).height)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidTrimIsRefusedBeforeRendering() { Timeline.validate(StudioProject(clips = listOf(clip().copy(endMs = 11000)))) }
    @Test(expected = IllegalArgumentException::class) fun invalidSpeedIsRefusedBeforeRendering() { Timeline.validate(StudioProject(clips = listOf(clip(Float.NaN)))) }
}
