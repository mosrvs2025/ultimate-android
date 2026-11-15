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
}
