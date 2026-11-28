package app.ultimate.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home styles' sums (pages, honeycomb rows, the dial's ring, the tile wall, the prompt), and how a style is stored and shared. */
class HomeStyleMathTest {
    private fun apps(n: Int) = (1..n).map { HomeApp("p$it", "App %02d".format(it)) }

    @Test fun pagesNeedWholePagesAndAnEmptyPhoneStillHasOne() {
        assertEquals(1, StyleMath.pages(0, 20)); assertEquals(1, StyleMath.pages(20, 20)); assertEquals(2, StyleMath.pages(21, 20)); assertEquals(5, StyleMath.pages(100, 20))
        assertEquals(1, StyleMath.pages(7, 0))
        assertEquals(apps(20).map { it.pkg }, StyleMath.pageSlice(apps(45), 0, 20).map { it.pkg })
        assertEquals(5, StyleMath.pageSlice(apps(45), 2, 20).size)
        assertEquals(emptyList<HomeApp>(), StyleMath.pageSlice(apps(45), 3, 20)); assertEquals(emptyList<HomeApp>(), StyleMath.pageSlice(apps(45), -1, 20))
        // Every app is on exactly one page.
        val all = (0 until StyleMath.pages(45, 12)).flatMap { StyleMath.pageSlice(apps(45), it, 12) }
        assertEquals(apps(45), all)
    }

    @Test fun theGridFitsWholeCellsWithinLimits() {
        assertEquals(4 to 5, StyleMath.gridFit(320f, 480f, 76f, 92f))
        assertEquals("never fewer than three columns or two rows", 3 to 2, StyleMath.gridFit(100f, 50f, 76f, 92f))
        assertEquals("never more than six columns", 6, StyleMath.gridFit(1200f, 480f, 76f, 92f).first)
    }

    @Test fun theHoneycombKeepsHalfACellForTheOffsetRowsAndEveryAppIsInOneRow() {
        assertEquals(4, StyleMath.hiveCols(320f, 67f))   // 320 - 33.5 = 286.5 / 67
        assertEquals(2, StyleMath.hiveCols(60f, 67f)); assertEquals(7, StyleMath.hiveCols(2000f, 67f))
        val rows = StyleMath.hiveRows(apps(10), 4)
        assertEquals(listOf(4, 4, 2), rows.map { it.size }); assertEquals(apps(10), rows.flatten())
        assertEquals(emptyList<List<HomeApp>>(), StyleMath.hiveRows(apps(3), 0))
    }

    @Test fun theDialPutsAppsOnAnArcAndTurnsWithTheFinger() {
        assertEquals(180f, StyleMath.dialAngle(0, 0f)); assertEquals(192f, StyleMath.dialAngle(1, 0f)); assertEquals(180f, StyleMath.dialAngle(1, 12f))
        assertTrue(StyleMath.dialVisible(225f)); assertTrue(StyleMath.dialVisible(180f)); assertTrue(StyleMath.dialVisible(270f)); assertFalse(StyleMath.dialVisible(100f)); assertFalse(StyleMath.dialVisible(276f)); assertFalse("only the arc itself shows, so nothing is cut at the ends", StyleMath.dialVisible(172f))
        // Turned all the way, the last app reaches the start of the arc; it can't be turned past either end.
        assertEquals(0f, StyleMath.dialClamp(-40f, 30)); assertEquals(29 * 12f, StyleMath.dialClamp(9_999f, 30)); assertEquals(0f, StyleMath.dialClamp(5f, 0))
        // The selected app is the one in the middle of the arc (45 degrees from the start is 3.75 steps).
        assertEquals(4, StyleMath.dialSelected(0f, 30)); assertEquals(5, StyleMath.dialSelected(12f, 30)); assertEquals(29, StyleMath.dialSelected(9_999f, 30)); assertEquals(-1, StyleMath.dialSelected(0f, 0))
        assertEquals("a short list selects its last app, not a missing one", 2, StyleMath.dialSelected(0f, 3))
    }

    @Test fun fingerAnglesAreTakenFromTheCornerAndTurnTheShortWay() {
        assertEquals(180f, StyleMath.angleOf(0f, 100f, 100f, 100f), 0.01f)   // left of the corner
        assertEquals(270f, StyleMath.angleOf(100f, 0f, 100f, 100f), 0.01f)   // above the corner
        assertEquals(30f, StyleMath.turn(190f, 220f), 0.01f); assertEquals(-30f, StyleMath.turn(220f, 190f), 0.01f)
        assertEquals("across the 0/360 seam", 20f, StyleMath.turn(350f, 10f), 0.01f)
    }

    @Test fun theTileWallHasARhythm() {
        assertEquals(emptyList<StyleMath.Tile>(), StyleMath.tilePlan(0))
        assertEquals(listOf(StyleMath.Tile.Large), StyleMath.tilePlan(1))
        assertEquals(listOf("Large", "Small", "Small", "Wide", "Small", "Small", "Wide", "Small"), StyleMath.tilePlan(8).map { it.name })
        assertTrue(StyleMath.tilePlan(8).all { it.span in 1..2 })
    }

    @Test fun thePromptListsFavouritesThenMatchesAndEnterOpensThePickedOrFirst() {
        val all = listOf("Gmail", "Maps", "Messages", "Chrome", "Calendar").mapIndexed { i, n -> HomeApp("p$i", n) }
        val favs = all.take(2)
        assertEquals(favs, StyleMath.promptList(all, favs, ""))
        assertEquals(listOf("Maps", "Messages"), StyleMath.promptList(all, favs, "m").map { it.label }.filter { it in setOf("Maps", "Messages") })
        assertTrue(StyleMath.promptList(all, favs, "zzz").isEmpty())
        assertEquals("a bare number keeps the numbered favourites in view", favs, StyleMath.promptList(all, favs, "2"))
        assertNull("Enter on an empty line does nothing", StyleMath.promptEnter(all, favs, ""))
        assertEquals("a bare number picks that line", favs[1], StyleMath.promptEnter(all, favs, "2"))
        assertNull("a number past the list opens nothing", StyleMath.promptPick("9", favs))
        assertNotNull(StyleMath.promptEnter(all, favs, "chr"))
        assertEquals("Chrome", StyleMath.promptEnter(all, favs, "chr")!!.label)
    }

    @Test fun aHomeStyleIsAPartOfALookSharedAndStored() {
        assertEquals("nobody's launcher changes", HomeStyle.Lists, LauncherSettings().homeStyle)
        assertEquals(HomeStyle.Lists, Looks.aurora.apply(LauncherSettings(homeStyle = HomeStyle.Hive)).homeStyle)   // choosing a list look puts the lists back
        val hive = Looks.hive.apply(LauncherSettings())
        assertEquals(HomeStyle.Hive, hive.homeStyle); assertEquals(Looks.hive, Looks.matching(hive))
        assertNull("another style is another look", Looks.matching(hive.copy(homeStyle = HomeStyle.Pages)))
        val code = LookCode.encode(hive)
        assertTrue(code, code.endsWith("|Hive")); assertEquals(HomeStyle.Hive, LookCode.apply(code, LauncherSettings())!!.homeStyle)
        // A code from before home styles (nine parts) still reads, and means the lists; an unknown style from a later version keeps what was there.
        assertEquals(HomeStyle.Lists, LookCode.apply("ULOOK1:Aurora|Aurora|Default|Day|List|Liquid|AsIs|5|true", LauncherSettings(homeStyle = HomeStyle.Dial))!!.homeStyle)
        assertEquals(HomeStyle.Dial, LookCode.apply("ULOOK1:Aurora|Aurora|Default|Day|List|Liquid|AsIs|5|true|Hologram", LauncherSettings(homeStyle = HomeStyle.Dial))!!.homeStyle)
        val stored = SettingsCodec.extras(hive)
        assertEquals(HomeStyle.Hive, SettingsCodec.withExtras(LauncherSettings()) { stored[it] }.homeStyle)
        assertEquals(HomeStyle.Lists, SettingsCodec.withExtras(LauncherSettings()) { null }.homeStyle)
    }

    @Test fun everyNewStyleHasALookAndEveryStyleIsDescribed() {
        listOf(Looks.pages, Looks.hive, Looks.dial, Looks.metro, Looks.prompt).forEach { p ->
            assertTrue(p.name, p.apply(LauncherSettings()).homeStyle != HomeStyle.Lists)
            assertEquals("a styled look has no letter rail to draw", RailStyle.Hidden, p.apply(LauncherSettings()).rail)
        }
        assertEquals(HomeStyle.entries.toSet(), Looks.all.map { it.apply(LauncherSettings()).homeStyle }.toSet())
        assertTrue(HomeStyle.entries.all { it.label.isNotBlank() && it.blurb.isNotBlank() })
    }
}
