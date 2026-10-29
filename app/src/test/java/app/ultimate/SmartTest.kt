package app.ultimate

import app.ultimate.checkup.model.AppSnap
import app.ultimate.checkup.model.Guardian
import app.ultimate.checkup.model.Snapshot
import app.ultimate.clean.CleanSummary
import app.ultimate.home.Commands
import app.ultimate.recipes.Kind
import app.ultimate.recipes.RecipeRules
import app.ultimate.recipes.Step
import app.ultimate.screentime.UsageMath
import app.ultimate.screentime.UseEvent
import app.ultimate.send.SendHttp
import app.ultimate.speech.Captions
import app.ultimate.speech.Chunker
import app.ultimate.speech.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTest {
    @Test fun typedRequestsFindTheRightTool() {
        assertEquals("squeeze", Commands.search("make this video smaller")[0].toolId)
        assertEquals("recorder", Commands.search("record my voice")[0].toolId)
        assertEquals("checkup", Commands.search("is my phone safe")[0].toolId)
        assertEquals("send", Commands.search("put this on my laptop")[0].toolId)
        assertTrue(Commands.search("x").isEmpty())
        val ids = Commands.available(Commands.forMime("video/mp4"), setOf("squeeze"), shareClean = true).map { it.id }
        assertEquals(listOf("squeeze", "clean"), ids)
    }

    @Test fun cleanSummaryReadsNaturally() {
        assertEquals("Removed location and camera details from the file.", CleanSummary.line(listOf(listOf("location", "camera details"))))
        assertEquals("Nothing hidden was found in 2 files. It's ready to send as is.", CleanSummary.line(listOf(emptyList(), emptyList())))
    }

    @Test fun speechIsCutAtQuietPointsAndSilenceDropped() {
        val levels = FloatArray(150) { if (it in 40..44 || it >= 120) 10f else 1000f }
        val chunks = Chunker.split(levels, minWin = 20, maxWin = 70, silence = 120f)
        assertEquals(0, chunks[0].first)
        assertTrue(chunks[0].last + 1 in 40..44) // cut inside the pause
        assertTrue(chunks.all { r -> (r.last - r.first + 1) <= 70 })
        assertTrue(chunks.none { it.first >= 120 && it.last < 150 && (it.first..it.last).all { w -> w >= 120 } })
        val pcm = ShortArray(3200) { if (it < 1600) 1000 else 0 }
        assertEquals(listOf(1000f, 0f), Chunker.levels(pcm).toList())
    }

    @Test fun captionsByTime() {
        val s = listOf(Segment(0, 2000, "Hello"), Segment(2500, 4000, "World"))
        assertEquals("Hello", Captions.at(s, 1999)); assertNull(Captions.at(s, 2200)); assertEquals("World", Captions.at(s, 2500))
        assertEquals("[0:00] Hello\n[0:02] World", Captions.transcript(s))
    }

    @Test fun guardianNamesWhatChanged() {
        val old = Snapshot(1, 80, listOf(AppSnap("a", "Flashlight", setOf("Camera"), emptySet()), AppSnap("b", "Old", emptySet(), emptySet())))
        val new = Snapshot(2, 70, listOf(AppSnap("a", "Flashlight", setOf("Camera", "Location"), setOf("accessibility")), AppSnap("c", "Notes", emptySet(), emptySet())))
        val texts = Guardian.diff(old, new).map { it.text }
        assertTrue("Flashlight can now see and control everything on screen" in texts)
        assertTrue("Flashlight can now use location" in texts)
        assertTrue("Notes was installed" in texts)
        assertTrue("Old was removed" in texts)
        assertTrue("Score went from 80 to 70" in texts)
        assertTrue(Guardian.diff(old, new).first().bad)
        assertEquals(new, Guardian.decode(Guardian.encode(new)))
        assertTrue(Guardian.diff(new, new).isEmpty())
    }

    @Test fun recipesOnlyChainWhatFits() {
        assertNull(RecipeRules.problem(listOf(Step.Shrink, Step.Mp3, Step.Transcribe)))
        assertTrue(RecipeRules.problem(listOf(Step.Mp3, Step.Shrink))!!.contains("can't follow"))
        assertTrue(RecipeRules.problem(emptyList()) != null)
        RecipeRules.builtIns.forEach { assertNull(it.name, RecipeRules.problem(it.steps)) }
        assertEquals(Kind.Image, RecipeRules.kindOf("image/png"))
        assertTrue(RecipeRules.isScreenRecording("DCIM/Screen recordings/"))
        assertTrue(RecipeRules.isScreenRecording("Movies/ScreenRecords/"))
        assertFalse(RecipeRules.isScreenRecording("DCIM/Camera/"))
        assertTrue(RecipeRules.forKind(RecipeRules.builtIns, Kind.Audio, setOf("audio")).any { it.id == "notes" })
    }

    @Test fun sendServerParsesAndSanitises() {
        val r = SendHttp.parseHead(listOf("PUT /abc123/upload?name=My%20File.mp4 HTTP/1.1", "Content-Length: 42", "Host: x"))!!
        assertEquals("PUT", r.method); assertEquals("/abc123/upload", r.path); assertEquals("My File.mp4", r.query["name"]); assertEquals("42", r.headers["content-length"])
        assertEquals("passwd", SendHttp.safeName("../../etc/passwd"))
        assertEquals("a.txt", SendHttp.safeName("C:\\x\\a.txt"))
        assertEquals("file", SendHttp.safeName("  "))
        assertEquals(6, SendHttp.newCode(java.util.Random(1)).length)
        assertEquals("&lt;b&gt;", SendHttp.escape("<b>"))
    }

    @Test fun usageTotalsHandleOpenAndCarriedOverSessions() {
        val ev = listOf(UseEvent("yt", 50, false), UseEvent("x", 100, true), UseEvent("x", 400, false), UseEvent("yt", 500, true))
        val t = UsageMath.totals(ev, from = 0, to = 1000)
        assertEquals(50L, t["yt"]!! - 500) // 0..50 carried over plus 500..1000 still open
        assertEquals(300L, t["x"])
        assertEquals("1h 5m", UsageMath.hm(65 * 60_000L)); assertEquals("9m", UsageMath.hm(9 * 60_000L))
    }
}

class CompanionTest {
    @org.junit.Test fun addressNamesAreUrlSafe() {
        org.junit.Assert.assertEquals("maurice-phone", app.ultimate.send.SendHttp.cleanCode(" Maurice Phone! "))
        org.junit.Assert.assertNull(app.ultimate.send.SendHttp.cleanCode("a!"))
        org.junit.Assert.assertEquals(32, app.ultimate.send.SendHttp.cleanCode("x".repeat(50))!!.length)
    }

    @org.junit.Test fun browserPathsStayInsideStorage() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        java.io.File(root, "DCIM").mkdirs()
        org.junit.Assert.assertEquals(java.io.File(root, "DCIM").canonicalFile, app.ultimate.send.SendApiPaths.resolve(root, "DCIM"))
        org.junit.Assert.assertEquals(root.canonicalFile, app.ultimate.send.SendApiPaths.resolve(root, ""))
        org.junit.Assert.assertNull(app.ultimate.send.SendApiPaths.resolve(root, "../../etc"))
        org.junit.Assert.assertNull(app.ultimate.send.SendApiPaths.resolve(root, "DCIM/../../x"))
    }

    @org.junit.Test fun chatLinesAlreadyKeptAreNotKeptAgain() {
        org.junit.Assert.assertEquals(listOf("Ana: new"), app.ultimate.inbox.LogRules.unseen(listOf("Ana: hi", "Ana: new"), listOf("Ana: hi")))
    }
}

class TodayTest {
    private fun facts(free: Long = 50, total: Long = 100, checkupAt: Long? = 0, update: String? = null, access: Boolean? = true, screen: Long? = 0) =
        app.ultimate.home.Facts(80, false, free, total, screen, 3, access, 80, checkupAt, update, 10 * 86_400_000L)

    @org.junit.Test fun nudgesPickWhatIsWorthDoingNow() {
        val all = setOf("cleanup", "checkup", "inbox", "screentime")
        val n = app.ultimate.home.Nudges.build(facts(free = 5, update = "1.23.0", access = false, screen = 5 * 3_600_000L), all).map { it.id }
        org.junit.Assert.assertEquals(listOf("update", "space", "checkup", "inbox", "screen"), n)
        org.junit.Assert.assertTrue(app.ultimate.home.Nudges.build(facts(checkupAt = 10 * 86_400_000L - 1000), all).isEmpty())
        org.junit.Assert.assertTrue(app.ultimate.home.Nudges.build(facts(free = 5, checkupAt = null), emptySet()).isEmpty())
    }

    @org.junit.Test fun browsersAreNamedPlainly() {
        val h = app.ultimate.send.SendHttp
        org.junit.Assert.assertEquals("Chrome on Windows", h.browserLabel("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"))
        org.junit.Assert.assertEquals("Edge on Windows", h.browserLabel("Mozilla/5.0 (Windows NT 10.0) Chrome/131.0 Safari/537.36 Edg/131.0"))
        org.junit.Assert.assertEquals("Safari on Mac", h.browserLabel("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 Version/17.5 Safari/605.1.15"))
        org.junit.Assert.assertEquals("A browser", h.browserLabel(null))
    }
}

class AdtsTest {
    @org.junit.Test fun headerEncodesLengthRateAndChannels() {
        val h = app.ultimate.send.Adts.header(100, 44100, 1)
        org.junit.Assert.assertEquals(7, h.size)
        org.junit.Assert.assertEquals(0xFF, h[0].toInt() and 0xFF)
        org.junit.Assert.assertEquals(0xF1, h[1].toInt() and 0xFF)
        org.junit.Assert.assertEquals(4, (h[2].toInt() shr 2) and 0xF) // 44.1 kHz index
        val len = ((h[3].toInt() and 3) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xFF) shr 5)
        org.junit.Assert.assertEquals(107, len)
        org.junit.Assert.assertEquals(1, ((h[2].toInt() and 1) shl 2) or ((h[3].toInt() and 0xFF) shr 6))
    }
}
