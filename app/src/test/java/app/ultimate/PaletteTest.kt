package app.ultimate

import app.ultimate.search.Hit
import app.ultimate.search.Kind
import app.ultimate.search.PaletteMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    @Test fun closerMatchesScoreHigher() {
        val q = "pen"
        val s = listOf("Pen", "Penny Lane", "Open settings", "Spending").map { PaletteMath.score(it, q) }
        assertTrue(s.toString(), s[0] > s[1] && s[1] > s[2] && s[2] == s[3] && s[3] > 0) // mid-word matches tie
        assertEquals(0, PaletteMath.score("Echo", "penny"))
        assertTrue(PaletteMath.score("Notification history", "nh") > 0)
        assertTrue("every word must appear", PaletteMath.score("Mom: dinner at 7", "dinner mom") > 0)
    }

    @Test fun penny() {
        // Searching a name: the contact leads, then her notifications, apps don't match.
        fun hit(k: Kind, t: String, score: Int) = Hit(k, t, "", score, "$k:$t") {}
        val (top, groups) = PaletteMath.arrange(listOf(
            hit(Kind.Message, "Penny: see you at 6", 70), hit(Kind.Contact, "Penny", 100 + 6), hit(Kind.Copied, "penny's address", 50), hit(Kind.App, "Spotify", 0),
        ))
        assertEquals("Penny", top!!.title)
        assertEquals(listOf(Kind.Message, Kind.Copied), groups.map { it.first })
    }

    @Test fun weakResultsDontClaimTheTopSpotAndKindsAreCapped() {
        val (top, groups) = PaletteMath.arrange(List(9) { Hit(Kind.File, "f$it", "", 30, "f$it") {} }, perKind = 4)
        assertNull(top)
        assertEquals(4, groups.single().second.size)
    }

    @Test fun settingsAreFoundByEverydayWords() {
        val hits = PaletteMath.SETTINGS.filter { (n, _, w) -> PaletteMath.best("brightness", n, *w.toTypedArray()) > 0 }.map { it.first }
        assertEquals(listOf("Display and brightness"), hits)
    }
}
