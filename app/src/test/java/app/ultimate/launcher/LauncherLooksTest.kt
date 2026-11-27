package app.ultimate.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/** The launcher's looks: that each preset is really different, that the default is the look it always had, and that a look survives storage and sharing. */
class LauncherLooksTest {
    private fun contrast(a: Color, b: Color): Double { val l1 = maxOf(a.luminance(), b.luminance()).toDouble(); val l2 = minOf(a.luminance(), b.luminance()).toDouble(); return (l1 + 0.05) / (l2 + 0.05) }

    @Test fun theDefaultSettingsAreTheAuroraLookSoNobodysLauncherChanges() {
        assertEquals(Looks.aurora, Looks.matching(LauncherSettings()))
        assertEquals("the default tokens are what was hard-coded before", LookTokens.Default, LauncherSettings().tokens())
        assertEquals(ClockLayout.Day, LauncherSettings().clockLayout); assertEquals(FavLayout.List, LauncherSettings().favLayout); assertEquals(RailStyle.Liquid, LauncherSettings().rail)
    }

    @Test fun everyPresetIsDifferentAndIsRecognisedOnceApplied() {
        val applied = Looks.all.map { it.apply(LauncherSettings()) }
        assertEquals("no two looks share all their parts", Looks.all.size, applied.map { listOf(it.accent, it.backdrop, it.face, it.clockLayout, it.favLayout, it.rail, it.iconShape) }.toSet().size)
        Looks.all.forEach { p -> assertEquals(p, Looks.matching(p.apply(LauncherSettings(accent = Accent.Rose, swipeUp = GestureAction.None)))) }
        assertEquals(Looks.all.map { it.id }.toSet().size, Looks.all.size)
        assertTrue(Looks.all.all { it.name.isNotBlank() && it.blurb.isNotBlank() })
    }

    @Test fun changingOnePartMakesItAMixOfYourOwn() {
        val terminal = Looks.terminal.apply(LauncherSettings())
        assertNull(Looks.matching(terminal.copy(face = Face.Serif)))
        assertNull(Looks.matching(terminal.copy(rail = RailStyle.Liquid)))
        // How many favourites show is not part of what makes a look.
        assertEquals(Looks.terminal, Looks.matching(terminal.copy(favouritesShown = 3)))
    }

    @Test fun eachLooksPartsAreWhatItsNameSays() {
        val t = Looks.terminal.apply(LauncherSettings()); val p = Looks.paper.apply(LauncherSettings()); val z = Looks.zen.apply(LauncherSettings())
        assertEquals(Face.Mono, t.face); assertEquals(ClockLayout.Terminal, t.clockLayout); assertEquals(FavLayout.Numbered, t.favLayout); assertFalse(t.drawerShowIcons)
        assertTrue(p.backdrop.light); assertEquals(Face.Serif, p.face); assertEquals(ClockLayout.Editorial, p.clockLayout)
        assertEquals(RailStyle.Hidden, z.rail); assertEquals(3, z.favouritesShown); assertEquals(FavLayout.Centered, z.favLayout)
        assertEquals(FavLayout.Grid, Looks.tiles.apply(LauncherSettings()).favLayout); assertEquals(FavLayout.Pills, Looks.dusk.apply(LauncherSettings()).favLayout)
    }

    @Test fun textStaysReadableOnEveryBackground() {
        Backdrop.entries.filter { it != Backdrop.Wallpaper }.forEach { b ->
            val bg = b.colors.map { it.luminance() }.average().toFloat()
            val mid = Color(bg, bg, bg)
            assertTrue("${b.label}: main text contrast ${contrast(b.fg, mid)}", contrast(b.fg, mid) >= 7.0)
            assertTrue("${b.label}: quiet text contrast ${contrast(b.dim, mid)}", contrast(b.dim, mid) >= 3.0)
            assertEquals("only a light background has dark text", b.light, b.fg.luminance() < 0.2f)
            assertTrue("${b.label} is solid", b.colors.all { it.alpha == 1f })
        }
        assertTrue(Backdrop.Paper.light); assertFalse(Backdrop.Aurora.light); assertFalse(Backdrop.Terminal.light)
    }

    @Test fun searchIsReadableInEveryLookEvenThoughItsPanelIsAlwaysDark() {
        Looks.all.forEach { p ->
            val s = p.apply(LauncherSettings()); val t = s.tokens().onSheet()
            val panel = s.backdrop.sheet.copy(alpha = 1f)
            assertTrue("${p.name}: results on the search panel, contrast ${contrast(t.fg, panel)}", contrast(t.fg, panel) >= 7.0)
            assertTrue("${p.name}: quiet text on the search panel, contrast ${contrast(t.dim, panel)}", contrast(t.dim, panel) >= 4.5)
        }
        assertEquals("a dark look is already right", Looks.terminal.apply(LauncherSettings()).tokens(), Looks.terminal.apply(LauncherSettings()).tokens().onSheet())
        assertFalse(Looks.paper.apply(LauncherSettings()).tokens().onSheet().light)
    }

    @Test fun everyLooksSettingsSurviveBeingStoredAndReadBack() {
        Looks.all.forEach { p ->
            val s = p.apply(LauncherSettings())
            val stored = SettingsCodec.extras(s)
            assertEquals(p.name, s, SettingsCodec.withExtras(s.copy(face = Face.Default, clockLayout = ClockLayout.Day, favLayout = FavLayout.List, rail = RailStyle.Liquid, iconShape = IconShape.AsIs), stored::get))
        }
        val odd = mapOf("face" to "Comic", "clockLayout" to "", "favLayout" to "Carousel", "rail" to "x", "iconShape" to "Star")
        assertEquals("a part from a later version falls back to the default", LauncherSettings(), SettingsCodec.withExtras(LauncherSettings(), odd::get))
    }

    @Test fun aLookIsOneLineOfTextAndComesBackExactly() {
        Looks.all.forEach { p ->
            val s = p.apply(LauncherSettings(swipeUp = GestureAction.Notifications, hidden = setOf("secret.app")))
            val code = LookCode.encode(s)
            assertTrue(code, code.startsWith("ULOOK1:") && '\n' !in code)
            val back = LookCode.apply(code, LauncherSettings())!!
            assertEquals(Looks.matching(s), Looks.matching(back)); assertEquals(s.favouritesShown, back.favouritesShown); assertEquals(s.drawerShowIcons, back.drawerShowIcons)
        }
        assertFalse("only the look travels: no apps and no gestures", LookCode.encode(Looks.terminal.apply(LauncherSettings(hidden = setOf("secret.app")))).contains("secret"))
        // Applying a look leaves everything else as it was.
        val mine = LauncherSettings(swipeUp = GestureAction.Notifications, hidden = setOf("a"), clock = ClockStyle.H24)
        val got = LookCode.apply(LookCode.encode(Looks.paper.apply(LauncherSettings())), mine)!!
        assertEquals(GestureAction.Notifications, got.swipeUp); assertEquals(setOf("a"), got.hidden); assertEquals(ClockStyle.H24, got.clock); assertEquals(Face.Serif, got.face)
    }

    @Test fun codesThatAreNotLooksAreRefusedAndOddOnesAreHandled() {
        val base = LauncherSettings()
        assertNull(LookCode.apply("", base)); assertNull(LookCode.apply("hello", base)); assertNull(LookCode.apply("ULOOK1:Aurora|Aurora", base))
        assertNull("the number of favourites must be a number", LookCode.apply("ULOOK1:Aurora|Aurora|Default|Day|List|Liquid|AsIs|many|true", base))
        assertNotNull(LookCode.apply("  ULOOK1:Aurora|Aurora|Default|Day|List|Liquid|AsIs|5|true \n", base))
        val future = LookCode.apply("ULOOK1:Neon|Aurora|Comic|Day|Carousel|Liquid|Star|99|true", LauncherSettings(accent = Accent.Rose))!!
        assertEquals("unknown parts keep what was there", Accent.Rose, future.accent); assertEquals(Face.Default, future.face)
        assertEquals("the favourites count is held to what the launcher allows", LauncherSettings.MAX_FAVS, future.favouritesShown)
    }

    @Test fun theThemesPageIsFoundByTheNewWords() {
        listOf("look", "terminal", "paper", "typeface", "share").forEach { assertEquals(it, listOf(SettingsPage.Colours), SettingsPage.matching(it)) }
        // The rail has settings in two places: its style (Themes) and its ticks (Home screen).
        assertTrue(SettingsPage.Colours in SettingsPage.matching("rail") && SettingsPage.Home in SettingsPage.matching("rail"))
        assertEquals(listOf(SettingsPage.Colours), SettingsPage.matching("Colour"))
        assertNotEquals(listOf(SettingsPage.Colours), SettingsPage.matching("gesture"))
    }

    @Test fun theClockIsFormattedInTheStyleAskedFor() {
        val at = Instant.parse("2026-11-26T14:05:00Z").toEpochMilli()
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("14:05", ClockText.time(at, ClockStyle.H24, system24 = false, locale = Locale.US, tz = utc))
        assertEquals("2:05", ClockText.time(at, ClockStyle.H12, system24 = true, locale = Locale.US, tz = utc))
        assertEquals("the phone's own setting decides", "14:05", ClockText.time(at, ClockStyle.System, system24 = true, locale = Locale.US, tz = utc))
        val day = DateTimeFormatter.ofPattern("EEEE", Locale.US).format(Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC))
        assertEquals(day, ClockText.format("EEEE", at, Locale.US, utc))
        assertEquals("26", ClockText.format("d", at, Locale.US, utc))
    }
}
