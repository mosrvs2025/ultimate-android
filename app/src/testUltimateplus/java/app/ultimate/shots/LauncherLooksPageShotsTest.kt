package app.ultimate.shots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.ultimate.UltimateApp
import app.ultimate.core.UltimateTheme
import app.ultimate.launcher.Accent
import app.ultimate.launcher.HomeApp
import app.ultimate.launcher.LookRule
import app.ultimate.launcher.When
import app.ultimate.launcher.WallpaperInk
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

/** The Themes page of the launcher settings: the looks, the colours, the parts and the share box, in one tall window so all of it is drawn. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w352dp-h1500dp-notnight-xxhdpi")
class LauncherLooksPageShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)

    @Test fun themesPage() = page("looks-page", withRules = false)

    /** The same page with a few rules set and the wallpaper's colours chosen, so the Living looks card is seen in use. */
    @Test fun themesPageWithRules() = page("looks-page-rules", withRules = true)

    private fun page(name: String, withRules: Boolean) {
        assumeTrue(dir != null)
        WallpaperInk.set(listOf(0xFF1FA39A.toInt(), 0xFFE8742C.toInt()))
        val repo = LauncherRepo(ApplicationProvider.getApplicationContext())
        if (withRules) repo.update { it.copy(accent = Accent.Wallpaper, rules = listOf(LookRule(When.Between(22 * 60, 6 * 60), "zen"), LookRule(When.Focusing, "terminal"))) }
        repo.seed(listOf("Gmail", "Photos", "Phone", "Maps", "Chrome").map { HomeApp(it.lowercase(), it) }, listOf("gmail", "photos"))
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme { LauncherHome(repo, 0, {}, autoLoad = false) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithContentDescription("Launcher settings").performClick()
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithText("Themes").performClick()
        repeat(20) { compose.mainClock.advanceTimeByFrame() }
        val out = dir!!.apply { mkdirs() }.resolve("$name.png")
        compose.onRoot().captureRoboImage(out.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }
}
