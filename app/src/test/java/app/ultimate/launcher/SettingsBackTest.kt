package app.ultimate.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * System Back in the launcher. The rules are plain logic, and the one thing that broke them on phones that deliver Back the old way (an empty
 * onBackPressed override on the home activity) is checked in the source. A test that drove the real screen with a Back dispatcher passed but
 * made the Robolectric tests after it (HostsScreenTest) never go idle, so it was taken out.
 */
class SettingsBackTest {
    @Test fun aSettingsPageGoesBackToTheListOfPagesAndTheListClosesSettings() {
        var s = SettingsBack.State(open = true, page = SettingsPage.Colours)
        s = SettingsBack.next(s)
        assertEquals("page to list", SettingsBack.State(open = true, page = null), s)
        s = SettingsBack.next(s)
        assertEquals("list to home", SettingsBack.State(open = false, page = null), s)
    }

    @Test fun everyPageGoesBackTheSameWay() {
        SettingsPage.entries.forEach { p -> assertEquals(SettingsBack.State(true, null), SettingsBack.next(SettingsBack.State(true, p))) }
    }

    @Test fun withSettingsClosedThereIsNothingToUndo() {
        assertEquals(SettingsBack.State(false, null), SettingsBack.next(SettingsBack.State(false, null)))
        // A page left over from before settings closed stays as it is rather than being rewritten.
        assertEquals(SettingsBack.State(false, SettingsPage.Home), SettingsBack.next(SettingsBack.State(false, SettingsPage.Home)))
    }

    @Test fun theHomeActivityDoesNotSwallowBackTheOldWay() {
        val source = listOf("src/main/java/app/ultimate/launcher/HomeActivity.kt", "app/src/main/java/app/ultimate/launcher/HomeActivity.kt").map(::File).first { it.exists() }.readText()
        val override = Regex("""override\s+fun\s+onBackPressed\(\)\s*\{([^}]*)}""").find(source)
        assertTrue("an onBackPressed override must pass the press on (an empty one stops Back reaching the screen's handlers)", override == null || override.groupValues[1].contains("super.onBackPressed()"))
        assertFalse("no one-line empty override", Regex("""override\s+fun\s+onBackPressed\(\)\s*=\s*Unit""").containsMatchIn(source))
        assertTrue("home stays home through one always-enabled callback, added before the screen", source.contains("OnBackPressedCallback(true)") && source.indexOf("OnBackPressedCallback(true)") < source.indexOf("setContent {"))
    }
}
