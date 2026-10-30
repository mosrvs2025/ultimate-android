package app.ultimate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ultimate.home.Route
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real PTY/JNI and UI tests: Robolectric cannot reproduce native shell startup or hidden API enforcement. */
@RunWith(AndroidJUnit4::class)
class TerminalSmokeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as UltimateApp

    private fun output(): String {
        var result = ""
        ui.runOnIdle { result = app.terminal.current.value?.emulator?.screen?.transcriptText.orEmpty() }
        return result
    }

    private fun awaitOutput(text: String) {
        ui.waitUntil(15_000) { output().contains(text) }
    }

    @Test fun shellLaunchesRunsPhoneCommandsAndSurvivesNavigation() {
        ui.onNodeWithTag("nav-terminal").performClick()
        awaitOutput("Ultimate terminal")
        ui.runOnIdle {
            assertNull(app.terminal.error.value)
            assertTrue(app.terminal.current.value!!.isRunning)
            app.terminal.type("printf 'ULT_%s\\n' SHELL_OK", false)
        }
        // Exercise the on-screen Enter key, not just the manager.
        ui.onNodeWithText("ENTER").performScrollTo().performClick()
        awaitOutput("ULT_SHELL_OK")
        ui.runOnIdle { app.terminal.type("help", true) }
        awaitOutput("Phone commands")
        ui.onNodeWithTag("nav-library").performClick()
        ui.onNodeWithTag("nav-terminal").performClick()
        awaitOutput("ULT_SHELL_OK")
        ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.runOnIdle { assertEquals(Route.Library, app.shell.route.value) }
        ui.onNodeWithTag("nav-home").assertIsDisplayed()
    }

    @Test fun exitedShellKeepsItsOutputAndCanBeReplaced() {
        ui.onNodeWithTag("nav-terminal").performClick()
        awaitOutput("Ultimate terminal")
        ui.runOnIdle { app.terminal.type("exit 7", true) }
        ui.waitUntil(15_000) {
            var exited = false
            ui.runOnIdle { exited = app.terminal.current.value?.isRunning == false }
            exited
        }
        ui.onNodeWithText("Shell exited (7)").assertIsDisplayed()
        ui.runOnIdle { assertTrue(app.terminal.sessions.value.contains(app.terminal.current.value)) }
        ui.onNodeWithContentDescription("New shell").performClick()
        awaitOutput("Ultimate terminal")
        ui.runOnIdle { assertTrue(app.terminal.current.value!!.isRunning) }
    }
}
