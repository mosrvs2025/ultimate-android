package app.ultimate.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.ultimate.UltimateApp
import app.ultimate.core.HeartPanel
import app.ultimate.core.UltimateTheme
import app.ultimate.fusion.FusedPath
import app.ultimate.movement.LiveMap
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
import kotlin.math.cos
import kotlin.math.sin

/** The live map (a made-up loop with a hill, paced slow-fast-slow) flat and tilted, and the beating heart. Renderings, not photos. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w412dp-h915dp-notnight-xxhdpi")
class MovementMapShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)

    private fun loop(): FusedPath {
        val n = 300
        val t = DoubleArray(n) { it.toDouble() }
        // A rounded loop about 120 m by 70 m, walked slowly, then fast down one side, then slowly again.
        val e = DoubleArray(n); val no = DoubleArray(n)
        var a = 0.0
        for (i in 0 until n) { val speed = if (i in 110..170) 2.4 else 1.0; a += speed / 300.0 * 2 * Math.PI / 1.0; e[i] = 60 * cos(a) + 8 * sin(3 * a); no[i] = 35 * sin(a) }
        return FusedPath(t, e, no, Array(n) { "GPS + steps" }, "north-up (GPS)", null).apply {
            anchor = doubleArrayOf(51.5007, -0.1246)
            height = DoubleArray(n) { 6 * (1 - cos(e[it] / 60 * Math.PI)) }
        }
    }

    private fun save(name: String) {
        val out = dir!!.apply { mkdirs() }.resolve("$name.png")
        compose.onRoot().captureRoboImage(out.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }

    @Test fun mapFlatAndTilted() {
        assumeTrue(dir != null)
        ApplicationProvider.getApplicationContext<UltimateApp>().getSharedPreferences("movement", 0).edit().putBoolean("tiles", false).commit()
        val p = loop()
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme {
            Column(Modifier.fillMaxSize().background(Color(0xFF0A0E14)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HeartPanel(118)
                LiveMap(p, doubleArrayOf(51.5007 + 0.0003, -0.1246 + 0.0009, 6.0), live = true, modifier = Modifier.fillMaxWidth().height(360.dp))
            }
        } }
        compose.mainClock.advanceTimeBy(700)
        save("movement-map-2d")
        compose.onNodeWithText("3D").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        save("movement-map-3d")
    }
}
