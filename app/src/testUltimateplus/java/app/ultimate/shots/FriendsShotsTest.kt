package app.ultimate.shots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.ultimate.UltimateApp
import app.ultimate.core.ToolLaunch
import app.ultimate.core.UltimateTheme
import app.ultimate.social.FriendsTool
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The Walk with friends screen as it first opens (nothing added yet). A rendering, not a photo. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w412dp-h1700dp-notnight-xxhdpi")
class FriendsShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)

    @Test fun firstOpen() {
        assumeTrue(dir != null)
        val tool = FriendsTool(ApplicationProvider.getApplicationContext())
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme { tool.Screen({}, ToolLaunch()) } }
        compose.mainClock.advanceTimeBy(800)
        val out = dir!!.apply { mkdirs() }.resolve("friends-walks.png")
        compose.onRoot().captureRoboImage(out.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }
}
