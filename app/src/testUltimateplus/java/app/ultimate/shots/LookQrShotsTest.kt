package app.ultimate.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ultimate.UltimateApp
import app.ultimate.launcher.LauncherSettings
import app.ultimate.launcher.LookCode
import app.ultimate.launcher.Looks
import app.ultimate.send.QrCode
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

/** What the QR of a look looks like on the dark card it is shown on. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w352dp-h400dp-notnight-xxhdpi")
class LookQrShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)

    @Test fun qr() {
        assumeTrue(dir != null)
        compose.setContent { Box(Modifier.background(Color(0xFF101620)).padding(24.dp)) { QrCode(LookCode.encode(Looks.terminal.apply(LauncherSettings())), Modifier.size(260.dp)) } }
        compose.onRoot().captureRoboImage(dir!!.apply { mkdirs() }.resolve("look-qr.png").path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
