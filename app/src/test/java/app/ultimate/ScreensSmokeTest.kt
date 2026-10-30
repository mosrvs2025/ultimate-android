package app.ultimate

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import app.ultimate.core.ToolLaunch
import app.ultimate.home.AboutScreen
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Draws the real screens in a simulated Android. A screen that crashes while composing, or never shows its heading, fails here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UltimateApp::class, instrumentedPackages = ["androidx.loader.content"])
class ScreensSmokeTest {
    @get:Rule val compose = createComposeRule()

    @After fun restoreSecurityProviders() {
        java.security.Security.getProviders().filter { it.name.contains("Conscrypt", ignoreCase = true) }.forEach { java.security.Security.removeProvider(it.name) }
    }

    private fun show(toolId: String, heading: String) {
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        val tool = app.tools.firstOrNull { it.id == toolId } ?: return // this edition doesn't have it
        compose.setContent { MaterialTheme { tool.Screen({}, ToolLaunch()) } }
        compose.onNodeWithText(heading).assertIsDisplayed()
    }

    @Test fun aboutScreenShowsTheVersion() {
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        compose.setContent { MaterialTheme { AboutScreen(app) {} } }
        compose.onNodeWithText("About and settings").assertIsDisplayed()
        compose.onNodeWithText("Version ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithText("Copy details").assertIsDisplayed()
    }

    @Test fun rulesScreenDraws() = show("rules", "Rules")
    @Test fun hostsScreenDraws() = show("hosts", "SSH hosts")
    @Test fun storageScreenDraws() = show("storage", "Storage")
    @Test fun cleanupScreenDraws() = show("cleanup", "Clean up")
    @Test fun everyEditionStartsAndHasItsTools() {
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        check(app.tools.isNotEmpty()) { "no tools" }
        // Editions that include the terminal must register it; the others must not.
        check(app.tools.any { it.id == "terminal" } == BuildConfig.HAS_TERMINAL)
    }
}
