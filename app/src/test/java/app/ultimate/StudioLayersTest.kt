package app.ultimate

import app.ultimate.studio.LayerBitmaps
import app.ultimate.studio.LayerKind
import app.ultimate.studio.Layers
import app.ultimate.studio.StudioClip
import app.ultimate.studio.StudioJson
import app.ultimate.studio.StudioLayer
import app.ultimate.studio.StudioProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioLayersTest {
    private fun film(ms: Long, layers: List<StudioLayer> = emptyList()) = StudioProject(name = "T", clips = listOf(StudioClip(uri = "content://x", name = "a", sourceMs = ms)), layers = layers)

    @Test fun aNewLayerIsThreeSecondsFromThePlayheadAndFitsShortFilms() {
        val p = film(20_000)
        val t = Layers.create(p, LayerKind.Text, "Hi", 5_000); assertEquals(5_000L, t.startMs); assertEquals(8_000L, t.endMs); assertEquals(0.78f, t.fy, 0.001f)
        val s = Layers.create(p, LayerKind.Sticker, "🔥", 19_000); assertEquals("pulled back so it fits", 17_000L, s.startMs); assertEquals(20_000L, s.endMs); assertEquals(0.5f, s.fy, 0.001f)
        val short = Layers.create(film(1_200), LayerKind.Text, "x", 0); assertEquals(1_200L, short.endMs)
    }

    @Test fun layersShowOnlyBetweenTheirStartAndEnd() {
        val p = film(10_000, listOf(StudioLayer(kind = LayerKind.Text, text = "a", startMs = 1000, endMs = 2000), StudioLayer(kind = LayerKind.Sticker, text = "🔥", startMs = 1500, endMs = 4000)))
        assertEquals(0, Layers.activeAt(p, 500).size); assertEquals(1, Layers.activeAt(p, 1200).size); assertEquals(listOf("a", "🔥"), Layers.activeAt(p, 1700).map { it.text }); assertEquals("end is exclusive", 1, Layers.activeAt(p, 2000).size)
    }

    @Test fun clampKeepsAMinimumLengthInsideTheFilmAndInRange() {
        val c = Layers.clamp(StudioLayer(kind = LayerKind.Text, text = "a", startMs = 9_900, endMs = 9_950, fx = 3f, fy = -1f, size = 99f), 10_000)
        assertTrue(c.endMs - c.startMs >= Layers.MIN_MS && c.endMs <= 10_000 && c.startMs >= 0); assertEquals(1f, c.fx, 0f); assertEquals(0f, c.fy, 0f); assertEquals(4f, c.size, 0f)
    }

    @Test fun shorteningTheFilmPullsLayersBackAndDropsOnesBeyondTheEnd() {
        val layers = listOf(StudioLayer(kind = LayerKind.Text, text = "keep", startMs = 1000, endMs = 9000), StudioLayer(kind = LayerKind.Text, text = "gone", startMs = 8000, endMs = 9500))
        val shorter = film(5_000, layers)
        val r = Layers.refit(shorter)
        assertEquals(listOf("keep"), r.map { it.text }); assertEquals(5_000L, r[0].endMs)
    }

    @Test fun layersSurviveSavingAndOldProjectsStillOpen() {
        val p = film(10_000, listOf(StudioLayer("id1", LayerKind.Sticker, "🚀", 100, 2500, 0.25f, 0.6f, 2f, 0xFFE14D)))
        val back = StudioJson.decode(StudioJson.encode(p))
        assertEquals(p.layers, back.layers)
        val old = org.json.JSONObject(StudioJson.encode(film(5000))).also { it.remove("layers") }.toString()
        assertTrue(StudioJson.decode(old).layers.isEmpty())
    }

    @Test fun theExportBitmapIsFrameSizedWithTextWhereItWasPlacedAndNothingElse() {
        val l = StudioLayer(kind = LayerKind.Text, text = "HELLO", startMs = 0, endMs = 1000, fx = 0.5f, fy = 0.5f)
        val b = LayerBitmaps.render(l, 720, 1280)
        assertEquals(720, b.width); assertEquals(1280, b.height)
        assertEquals("corner is transparent", 0, android.graphics.Color.alpha(b.getPixel(5, 5)))
        var inked = 0; for (x in 200..520 step 4) for (y in 560..720 step 4) if (android.graphics.Color.alpha(b.getPixel(x, y)) > 0) inked++
        assertTrue("text drawn around the middle ($inked)", inked > 20)
        var top = 0; for (x in 0 until 720 step 8) for (y in 0..300 step 8) if (android.graphics.Color.alpha(b.getPixel(x, y)) > 0) top++
        assertEquals("nothing drawn far from it", 0, top)
        assertFalse(LayerBitmaps.textPx(720, l.copy(size = 2f)) <= LayerBitmaps.textPx(720, l))
    }
}

class SilenceCutTest {
    private fun db(vararg runs: Pair<Int, Float>): FloatArray = runs.flatMap { (n, v) -> List(n) { v } }.toFloatArray()   // windows of 50 ms

    @Test fun longQuietStretchesAreFoundWithAMarginKeptOnEachSide() {
        // 1 s speech, 1 s quiet, 1 s speech.
        val cuts = app.ultimate.studio.Silence.find(db(20 to -20f, 20 to -60f, 20 to -20f), thresholdDb = -42f, minSilentMs = 450, keepMs = 120)
        assertEquals(1, cuts.size); assertEquals(1120L..1879L, cuts[0])
    }

    @Test fun shortGapsBetweenWordsAndPausesTooSmallToCutAreLeftAlone() {
        assertTrue("a 300 ms breath is not cut", app.ultimate.studio.Silence.find(db(20 to -20f, 6 to -60f, 20 to -20f)).isEmpty())
        assertTrue("500 ms quiet minus margins leaves under 100 ms", app.ultimate.studio.Silence.find(db(20 to -20f, 10 to -60f, 20 to -20f), keepMs = 230).isEmpty())
        assertTrue(app.ultimate.studio.Silence.find(db(40 to -20f)).isEmpty()); assertTrue(app.ultimate.studio.Silence.find(FloatArray(0)).isEmpty())
    }

    @Test fun quietAtTheStartAndEndIsCutToo() {
        val cuts = app.ultimate.studio.Silence.find(db(14 to -60f, 20 to -20f, 14 to -60f))
        assertEquals(2, cuts.size); assertTrue(cuts[0].last < 700 && cuts[1].first > 1500)
    }

    @Test fun theThresholdFollowsTheRecordingsOwnNoiseFloorWithinLimits() {
        val quietRoom = db(30 to -58f, 30 to -20f); val noisyRoom = db(30 to -38f, 30 to -15f)
        assertEquals(-49f, app.ultimate.studio.Silence.thresholdFor(quietRoom), 0.1f); assertEquals(-30f, app.ultimate.studio.Silence.thresholdFor(noisyRoom), 0.1f)
        assertEquals(-42f, app.ultimate.studio.Silence.thresholdFor(FloatArray(0)), 0f)
    }

    @Test fun removingRangesLeavesTheSpeechPiecesWithTheClipsSettingsAndUndoableIds() {
        val c = StudioClip(id = "c", uri = "content://x", name = "a", sourceMs = 10_000, startMs = 1_000, endMs = 9_000, volume = 0.7f)
        val parts = app.ultimate.studio.Timeline.removeRanges(c, listOf(1_000L..1_999L, 5_000L..5_999L))
        assertEquals(3, parts.size); assertEquals("c", parts[0].id)
        assertEquals(1_000L, parts[0].startMs); assertEquals(2_000L, parts[0].endMs); assertEquals(3_000L, parts[1].startMs); assertEquals(7_000L, parts[2].startMs); assertEquals(9_000L, parts[2].endMs)
        assertTrue(parts.all { it.volume == 0.7f } && parts.map { it.id }.toSet().size == 3)
        assertEquals(listOf(c), app.ultimate.studio.Timeline.removeRanges(c, emptyList()))
        assertTrue("pieces under the minimum are dropped", app.ultimate.studio.Timeline.removeRanges(c, listOf(0L..7_950L)).isEmpty())
    }
}
