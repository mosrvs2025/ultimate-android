package app.ultimate.shots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    @Test fun flingAnAppToAnotherPhone() {
        assumeTrue(dir != null)
        app.ultimate.mesh.DeviceFling.provider = object : app.ultimate.mesh.DeviceFling.Provider {
            override fun targets() = listOf(app.ultimate.mesh.DeviceFling.Target("a", "Galaxy Z Flip6", "fold", near = true), app.ultimate.mesh.DeviceFling.Target("b", "Galaxy S21 Ultra", "phone", near = false))
            override suspend fun app(targetId: String, pkg: String, label: String): String? = null
            override suspend fun text(targetId: String, text: String): String? = null
            override suspend fun file(targetId: String, name: String, mime: String, bytes: ByteArray, progress: (Float) -> Unit): String? = null
        }
        try {
            show()
            val row = compose.onNodeWithText("Photos")
            row.performTouchInput { down(center) }
            repeat(40) { compose.mainClock.advanceTimeByFrame() } // past the long-press
            repeat(30) { i ->
                row.performTouchInput { moveTo(androidx.compose.ui.geometry.Offset(center.x - 9f * i, center.y + 14f * i)) }
                compose.mainClock.advanceTimeByFrame()
            }
            repeat(40) { compose.mainClock.advanceTimeByFrame() }
            save("launcher-fling")
        } finally { app.ultimate.mesh.DeviceFling.provider = null }
    }

    @Test fun edgeRailOverAnyApp() {
        assumeTrue(dir != null)
        val repo = LauncherRepo(ApplicationProvider.getApplicationContext())
        repo.seed(names.map { HomeApp(it.lowercase(), it) }, emptyList())
        val drag = kotlinx.coroutines.flow.MutableStateFlow(app.ultimate.launcher.EdgeDrag())
        val expanded = kotlinx.coroutines.flow.MutableStateFlow(true)
        val hover = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFECEFF3))) {
                androidx.compose.material3.Text("Any app underneath", androidx.compose.ui.Modifier.padding(24.dp), color = androidx.compose.ui.graphics.Color(0xFF333333), fontSize = 22.sp)
                app.ultimate.launcher.EdgeRailContent(repo, drag, expanded, hover, {}, {}, {})
            }
        } }
        compose.mainClock.advanceTimeBy(300)
        // A thumb on the tab at the right edge, sliding down to the letters around S (screen pixels at xxhdpi).
        repeat(40) { i -> drag.value = app.ultimate.launcher.EdgeDrag(true, 1010f, 900f + 22f * minOf(i, 25), moved = true); compose.mainClock.advanceTimeByFrame() }
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
        save("edge-rail")
    }
}
