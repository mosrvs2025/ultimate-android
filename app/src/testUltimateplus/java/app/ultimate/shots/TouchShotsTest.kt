package app.ultimate.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.ultimate.UltimateApp
import app.ultimate.core.GlassCard
import app.ultimate.core.LiquidButton
import app.ultimate.core.UltimateTheme
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

/** The touch ring, caught about a fifth of a second after the finger lands, on a button, a card and a plain row. A rendering, not a photo. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w412dp-h915dp-notnight-xxhdpi")
class TouchShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)

    @Test fun ringsOnButtonCardAndRow() {
        assumeTrue(dir != null)
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme {
            Column(Modifier.fillMaxSize().background(Color(0xFF0A0E14)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                LiquidButton("Start", {}, Modifier.fillMaxWidth())
                GlassCard(title = "Map my devices") { Text("Tap anywhere on a card", color = Color.White, modifier = Modifier.fillMaxWidth().height(70.dp).clickable {}) }
                Text("A plain row in a list", color = Color.White, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {}.padding(20.dp))
            }
        } }
        compose.mainClock.advanceTimeBy(300)
        // One finger, three presses a moment apart: each ring is caught at a different stage.
        compose.onNodeWithText("Start").performTouchInput { down(center.copy(x = width * 0.3f)); up() }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithText("Tap anywhere on a card").performTouchInput { down(center); up() }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithText("A plain row in a list").performTouchInput { down(center.copy(x = width * 0.7f)); up() }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val out = dir!!.apply { mkdirs() }.resolve("touch-rings.png")
        compose.onRoot().captureRoboImage(out.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }
}
