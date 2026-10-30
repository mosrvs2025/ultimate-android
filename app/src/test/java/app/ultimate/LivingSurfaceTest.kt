package app.ultimate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.ultimate.home.LivingSurface
import app.ultimate.home.ToolSwitcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w411dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LivingSurfaceTest {
    @get:Rule val compose = createComposeRule()
    @After fun cleanup() {
        java.security.Security.getProviders().filter { it.name.contains("Conscrypt", true) }.forEach { java.security.Security.removeProvider(it.name) }
    }
    @Test fun compactSurfaceOpensRecentActionsAndATool() {
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        val tools = app.tools.filter { it.id in setOf("squeeze", "files", "storage") }.take(3)
        compose.setContent { MaterialTheme { Box(Modifier.width(300.dp)) { LivingSurface(app, tools, emptySet()) } } }
        compose.onNodeWithTag("home-orb").assertIsDisplayed().performClick()
        compose.onNodeWithText("From the last 48 hours").assertIsDisplayed()
        compose.onAllNodesWithText(tools.first().title).onFirst().performClick()
        check((app.shell.route.value as app.ultimate.home.Route.InTool).tool.id == tools.first().id)
    }
    @Test fun expandedSurfaceRenders() {
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        compose.setContent { MaterialTheme { LivingSurface(app, app.tools.take(6), setOf(app.tools.first().id)) } }
        compose.onNodeWithTag("home-orb").assertIsDisplayed()
        val target = File("build/reports/living-surface.png").apply { parentFile.mkdirs() }
        target.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun toolOrbHasAHomeActionAndAFullCatalogFallback() {
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        compose.setContent { MaterialTheme { ToolSwitcher(app, app.tools.first()) } }
        compose.onNode(hasClickAction() and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button)).performClick()
        compose.onNodeWithText("All tools").assertIsDisplayed().performClick()
        compose.onNodeWithText("Jump to").assertIsDisplayed()
    }
}
