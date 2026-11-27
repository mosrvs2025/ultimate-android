package app.ultimate.launcher

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.ultimate.core.UltimateTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * System Back on the launcher: a settings page goes back to the list and the list closes settings. The test supplies its own Back dispatcher
 * (so it needs no activity of its own, like the other launcher tests). Bare home staying home is the always-enabled callback in HomeActivity,
 * checked here by reading the class: a test that launched the real activity left its animations running and stalled other tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = app.ultimate.UltimateApp::class)
class LauncherBackTest {
    @get:Rule val compose = createComposeRule()
    private val dispatcher = OnBackPressedDispatcher()

    private fun frames(n: Int = 20) = repeat(n) { compose.mainClock.advanceTimeByFrame() }

    /** Presses system Back, then lets the screen take in the change (the state is written outside a test action, so it has to be applied first). */
    private fun back() {
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle(); frames()
    }

    @Test fun backWalksOutOfSettingsOneStepAtATime() {
        val repo = LauncherRepo(ApplicationProvider.getApplicationContext())
        repo.seed(listOf("Gmail", "Photos", "Phone").map { HomeApp(it.lowercase(), it) }, listOf("gmail"))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val owner = remember { object : OnBackPressedDispatcherOwner { override val lifecycle = lifecycle; override val onBackPressedDispatcher = dispatcher } }
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) { UltimateTheme { LauncherHome(repo, 0, {}, autoLoad = false) } }
        }
        frames(30)
        compose.onNodeWithContentDescription("Launcher settings").performClick(); frames(30)
        compose.onNodeWithText("Themes").performClick(); frames(30)
        // The title says where we are: "Themes" on the page, "Launcher settings" on the list, and no title at all back on the home screen.
        compose.onNodeWithText("Launcher settings").assertDoesNotExist()
        back()
        compose.onNodeWithText("Launcher settings").assertExists()          // back on the list of pages
        back()
        compose.onNodeWithText("Launcher settings").assertDoesNotExist()    // settings closed
        compose.onNodeWithContentDescription("Launcher settings").assertExists()   // the gear on the home screen
    }

    @Test fun theHomeActivityHasNoOldStyleBackOverrideThatSwallowsBack() {
        // The old override of onBackPressed() did nothing, so on phones that deliver Back the old way it never reached the settings pages.
        val declared = HomeActivity::class.java.declaredMethods.map { it.name }
        assertTrue("HomeActivity must not override onBackPressed", "onBackPressed" !in declared)
    }
}
