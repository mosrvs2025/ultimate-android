package app.ultimate.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.ultimate.UltimateApp
import app.ultimate.core.UltimateTheme
import app.ultimate.launcher.HomeApp
import app.ultimate.launcher.LauncherHome
import app.ultimate.launcher.LauncherRepo
import app.ultimate.launcher.LookCard
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

/** The launcher's home styles (Pages, Hive, Dial, Tiles, Prompt) on a Fold's outer screen with sample apps, and every look as a small card. Renderings, not photos. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w352dp-h780dp-notnight-xxhdpi")
class HomeStylesShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)

    private val names = listOf("Browser", "Bixby", "Calculator", "Calendar", "Camera", "Chrome", "Clock", "Contacts", "Gallery", "Gmail", "Google", "Health", "Instagram", "Maps",
        "Messages", "Netflix", "Notes", "Phone", "Photos", "Play Store", "Pokémon GO", "Samsung Notes", "Settings", "Spotify", "Strava", "Telegram", "Ultimate+", "WhatsApp", "YouTube", "YT Music")
    private val favs = listOf("gmail", "phone", "photos", "gallery", "chrome", "maps", "spotify", "youtube")

    private fun save(name: String) {
        compose.onRoot().captureRoboImage(dir!!.apply { mkdirs() }.resolve("$name.png").path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }

    private fun style(look: LookPreset) {
        assumeTrue(dir != null)
        val repo = LauncherRepo(ApplicationProvider.getApplicationContext())
        repo.seed(names.map { HomeApp(it.lowercase(), it) }, favs)
        repo.update { look.apply(it) }
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme { LauncherHome(repo, 0, {}, autoLoad = false) } }
        compose.mainClock.advanceTimeBy(2_000)
        save("style-${look.id}")
    }

    @Test fun pages() = style(Looks.pages)
    @Test fun hive() = style(Looks.hive)
    @Test fun dial() = style(Looks.dial)
    @Test fun metro() = style(Looks.metro)
    @Test fun prompt() = style(Looks.prompt)
}

/** All the looks as the cards on the Themes page, wrapped so none is off to the side. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w640dp-h640dp-notnight-xxhdpi")
class LookCardsShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)

    @OptIn(ExperimentalLayoutApi::class)
    @Test fun allLooks() {
        assumeTrue(dir != null)
        compose.setContent {
            UltimateTheme {
                FlowRow(Modifier.background(Color(0xFF1B212B)).padding(12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Looks.all.forEach { p -> LookCard(p, false, Color.White) {} }
                }
            }
        }
        compose.onRoot().captureRoboImage(dir!!.apply { mkdirs() }.resolve("look-cards.png").path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }
}
