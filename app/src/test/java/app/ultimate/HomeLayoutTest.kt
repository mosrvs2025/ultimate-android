package app.ultimate

import app.ultimate.home.HomeLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {
    private val all = listOf("studio", "squeeze", "audio", "recorder", "files", "storage", "cleanup", "terminal", "orb")
    private val day = 86_400_000L
    private val now = 100 * day

    @Test fun newInstallShowsTheStarters() {
        assertEquals(listOf("studio", "squeeze", "files", "storage"), HomeLayout.favorites(all, { 0 }, { 0 }, emptySet(), now))
    }

    @Test fun mostOpenedComeFirstThenStarters() {
        val opens = mapOf("terminal" to 9, "audio" to 4)
        val last = mapOf("terminal" to now - day, "audio" to now - day)
        assertEquals(listOf("terminal", "audio", "studio", "squeeze"), HomeLayout.favorites(all, { opens[it] ?: 0 }, { last[it] ?: 0 }, emptySet(), now))
    }

    @Test fun aRecentOpenOutweighsAnOldHabit() {
        val opens = mapOf("recorder" to 6, "audio" to 5)
        val last = mapOf("recorder" to now - 60 * day, "audio" to now - day)
        assertEquals("audio", HomeLayout.favorites(all, { opens[it] ?: 0 }, { last[it] ?: 0 }, emptySet(), now).first())
    }

    @Test fun runningToolsAreAlwaysShownFirstWithoutRepeats() {
        val opens = mapOf("terminal" to 9)
        val r = HomeLayout.favorites(all, { opens[it] ?: 0 }, { now }, setOf("cleanup"), now)
        assertEquals(listOf("cleanup", "terminal", "studio", "squeeze"), r)
        assertEquals(r.size, r.toSet().size)
    }

    @Test fun toolsMissingInAnEditionAreSkipped() {
        assertEquals(listOf("squeeze", "audio"), HomeLayout.favorites(listOf("squeeze", "audio"), { 0 }, { 0 }, emptySet(), now))
    }
}
