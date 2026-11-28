package app.ultimate.shots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.ultimate.UltimateApp
import app.ultimate.core.UltimateTheme
import app.ultimate.launcher.HomeApp
import app.ultimate.launcher.LauncherRepo
import app.ultimate.launcher.LauncherSettings
import app.ultimate.launcher.LauncherSettingsSheet
import app.ultimate.launcher.Looks
import app.ultimate.launcher.SettingsPage
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

/** The look studio on a Fold's outer screen: the hub with the look you wear, Themes with a pending preview, and Wallpaper. Renderings, not photos. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w352dp-h780dp-notnight-xxhdpi")
class StudioShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)

    private fun shot(name: String, page: SettingsPage?, draft: LauncherSettings?) {
        assumeTrue(dir != null)
        val repo = LauncherRepo(ApplicationProvider.getApplicationContext())
        repo.seed(listOf("Mail", "Phone", "Maps").map { HomeApp(it.lowercase(), it) }, listOf("mail", "phone", "maps"))
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme { LauncherSettingsSheet(repo, emptyList(), LauncherSettings(), page, {}, {}, initialDraft = draft) } }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage(dir!!.apply { mkdirs() }.resolve("$name.png").path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }

    @Test fun hub() = shot("studio-hub", null, null)
    @Test fun themesApplied() = shot("studio-themes", SettingsPage.Colours, null)
    @Test fun themesPending() = shot("studio-themes-pending", SettingsPage.Colours, Looks.terminal.apply(LauncherSettings()))
    @Test fun wallpaper() = shot("studio-wallpaper", SettingsPage.Wallpaper, null)
}

/** The whole Themes page on a very tall screen, so every tile row can be looked at at once. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w352dp-h2100dp-notnight-xxhdpi")
class StudioTallShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)

    private fun shot(name: String, page: SettingsPage?, draft: LauncherSettings?) {
        assumeTrue(dir != null)
        val repo = LauncherRepo(ApplicationProvider.getApplicationContext())
        repo.seed(listOf("Mail", "Phone", "Maps").map { HomeApp(it.lowercase(), it) }, listOf("mail", "phone", "maps"))
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme { LauncherSettingsSheet(repo, emptyList(), LauncherSettings(), page, {}, {}, initialDraft = draft) } }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage(dir!!.apply { mkdirs() }.resolve("$name.png").path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }

    @Test fun themesPendingTall() = shot("studio-themes-tall", SettingsPage.Colours, Looks.dial.apply(LauncherSettings()))
}
