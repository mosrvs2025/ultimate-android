package app.ultimate.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherMathTest {
    private val apps = listOf("YouTube", "YT Music", "Éclair Notes", "1Password", "Gmail", "Google Photos", "Chrome", "Camera", "WhatsApp").map { HomeApp(it.lowercase(), it) }

    @Test fun appsFileUnderTheirFirstLetterIgnoringAccentsAndSymbols() {
        assertEquals('E', LauncherMath.letterOf("Éclair Notes"))
        assertEquals('#', LauncherMath.letterOf("1Password"))
        assertEquals('#', LauncherMath.letterOf("   "))
        assertEquals('Y', LauncherMath.letterOf("  yt music"))
    }

    @Test fun theRailHasOnlyLettersWithAppsInOrder() {
        assertEquals("#CEGWY", LauncherMath.letters(apps).joinToString(""))
        assertEquals(listOf("Chrome", "Camera").sorted(), LauncherMath.appsFor(apps, 'C').map { it.label }.sorted())
        assertEquals(listOf("YouTube", "YT Music"), LauncherMath.appsFor(apps, 'Y').map { it.label })
    }

    @Test fun aFingerMapsToTheLetterUnderItAndStaysOnTheRail() {
        // Six letters spread over 500 px from y = 100: one every 100 px.
        assertEquals(0f, LauncherMath.position(40f, 100f, 500f, 6), 0f)          // above the rail: the first letter
        assertEquals(2.5f, LauncherMath.position(350f, 100f, 500f, 6), 1e-4f)
        assertEquals(5f, LauncherMath.position(900f, 100f, 500f, 6), 0f)          // below: the last
        assertEquals(3, LauncherMath.index(2.5f, 6))
    }

    @Test fun theWaveIsTallestUnderTheFingerAndFallsAwaySmoothly() {
        val at = 10f
        assertEquals(1f, LauncherMath.bulge(10, at), 1e-6f)
        val b = (0..20).map { LauncherMath.bulge(it, at) }
        for (i in 1..10) assertTrue("rises toward the finger", b[i] > b[i - 1])
        for (i in 11..20) assertTrue("falls after it", b[i] < b[i - 1])
        assertTrue("letters far away barely move", LauncherMath.bulge(0, at) < 0.01f)
        assertEquals(LauncherMath.bulge(7, at), LauncherMath.bulge(13, at), 1e-6f)  // symmetric
    }

    @Test fun searchMatchesTheStartOfAnyWord() {
        assertEquals(listOf("YT Music"), LauncherMath.search(apps, "yt mu").map { it.label })
        assertEquals(listOf("Google Photos"), LauncherMath.search(apps, "pho").map { it.label })
        assertEquals("Gmail", LauncherMath.search(apps, "g").first().label.let { if (it == "Gmail" || it == "Google Photos") "Gmail" else it })
        assertEquals(listOf("Éclair Notes"), LauncherMath.search(apps, "ecl").map { it.label })
        assertTrue(LauncherMath.search(apps, "  ").isEmpty())
    }

    @Test fun defaultFavouritesPreferWhatYouUseThenThePhonesOwnApps() {
        val installed = setOf("a", "b", "c", "dialer", "sms", "me")
        assertEquals(listOf("c", "a", "dialer", "sms"), LauncherMath.defaultFavourites(listOf("a" to 50L, "c" to 90L, "me" to 999L, "gone" to 500L), listOf("dialer", "sms", "missing"), installed, "me"))
        assertEquals(listOf("dialer", "sms"), LauncherMath.defaultFavourites(emptyList(), listOf("dialer", "sms"), installed, "me"))
    }

    @Test fun `the rail stays on a letter until the finger is clearly past it`() {
        // Sliding down across the boundary between letter 3 and 4 and wobbling back: plain rounding flips at 3.5, sticky waits for 4.2.
        assertEquals(4, LauncherMath.index(3.6f, 10)); assertEquals(3, LauncherMath.indexSticky(3.6f, 3, 10))
        assertEquals(3, LauncherMath.indexSticky(3.69f, 3, 10)); assertEquals(4, LauncherMath.indexSticky(3.75f, 3, 10))
        // Going back up works the same way, so it never traps the finger.
        assertEquals(4, LauncherMath.indexSticky(3.4f, 4, 10)); assertEquals(3, LauncherMath.indexSticky(3.25f, 4, 10))
        // A fast flick jumps straight to the right letter, and the first touch has nothing to stick to.
        assertEquals(9, LauncherMath.indexSticky(8.8f, 2, 10)); assertEquals(5, LauncherMath.indexSticky(5.4f, null, 10))
        // A letter list that shrank under the finger can't leave it on a letter that no longer exists.
        assertEquals(1, LauncherMath.indexSticky(1.0f, 7, 2))
        // A wobbling slide never changes letter more than the finger really moved: 40 jittery samples around the boundary change it once.
        var held: Int? = null; var changes = 0
        for (k in 0 until 40) { val pos = 3.5f + (if (k % 2 == 0) 0.18f else -0.18f) + k * 0.01f; val now = LauncherMath.indexSticky(pos, held, 10); if (held != null && now != held) changes++; held = now }
        assertTrue("changed $changes times", changes <= 1)
    }
}

class LauncherSettingsTest {
    private val utc = java.util.TimeZone.getTimeZone("UTC")
    private val t = java.time.Instant.parse("2026-11-16T21:05:00Z").toEpochMilli()

    @org.junit.Test fun `the clock follows the chosen style`() {
        val us = java.util.Locale.US
        org.junit.Assert.assertEquals("21:05 · 16 November", LauncherMath.clockLine(t, ClockStyle.H24, true, false, us, utc))
        org.junit.Assert.assertEquals("9:05 PM · 16 November", LauncherMath.clockLine(t, ClockStyle.H12, true, true, us, utc))
        org.junit.Assert.assertEquals("9:05 PM", LauncherMath.clockLine(t, ClockStyle.System, false, false, us, utc))
        org.junit.Assert.assertEquals("21:05", LauncherMath.clockLine(t, ClockStyle.System, false, true, us, utc))
    }

    @org.junit.Test fun `hidden apps are left out and nothing hidden changes nothing`() {
        val apps = listOf(HomeApp("a", "Alpha"), HomeApp("b", "Beta"), HomeApp("c", "Gamma"))
        org.junit.Assert.assertEquals(listOf("a", "c"), LauncherMath.visible(apps, setOf("b")).map { it.pkg })
        org.junit.Assert.assertSame(apps, LauncherMath.visible(apps, emptySet()))
        org.junit.Assert.assertEquals(listOf("a", "b", "c"), LauncherMath.visible(apps, setOf("zzz")).map { it.pkg })
    }

    @org.junit.Test fun `settings come back from saved text, forgiving of anything odd`() {
        val s = SettingsCodec.decode("Sunset", "Black", "H24", false, false, 6, false, "x.y,,b.c")
        org.junit.Assert.assertEquals(LauncherSettings(Accent.Sunset, Backdrop.Black, ClockStyle.H24, false, false, 6, false, setOf("x.y", "b.c")), s)
        val odd = SettingsCodec.decode("Neon", null, "weird", true, true, 99, true, null)
        org.junit.Assert.assertEquals(Accent.Aurora, odd.accent); org.junit.Assert.assertEquals(ClockStyle.System, odd.clock)
        org.junit.Assert.assertEquals(LauncherSettings.MAX_FAVS, odd.favouritesShown); org.junit.Assert.assertEquals(emptySet<String>(), odd.hidden)
        org.junit.Assert.assertEquals(LauncherSettings.MIN_FAVS, SettingsCodec.decode(null, null, null, true, true, -3, true, null).favouritesShown)
        org.junit.Assert.assertEquals("a,b", SettingsCodec.encodeHidden(setOf("b", "a", "", "c,d")))   // blanks and anything with a comma in it are dropped
    }

    @org.junit.Test fun `the defaults are how the launcher looked before settings`() {
        val d = LauncherSettings()
        org.junit.Assert.assertEquals(Accent.Aurora, d.accent); org.junit.Assert.assertEquals(5, d.favouritesShown); org.junit.Assert.assertTrue(d.suggestions && d.railTicks && d.showDate)
        org.junit.Assert.assertEquals(app.ultimate.core.Liquid.teal, Accent.Aurora.a); org.junit.Assert.assertEquals(app.ultimate.core.Liquid.violet, Accent.Aurora.b)
    }
}
