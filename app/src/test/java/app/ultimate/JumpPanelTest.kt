package app.ultimate

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.ultimate.core.UltimateTheme
import app.ultimate.home.JumpMath
import app.ultimate.home.ToolSwitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UltimateApp::class)
class JumpPanelTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<UltimateApp>()

    @Test fun bubbleBecomesThePanelAndBack() {
        val bubble = Rect(900f, 1200f, 1040f, 1340f); val panel = Rect(30f, 700f, 1050f, 2300f)
        assertEquals(bubble to 70f, JumpMath.morph(bubble, panel, 0f))
        val (r, _) = JumpMath.morph(bubble, panel, 1f)
        assertEquals(panel, r)
        val (mid, _) = JumpMath.morph(bubble, panel, 0.5f)
        assertTrue(mid.width > bubble.width && mid.width < panel.width)
    }

    // Single-tool editions (Squeeze, Audio...) have no Jump panel and none of these tools.
    private fun needs(vararg ids: String) = org.junit.Assume.assumeTrue(ids.all { id -> app.tools.any { it.id == id } })

    @Test fun searchFindsByNameOrPurposeAndPutsNameMatchesFirst() {
        needs("storage")
        val found = JumpMath.search(app.tools, "stor")
        assertEquals("storage", found.first().id)
        assertEquals(app.tools.size, JumpMath.search(app.tools, "  ").size)
        assertTrue(JumpMath.search(app.tools, "zzzz-nothing").isEmpty())
    }

    @Test fun tapTheBubbleSearchAndJump() {
        needs("storage", "rules")
        val current = app.tools.first { it.id == "storage" }
        compose.setContent { UltimateTheme { ToolSwitcher(app, current) } }
        compose.onNode(hasContentDescription("Switch tool")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Jump to a tool").assertExists()
        compose.onNodeWithText("Home").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("rules")
        compose.onNodeWithText("Rules").performClick()
        compose.waitForIdle()
        assertTrue(app.shell.route.value.toString(), app.shell.route.value.toString().contains("rules"))
    }
}
