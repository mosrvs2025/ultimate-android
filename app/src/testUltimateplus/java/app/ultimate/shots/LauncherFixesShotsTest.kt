package app.ultimate.shots

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertCountEquals
import androidx.test.core.app.ApplicationProvider
import app.ultimate.UltimateApp
import app.ultimate.core.UltimateTheme
import app.ultimate.launcher.Accent
import app.ultimate.launcher.HomeApp
import app.ultimate.launcher.LauncherHome
import app.ultimate.launcher.LauncherRepo
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** What the launcher does about the four things that were wrong: a long letter, the full list, the hold menu, and settings. Renderings, not photos. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w352dp-h780dp-notnight-xxhdpi")
class LauncherFixesShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)

    private fun show(apps: List<String>, favs: List<String> = emptyList()): LauncherRepo {
        val repo = LauncherRepo(ApplicationProvider.getApplicationContext())
        repo.seed(apps.map { HomeApp(it.lowercase(), it) }, favs.map { it.lowercase() })
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme { LauncherHome(repo, 0, {}, autoLoad = false) } }
        compose.mainClock.advanceTimeBy(1_500)
        return repo
    }
    /** A finger moving from [from] to [to] in [steps] steps with a frame between each, as a real one does (injecting it all at once gives the app no chance to see a drag). */
    private fun drag(node: androidx.compose.ui.test.SemanticsNodeInteraction, from: Offset, to: Offset, steps: Int = 24, release: Boolean = true) {
        node.performTouchInput { down(from) }
        frames(2)
        for (i in 1..steps) { val t = i / steps.toFloat(); node.performTouchInput { moveTo(Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)) }; frames(1) }
        if (release) { node.performTouchInput { up() }; frames(2) }
    }
    private fun frames(n: Int) = repeat(n) { compose.mainClock.advanceTimeByFrame() }
    private fun save(name: String) {
        val out = dir!!.apply { mkdirs() }.resolve("$name.png")
        compose.onRoot().captureRoboImage(out.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }

    @Test fun aLetterWithManyAppsScrollsInsteadOfOpeningSearch() {
        assumeTrue(dir != null)
        show(listOf("Alpha", "Zed") + (1..40).map { "Mapper %02d".format(it) })
        val rail = compose.onNodeWithContentDescription("Letter rail: slide to find an app")
        // Letters are A, M, Z: M is the middle one. Put a thumb there and let go.
        rail.performTouchInput { down(Offset(width * 0.85f, height * 0.5f)) }
        frames(30)
        save("launcher-long-letter-held")
        rail.performTouchInput { up() }
        frames(60)
        save("launcher-long-letter")
        compose.onNodeWithText("Mapper 01").assertIsDisplayed()
        compose.onNodeWithText("Mapper 40").assertIsNotDisplayed()          // cut to the room there is, not running off the screen
        save("launcher-long-letter")
        // Swiping up on the list scrolls it, and does not open search.
        val row = compose.onNodeWithText("Mapper 05")
        var from = Offset.Zero
        row.performTouchInput { from = center }
        drag(row, from, Offset(from.x, from.y - 1_000f), steps = 30)
        frames(60)
        compose.onNodeWithText("Mapper 40").assertIsDisplayed()
        compose.onAllNodesWithText("Search apps").assertCountEquals(0)
        save("launcher-long-letter-scrolled")
    }

    @Test fun searchListsEveryApp() {
        assumeTrue(dir != null)
        show((1..120).map { "App %03d".format(it) })
        drag(compose.onRoot(), Offset(300f, 1_900f), Offset(300f, 800f), steps = 30)
        frames(60)
        save("launcher-search-all")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("App 120"))   // well past the old limit of 60
        frames(10)
        compose.onNodeWithText("App 120").assertIsDisplayed()
        save("launcher-search-all")
    }

    @Test fun holdingAnAppOpensTheMenuWithFavourites() {
        assumeTrue(dir != null)
        app.ultimate.mesh.DeviceFling.provider = object : app.ultimate.mesh.DeviceFling.Provider {
            override fun targets() = listOf(app.ultimate.mesh.DeviceFling.Target("a", "Galaxy Z Flip6", "fold", near = true))
            override suspend fun app(targetId: String, pkg: String, label: String): String? = null
            override suspend fun text(targetId: String, text: String): String? = null
            override suspend fun file(targetId: String, name: String, mime: String, bytes: ByteArray, progress: (Float) -> Unit): String? = null
        }
        try {
            show(listOf("Gmail", "Photos", "Phone", "Maps"), favs = listOf("Photos"))
            val row = compose.onNodeWithText("Photos")
            row.performTouchInput { down(center) }
            frames(40)                                                   // past the long-press, finger still down and still
            row.performTouchInput { up() }
            frames(30)
            compose.onNodeWithText("Remove from favourites").assertExists()   // the menu, not a floating app
            compose.onNodeWithText("Send to Galaxy Z Flip6").assertExists()
            save("launcher-hold-menu")
        } finally { app.ultimate.mesh.DeviceFling.provider = null }
    }

    @Test fun settingsChangeTheLauncher() {
        assumeTrue(dir != null)
        val repo = show(listOf("Gmail", "Photos", "Phone", "Maps", "Chrome"), favs = listOf("Gmail", "Photos"))
        compose.onNodeWithContentDescription("Launcher settings").performClick()
        frames(30)
        compose.onNodeWithText("Launcher settings").assertIsDisplayed()
        save("launcher-settings")
        compose.onNodeWithContentDescription("Sunset").performClick()
        frames(10)
        assertEquals(Accent.Sunset, repo.settings.value.accent)
        compose.onNodeWithText("Black").performClick(); frames(5)
        compose.onNodeWithText("12-hour").performClick(); frames(5)
        assertEquals(app.ultimate.launcher.Backdrop.Black, repo.settings.value.backdrop)
        assertEquals(app.ultimate.launcher.ClockStyle.H12, repo.settings.value.clock)
        save("launcher-settings-changed")
        compose.onNodeWithContentDescription("Close settings").performClick()
        frames(30)
        save("launcher-sunset-black")
    }
}
