package app.ultimate.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ultimate.UltimateApp
import app.ultimate.core.UltimateTheme
import app.ultimate.mesh.HouseLayout
import app.ultimate.mesh.HouseMapCard
import app.ultimate.mesh.MeshManager
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

/** The device map card with a made-up room of five devices, one echoed measurement. A rendering, not a photo. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w412dp-h915dp-notnight-xxhdpi")
class HouseShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)

    @Test fun deviceMap() {
        assumeTrue(dir != null)
        val truth = mapOf("fold" to HouseLayout.Point(0.0, 0.0), "flip" to HouseLayout.Point(3.2, 0.4), "s21" to HouseLayout.Point(1.1, 2.7),
            "s10" to HouseLayout.Point(4.0, 3.5), "quest" to HouseLayout.Point(2.2, 1.3))
        val names = mapOf("fold" to "Z Fold6 (this phone)", "flip" to "Z Flip6", "s21" to "S21 Ultra", "s10" to "S10+", "quest" to "Quest 3S")
        val ids = truth.keys.toList()
        val noise = listOf(0.03, -0.02, 0.04, -0.01, 0.02, -0.03, 0.01, 0.0, -0.02, 0.03)
        var k = 0
        val ms = ids.flatMapIndexed { i, a -> ids.drop(i + 1).map { b -> HouseLayout.Measure(a, b, truth.getValue(a).to(truth.getValue(b)) + noise[k++] + if (a == "flip" && b == "s10") 1.4 else 0.0) } }
        val state = MeshManager.HouseState.Done(HouseLayout.solve(ids, ms), names, ms, emptyList(), 1L)
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme { Box(Modifier.fillMaxSize().background(Color(0xFF0A0E14)).padding(16.dp)) { HouseMapCard(state, peers = 4, lends = true, onMap = {},
            you = app.ultimate.mesh.WhereAmI.Estimate(HouseLayout.Point(2.9, 0.9), 0.8, "flip", false, 4), tracking = true) } } }
        compose.mainClock.advanceTimeBy(2_000)
        val out = dir!!.apply { mkdirs() }.resolve("house-map.png")
        compose.onRoot().captureRoboImage(out.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }
}
