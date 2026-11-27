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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** System Back on the launcher: a settings page goes back to the list, the list closes settings, and bare home never leaves the home screen. */
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
        compose.onNodeWithText("Wallpaper").assertDoesNotExist()        // on the Themes page: the list of pages is not showing
        back()
        compose.onNodeWithText("Wallpaper").assertIsDisplayed()         // back on the list of pages
        compose.onNodeWithText("Launcher settings").assertIsDisplayed()
        back()
        compose.onNodeWithContentDescription("Launcher settings").assertIsDisplayed()   // settings closed: the gear on the home screen
        assertFalse("Back from settings must not leave the home screen", compose.activity.isFinishing)
    }

    @Test fun backOnTheBareHomeScreenStaysHome() {
        val home = Robolectric.buildActivity(HomeActivity::class.java).create().start().resume().get()
        home.onBackPressedDispatcher.onBackPressed()
        assertFalse("Back on the home screen must not close it", home.isFinishing)
    }

    @Test fun theHomeActivityHasNoOldStyleBackOverrideThatSwallowsBack() {
        // The old override of onBackPressed() did nothing, so on phones that deliver Back the old way it never reached the settings pages.
        val declared = HomeActivity::class.java.declaredMethods.map { it.name }
        assertTrue("HomeActivity must not override onBackPressed", "onBackPressed" !in declared)
    }
}
