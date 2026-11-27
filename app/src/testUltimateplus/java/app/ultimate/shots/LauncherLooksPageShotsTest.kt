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
import app.ultimate.launcher.Face
import app.ultimate.launcher.LookCode
import app.ultimate.launcher.LookSlot
import app.ultimate.launcher.Looks
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
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w352dp-h2900dp-notnight-xxhdpi")
class LauncherLooksPageShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)

    @Test fun themesPage() = page("looks-page", withRules = false)

    /** The same page with a few rules set and the wallpaper's colours chosen, so the Living looks card is seen in use. */
    @Test fun themesPageWithRules() = page("looks-page-rules", withRules = true)

    /** Two saved looks and a rule wearing one of them, so the Your looks card and the rule's saved-look chip are seen in use. */
    @Test fun themesPageWithSavedLooks() = page("looks-page-saved", withRules = false, withSlots = true)

    private fun page(name: String, withRules: Boolean, withSlots: Boolean = false) {
        assumeTrue(dir != null)
        WallpaperInk.set(listOf(0xFF1FA39A.toInt(), 0xFFE8742C.toInt()))
        val repo = LauncherRepo(ApplicationProvider.getApplicationContext())
        if (withSlots) repo.update {
            val night = LookCode.encode(Looks.zen.apply(it)); val work = LookCode.encode(Looks.terminal.apply(it).copy(face = Face.Serif))
            Looks.paper.apply(it).copy(slots = listOf(LookSlot("Night owl", night), LookSlot("Work mix", work)), rules = listOf(LookRule(When.Between(22 * 60, 6 * 60), night)))
        }
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
