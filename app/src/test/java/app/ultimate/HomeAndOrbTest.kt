package app.ultimate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.ultimate.home.ToolSwitcher
import app.ultimate.home.UltimateRoot
import app.ultimate.core.UltimateTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w411dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeAndOrbTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @After fun cleanup() {
        java.security.Security.getProviders().filter { it.name.contains("Conscrypt", true) }.forEach { java.security.Security.removeProvider(it.name) }
    }
    @Test fun fullHomeRendersWithNavigation() {
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        // Single-tool editions (Squeeze, Audio...) open straight into their tool and have no Home.
        org.junit.Assume.assumeTrue(app.tools.size > 1)
        compose.setContent { UltimateTheme { UltimateRoot(app) } }
        // Home is a scrolling list (and a first-run dialog can sit on top), so check its tools and the bar are composed.
        compose.onNodeWithText("Your tools").assertExists()
        compose.onNodeWithText("Home").assertExists()
        saveFrame("living-home.png")
    }

    private fun saveFrame(name: String) {
        val target = File("build/reports/$name").apply { parentFile?.mkdirs() }
        // PixelCopy waits for a real window callback. Native drawing uses the same Compose display list.
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(image))
            target.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    @Test fun toolOrbOpensTheJumpPanelWithHome() {
        // Inside a tool the orb carries the tool's look (from the living surface) and opens the one Jump panel.
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        compose.setContent { MaterialTheme { ToolSwitcher(app, app.tools.first()) } }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Switch tool")).performClick()
        compose.onNodeWithText("Jump to a tool").assertExists()
        saveFrame("living-tool-orbit.png")
        compose.onNodeWithText("Home").assertExists()
    }
}
