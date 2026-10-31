package app.ultimate

import app.ultimate.home.AboutInfo
import app.ultimate.home.Changelog
import app.ultimate.insights.Crash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutTest {
    @Test fun editionsHaveFriendlyNames() {
        assertEquals("Ultimate+", AboutInfo.editionName("ultimateplus"))
        assertEquals("Squeeze", AboutInfo.editionName("squeeze"))
        assertEquals("Newthing", AboutInfo.editionName("newthing"))
        assertEquals("1.31.0 (build 36)", AboutInfo.versionLine("1.31.0", 36))
    }

    @Test fun aCrashReadsOnOneLine() {
        val c = Crash(0, "1.30.0", "java.lang.NullPointerException", "Cannot read field\nmore", listOf("TerminalTool.prepare:90", "x"))
        assertEquals("2 days ago · NullPointerException: Cannot read field @ TerminalTool.prepare:90", AboutInfo.crashLine(c, "2 days ago"))
        assertEquals("now · IllegalStateException", AboutInfo.crashLine(Crash(0, "v", "a.b.IllegalStateException", null, emptyList()), "now"))
    }

    @Test fun detailsCanBePastedIntoAMessage() {
        val t = AboutInfo.details("Ultimate+", "1.30.1", 35, "release", "14", 34, "samsung SM-S918U", listOf("Studio", "Rules"), true, listOf("1 day ago · Boom"))
        assertTrue(t.startsWith("Ultimate+ 1.30.1 (build 35, release)"))
        assertTrue(t.contains("Android 14 (API 34) on samsung SM-S918U"))
        assertTrue(t.contains("Internet permission: yes") && t.contains("Tools (2): Studio, Rules") && t.contains("  1 day ago · Boom"))
        assertTrue(AboutInfo.details("U", "1", 1, "release", "10", 29, "d", emptyList(), false, emptyList()).contains("Recent crashes: none"))
        assertFalse(t.endsWith("\n"))
    }

    @Test fun theChangelogStartsAtTheCurrentVersion() {
        assertEquals(listOf("1.30.0", "1.29.0"), Changelog.recent("1.30.0", 2).map { it.version })
        assertEquals("1.34.0", Changelog.entries.first().version)
        assertEquals(Changelog.entries.first().version, Changelog.recent("9.9.9", 1).single().version) // an unknown version still shows the latest news
        assertTrue(Changelog.entries.map { it.version }.toSet().size == Changelog.entries.size)
    }
}
