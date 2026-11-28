package app.ultimate.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The look studio: what counts as a pending change, what Apply keeps and leaves alone, and the shuffle. */
class LookStudioTest {
    private fun contrast(a: Color, b: Color): Double { val l1 = maxOf(a.luminance(), b.luminance()).toDouble(); val l2 = minOf(a.luminance(), b.luminance()).toDouble(); return (l1 + 0.05) / (l2 + 0.05) }

    @Test fun nothingIsPendingUntilTheDraftDiffersFromWhatIsWorn() {
        val base = LauncherSettings()
        assertFalse(LookStudio.pending(null, base))
        assertFalse("the same settings are not a change", LookStudio.pending(base.copy(), base))
        assertFalse("rules and saved looks act at once, so they are never a pending change", LookStudio.pending(base.copy(rules = listOf(LookRule(When.Focusing, "zen")), slots = listOf(LookSlot("Mine", LookCode.encode(base)))), base))
        assertTrue(LookStudio.pending(base.copy(face = Face.Mono), base))
        assertTrue(LookStudio.pending(base.copy(homeStyle = HomeStyle.Hive), base))
        assertTrue("the other pages' choices count too", LookStudio.pending(base.copy(showDate = false), base))
        assertTrue(LookStudio.pending(base.copy(hidden = setOf("x")), base))
        assertTrue(LookStudio.pending(base.copy(swipeUp = GestureAction.Notifications), base))
        assertTrue(LookStudio.pending(base.copy(searchWeb = true, searchEngine = SearchEngine.Brave), base))
    }

    @Test fun applyingTakesTheDraftButKeepsTheRulesAndSavedLooksOfTheRealSettings() {
        val base = LauncherSettings(hidden = setOf("com.secret"), swipeUp = GestureAction.Notifications, rules = listOf(LookRule(When.Focusing, "zen")), slots = listOf(LookSlot("Mine", LookCode.encode(LauncherSettings()))))
        // The draft was taken before a rule was added and a look saved, so it holds the older ones.
        val draft = Looks.terminal.apply(LauncherSettings(hidden = setOf("com.other"), swipeUp = GestureAction.Search, favouritesShown = 3))
        val applied = LookStudio.commit(draft, base)
        assertEquals("the look in the draft is the look worn", Looks.terminal, Looks.matching(applied))
        assertEquals("choices from the draft are taken", setOf("com.other"), applied.hidden); assertEquals(GestureAction.Search, applied.swipeUp)
        assertEquals("the real rules and saved looks are kept", base.rules, applied.rules); assertEquals(base.slots, applied.slots)
    }

    @Test fun aDraftIsClampedLikeAnySetting() {
        assertEquals(LauncherSettings.MAX_FAVS, LookStudio.commit(LauncherSettings(favouritesShown = 50), LauncherSettings()).favouritesShown)
    }

    @Test fun aLookIsNamedOrCalledAMix() {
        assertEquals("Terminal", LookStudio.name(Looks.terminal.apply(LauncherSettings())))
        assertEquals("Your own mix", LookStudio.name(Looks.terminal.apply(LauncherSettings()).copy(face = Face.Serif)))
    }

    @Test fun aLightBackgroundOnlyOffersAccentsThatCanBeReadOnIt() {
        val onPaper = LookStudio.readableAccents(Backdrop.Paper)
        assertTrue(Accent.Ink in onPaper); assertFalse(Accent.Mono in onPaper); assertFalse(Accent.Phosphor in onPaper)
        assertTrue("a dark background keeps the bright ones", Accent.Aurora in LookStudio.readableAccents(Backdrop.Black))
        assertTrue(LookStudio.readableAccents(Backdrop.Black).size > onPaper.size)
        assertTrue("every background offers something", Backdrop.entries.all { LookStudio.readableAccents(it).isNotEmpty() })
    }

    @Test fun shuffleAlwaysChangesTheLookAndIsAlwaysReadable() {
        var base = LauncherSettings(hidden = setOf("keep"), rules = listOf(LookRule(When.Focusing, "zen")))
        val seen = mutableSetOf<String>()
        for (seed in 0 until 400) {
            val next = LookStudio.shuffle(base, Random(seed))
            assertNotEquals("seed $seed", LookCode.encode(base), LookCode.encode(next))
            assertEquals(setOf("keep"), next.hidden); assertEquals(base.rules, next.rules)
            if (next.accent != Accent.Wallpaper && next.backdrop != Backdrop.Wallpaper) {
                assertTrue("seed $seed ${next.accent} on ${next.backdrop}", contrast(next.accent.a, next.backdrop.colors[1]) >= 3.0 && contrast(next.accent.b, next.backdrop.colors[1]) >= 3.0)
            }
            if (next.homeStyle != HomeStyle.Lists) assertEquals("a styled home has no letter rail", RailStyle.Hidden, next.rail)
            assertNotNull("what it makes can be shared", LookCode.apply(LookCode.encode(next), LauncherSettings()))
            assertEquals(next.clockLayout != ClockLayout.Hidden, true)
            seen += LookCode.encode(next)
            if (seed % 50 == 0) base = next
        }
        assertTrue("it really does vary: ${seen.size}", seen.size > 300)
    }

    @Test fun theSameDiceGiveTheSameLook() {
        val base = LauncherSettings()
        assertEquals(LookCode.encode(LookStudio.shuffle(base, Random(7))), LookCode.encode(LookStudio.shuffle(base, Random(7))))
    }
}
