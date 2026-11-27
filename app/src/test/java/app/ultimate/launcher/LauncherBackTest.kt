package app.ultimate.launcher

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.ultimate.core.UltimateTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** System Back on the launcher: a settings page goes back to the list and the list closes settings. (Bare home staying home is the always-enabled callback in HomeActivity; a test that launches the real activity left its animations running and stalled other tests, so it is checked by reading the code instead.) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = app.ultimate.UltimateApp::class)
class LauncherBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun frames(n: Int = 20) = repeat(n) { compose.mainClock.advanceTimeByFrame() }
    private fun back() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; frames() }

    @Test fun backWalksOutOfSettingsOneStepAtATime() {
        val repo = LauncherRepo(ApplicationProvider.getApplicationContext())
        repo.seed(listOf("Gmail", "Photos", "Phone").map { HomeApp(it.lowercase(), it) }, listOf("gmail"))
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme { LauncherHome(repo, 0, {}, autoLoad = false) } }
        frames(30)
        compose.onNodeWithContentDescription("Launcher settings").performClick(); frames(30)
        compose.onNodeWithText("Themes").performClick(); frames(30)
        // The title says where we are: "Themes" on the page, "Launcher settings" on the list, and no title at all back on the home screen.
        compose.onNodeWithText("Launcher settings").assertDoesNotExist()
        back()
        compose.onNodeWithText("Launcher settings").assertIsDisplayed()   // back on the list of pages
        back()
        compose.onNodeWithText("Launcher settings").assertDoesNotExist()  // settings closed
        compose.onNodeWithContentDescription("Launcher settings").assertIsDisplayed()   // the gear on the home screen
        assertFalse("Back from settings must not leave the home screen", compose.activity.isFinishing)
    }

    @Test fun theHomeActivityHasNoOldStyleBackOverrideThatSwallowsBack() {
        // The old override of onBackPressed() did nothing, so on phones that deliver Back the old way it never reached the settings pages.
        val declared = HomeActivity::class.java.declaredMethods.map { it.name }
        assertTrue("HomeActivity must not override onBackPressed", "onBackPressed" !in declared)
    }
}
