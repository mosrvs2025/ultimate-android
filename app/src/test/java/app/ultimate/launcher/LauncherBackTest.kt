package app.ultimate.launcher

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
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

    /** Every Back callback the activity has, and whether it is on: what a failure needs to say where the press went. */
    private fun callbacks(): String = runCatching {
        val d = compose.activity.onBackPressedDispatcher
        val f = d.javaClass.getDeclaredField("onBackPressedCallbacks").also { it.isAccessible = true }
        (f.get(d) as Iterable<*>).joinToString("\n") { c -> "  " + c!!.javaClass.name + " enabled=" + (c as androidx.activity.OnBackPressedCallback).isEnabled }
    }.getOrElse { "(could not list: $it)" }

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
        val before = callbacks()
        back()
        try { compose.onNodeWithText("Launcher settings").assertExists() }   // back on the list of pages
        catch (e: AssertionError) { throw AssertionError("After Back from Themes the list of pages should show.\nBack callbacks before the press:\n$before\nafter:\n${callbacks()}\nfinishing=${compose.activity.isFinishing}\nThe screen is:\n" + compose.onRoot().printToString(maxDepth = 1), e) }
        back()
        compose.onNodeWithText("Launcher settings").assertDoesNotExist()  // settings closed
        compose.onNodeWithContentDescription("Launcher settings").assertExists()   // the gear on the home screen
        assertFalse("Back from settings must not leave the home screen", compose.activity.isFinishing)
    }

    @Test fun theHomeActivityHasNoOldStyleBackOverrideThatSwallowsBack() {
        // The old override of onBackPressed() did nothing, so on phones that deliver Back the old way it never reached the settings pages.
        val declared = HomeActivity::class.java.declaredMethods.map { it.name }
        assertTrue("HomeActivity must not override onBackPressed", "onBackPressed" !in declared)
    }
}
