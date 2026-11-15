package app.ultimate.shots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.ultimate.UltimateApp
import app.ultimate.core.UltimateTheme
import app.ultimate.launcher.HomeApp
import app.ultimate.launcher.LauncherHome
import app.ultimate.launcher.LauncherRepo
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

/** Renders the home screen at a folded Fold's outer-screen width, at rest and with a thumb held on the letter rail. Sample apps; renderings, not photos. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w352dp-h780dp-notnight-xxhdpi")
class LauncherShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)

    private val names = listOf("Browser", "Bixby", "Calculator", "Calendar", "Camera", "Chrome", "Clock", "Contacts", "Gallery", "Gmail", "Google", "Health", "Instagram", "Maps",
        "Messages", "Netflix", "Notes", "Phone", "Photos", "Play Store", "Pokémon GO", "Samsung Notes", "Settings", "Spotify", "Strava", "Telegram", "Ultimate+", "WhatsApp", "YouTube", "YT Music")

    private fun show(): LauncherRepo {
        val repo = LauncherRepo(ApplicationProvider.getApplicationContext())
        repo.seed(names.map { HomeApp(it.lowercase(), it) }, listOf("gmail", "phone", "photos", "gallery", "chrome"))
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme { LauncherHome(repo, 0, {}, autoLoad = false) } }
        compose.mainClock.advanceTimeBy(1_500)
        return repo
    }

    private fun save(name: String) {
        val out = dir!!.apply { mkdirs() }.resolve("$name.png")
        compose.onRoot().captureRoboImage(out.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }

    @Test fun atRest() { assumeTrue(dir != null); show(); save("launcher-home") }

    @Test fun thumbOnTheRail() {
        assumeTrue(dir != null); show()
        val rail = compose.onNodeWithContentDescription("Letter rail: slide to find an app")
        rail.performTouchInput { down(androidx.compose.ui.geometry.Offset(width * 0.85f, height * 0.25f)) }
        repeat(30) { i ->
            compose.mainClock.advanceTimeByFrame()
            rail.performTouchInput { moveTo(androidx.compose.ui.geometry.Offset(width * 0.85f, height * (0.25f + 0.37f * minOf(1f, i / 20f)))) }
        }
        repeat(40) { compose.mainClock.advanceTimeByFrame() }
        save("launcher-rail")
    }
}
