package app.ultimate.shots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.ultimate.UltimateApp
import app.ultimate.core.Liquid
import app.ultimate.core.LiquidSync
import app.ultimate.core.UltimateTheme
import app.ultimate.fusion.MovementAnalyzer
import app.ultimate.fusion.SimConfig
import app.ultimate.fusion.Trace
import app.ultimate.fusion.WalkSim
import app.ultimate.movement.MovementTool
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

/**
 * Renders the Movement review at the widths of the owner's phones, to check the layout by eye: a folded Galaxy Fold's outer
 * screen (about 352 dp) and a Flip / S-series (412 dp). The recording is SIMULATED (WalkSim); these are renderings, not photos.
 * Runs only when asked: `gradle -Pscreenshots :app:testUltimateplusDebugUnitTest --tests '*LiquidShotsTest'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class)
class LiquidShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)
    private val app get() = ApplicationProvider.getApplicationContext<UltimateApp>()

    private fun report() = MovementAnalyzer.analyze(Trace.read(WalkSim.run(SimConfig(laps = 2, camScale = 1.5)).lines.asSequence()))

    private fun review(name: String) {
        assumeTrue(dir != null)
        val tool = app.tools.filterIsInstance<MovementTool>().single()
        val r = report()
        compose.setContent { UltimateTheme { Surface(Modifier.fillMaxSize()) { tool.ReviewBody(null, r) { "ok" } } } }
        compose.waitForIdle()
        compose.onNodeWithText("Which sensor disagreed?").performClick()
        compose.waitForIdle()
        val out = dir!!.apply { mkdirs() }.resolve("$name.png")
        compose.onRoot().captureRoboImage(out.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
        println("SHOT>>> $name ${out.length()} bytes")
    }

    @Test @Config(qualifiers = "w352dp-h2600dp-notnight-xxhdpi") fun reviewOnFoldCoverScreen() = review("movement-review-352dp")
    @Test @Config(qualifiers = "w412dp-h2600dp-notnight-xxhdpi") fun reviewOnFlip() = review("movement-review-412dp")

    @Test @Config(qualifiers = "w352dp-h400dp-notnight-xxhdpi") fun syncDrops() {
        assumeTrue(dir != null)
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme { Column(Modifier.fillMaxSize().background(Liquid.bg).padding(16.dp)) { LiquidSync(true, "Galaxy Z Fold6", "Galaxy Watch", status = "Syncing · walking, 112 steps a minute") } } }
        compose.mainClock.advanceTimeBy(700)
        val out = dir!!.apply { mkdirs() }.resolve("liquid-sync.png")
        compose.onRoot().captureRoboImage(out.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }
}
