package app.ultimate.shots

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.ultimate.UltimateApp
import app.ultimate.core.UltimateTheme
import app.ultimate.launcher.HomeApp
import app.ultimate.launcher.LauncherHome
import app.ultimate.launcher.LauncherRepo
import app.ultimate.launcher.LookPreset
import app.ultimate.launcher.Looks
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

/** Each launcher look on the home screen at a folded Fold's outer-screen width, with sample apps. Renderings, not photos. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w352dp-h780dp-notnight-xxhdpi")
class LauncherLooksShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)

    private val names = listOf("Browser", "Bixby", "Calculator", "Calendar", "Camera", "Chrome", "Clock", "Contacts", "Gallery", "Gmail", "Google", "Health", "Instagram", "Maps",
        "Messages", "Netflix", "Notes", "Phone", "Photos", "Play Store", "Pokémon GO", "Samsung Notes", "Settings", "Spotify", "Strava", "Telegram", "Ultimate+", "WhatsApp", "YouTube", "YT Music")
    private val favs = listOf("gmail", "phone", "photos", "gallery", "chrome", "maps", "spotify", "youtube")

    private fun show(look: LookPreset): LauncherRepo {
        val repo = LauncherRepo(ApplicationProvider.getApplicationContext())
        repo.seed(names.map { HomeApp(it.lowercase(), it) }, favs)
        repo.update { look.apply(it) }
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme { LauncherHome(repo, 0, {}, autoLoad = false) } }
        compose.mainClock.advanceTimeBy(1_500)
        return repo
    }

    private fun save(name: String) {
        val out = dir!!.apply { mkdirs() }.resolve("$name.png")
        compose.onRoot().captureRoboImage(out.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }

    private fun rest(look: LookPreset) { assumeTrue(dir != null); show(look); save("look-${look.id}") }

    private fun thumbOnTheRail(look: LookPreset) {
        assumeTrue(dir != null); show(look)
        val rail = compose.onNodeWithContentDescription("Letter rail: slide to find an app")
        rail.performTouchInput { down(Offset(width * 0.85f, height * 0.25f)) }
        repeat(30) { i ->
            compose.mainClock.advanceTimeByFrame()
            rail.performTouchInput { moveTo(Offset(width * 0.85f, height * (0.25f + 0.37f * minOf(1f, i / 20f)))) }
        }
        repeat(40) { compose.mainClock.advanceTimeByFrame() }
        save("look-${look.id}-rail")
    }

    @Test fun aurora() = rest(Looks.aurora)
    @Test fun terminal() = rest(Looks.terminal)
    @Test fun paper() = rest(Looks.paper)
    @Test fun zen() = rest(Looks.zen)
    @Test fun tiles() = rest(Looks.tiles)
    @Test fun dusk() = rest(Looks.dusk)

    @Test fun paperSearch() {
        assumeTrue(dir != null); show(Looks.paper)
        compose.onRoot().performTouchInput { down(Offset(300f, 1_900f)); moveTo(Offset(300f, 1_500f)); moveTo(Offset(300f, 800f)); up() }
        repeat(40) { compose.mainClock.advanceTimeByFrame() }
        save("look-paper-search")
    }

    @Test fun terminalRail() = thumbOnTheRail(Looks.terminal)
    @Test fun paperRail() = thumbOnTheRail(Looks.paper)
    @Test fun zenRail() = thumbOnTheRail(Looks.zen)
}
