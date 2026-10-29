package app.ultimate

import app.ultimate.studio.CanvasShape
import app.ultimate.studio.CaptionStyle
import app.ultimate.studio.ExportPreset
import app.ultimate.studio.Fades
import app.ultimate.studio.StudioClip
import app.ultimate.studio.StudioEffects
import app.ultimate.studio.StudioJson
import app.ultimate.studio.StudioProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioCaptionTest {
    @Test fun wrapKeepsWordsWhole() {
        assertEquals(listOf("the quick brown", "fox jumps"), StudioEffects.wrap("the quick brown fox jumps", 15))
        assertEquals(listOf("abcdefghij", "klm"), StudioEffects.wrap("abcdefghijklm", 10))
        assertEquals(listOf("short"), StudioEffects.wrap("short", 10))
    }

    @Test fun fadesRiseAndFall() {
        assertEquals(0f, Fades.gain(0, 100, 100, 1000), 0f)
        assertEquals(0.5f, Fades.gain(50, 100, 100, 1000), 1e-6f)
        assertEquals(1f, Fades.gain(500, 100, 100, 1000), 0f)
        assertEquals(0.5f, Fades.gain(950, 100, 100, 1000), 1e-6f)
        assertEquals(0f, Fades.gain(1000, 100, 100, 1000), 0f)
        assertEquals(1f, Fades.gain(0, 0, 0, 1000), 0f)
    }

    @Test fun presetsSetShapeSizeAndFill() {
        val p = ExportPreset.Vertical.applyTo(StudioProject(shape = CanvasShape.Landscape))
        assertEquals(CanvasShape.Portrait, p.shape); assertEquals(1080, p.shortSide); assertTrue(p.fillCanvas)
        assertTrue(ExportPreset.Vertical.matches(p))
        assertEquals(1080 to 1920, p.width to p.height)
    }

    @Test fun captionsAndFadesSurviveSaving() {
        val p = StudioProject(clips = listOf(StudioClip(uri = "content://v", name = "v", sourceMs = 1000, title = "Hi", captionStyle = CaptionStyle.Pop, captionColor = 0xFFE14D)), fadeInMs = 1000, fadeOutMs = 2000)
        val back = StudioJson.decode(StudioJson.encode(p))
        assertEquals(CaptionStyle.Pop, back.clips[0].captionStyle)
        assertEquals(0xFFE14D, back.clips[0].captionColor)
        assertEquals(1000L to 2000L, back.fadeInMs to back.fadeOutMs)
    }

    @Test fun oldProjectsStillOpen() {
        val old = StudioJson.encode(StudioProject()).replace(",\"fadeIn\":0,\"fadeOut\":0", "")
        assertEquals(0L, StudioJson.decode(old).fadeInMs)
    }
}
