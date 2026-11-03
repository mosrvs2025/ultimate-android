package app.ultimate

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.ultimate.core.UltimateTheme
import app.ultimate.home.Route
import app.ultimate.home.UltimateRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** System Back from a tool goes back to Home (it used to leave the app from tools without their own Back). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UltimateApp::class)
class BackNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun backFromYouReturnsHome() {
        val app = compose.activity.application as UltimateApp
        val you = app.tools.firstOrNull { it.id == "timeline" }
        assumeTrue(you != null)
        compose.setContent { UltimateTheme { UltimateRoot(app) } }
        compose.runOnUiThread { app.shell.showHome(); app.shell.open(you!!) }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(Route.Home, app.shell.route.value)
        assertFalse(compose.activity.isFinishing)
    }
}
