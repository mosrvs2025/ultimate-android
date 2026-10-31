package app.ultimate.shots

import android.app.NotificationManager
import android.content.Intent
import android.os.Environment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.ultimate.UltimateApp
import app.ultimate.core.ToolLaunch
import app.ultimate.core.UltimateTheme
import app.ultimate.files.FileKind
import app.ultimate.home.AboutContent
import app.ultimate.home.HomeScreen
import app.ultimate.rules.RuleParser
import app.ultimate.sshclient.Host
import app.ultimate.sshclient.HostKind
import app.ultimate.sshclient.HostsTool
import app.ultimate.storage.AppSize
import app.ultimate.storage.BigFile
import app.ultimate.storage.Folder
import app.ultimate.storage.FolderMap
import app.ultimate.storage.StorageReport
import app.ultimate.storage.StorageTool
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/**
 * Renders the app's real screens to PNG files for the README: the actual Compose code, drawn on a simulated phone with sample
 * data. They are renderings, not photos from a device. Runs only when asked: `gradle -Pscreenshots :app:testUltimateplusDebugUnitTest --tests '*ScreenshotTest'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w411dp-h891dp-notnight-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)
    private val app get() = ApplicationProvider.getApplicationContext<UltimateApp>()

    @Before fun phone() {
        assumeTrue(dir != null)
        // A 256 GB phone with 98 GB free, 78% charged, with Do Not Disturb and overlay access allowed.
        ShadowStatFs.registerStats(Environment.getDataDirectory(), 62_500_000, 23_900_000, 23_900_000)
        ShadowStatFs.registerStats(Environment.getExternalStorageDirectory(), 62_500_000, 23_900_000, 23_900_000)
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra("level", 78).putExtra("scale", 100).putExtra("plugged", 0).putExtra("status", 3))
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(true)
    }

    @After fun restoreSecurityProviders() {
        java.security.Security.getProviders().filter { it.name.contains("Conscrypt", ignoreCase = true) }.forEach { java.security.Security.removeProvider(it.name) }
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { UltimateTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
        compose.waitForIdle()
    }

    /** Saves the whole screen, or only the part of it holding the node tagged [tag] (cropped from its measured bounds). */
    private fun save(name: String, tag: String? = null) {
        val out = dir!!.apply { mkdirs() }.resolve("$name.png")
        val scale = 0.5
        compose.onRoot().captureRoboImage(out.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = scale)))
        if (tag != null) {
            val box = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val whole = android.graphics.BitmapFactory.decodeFile(out.path)
            val crop = android.graphics.Bitmap.createBitmap(whole, (box.left * scale).toInt(), (box.top * scale).toInt(), (box.width * scale).toInt().coerceAtMost(whole.width), (box.height * scale).toInt())
            out.outputStream().use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        println("SHOT>>> $name ${out.length()} bytes")
    }

    @Test fun home() {
        listOf("studio", "squeeze", "terminal", "storage", "studio", "rules", "storage", "terminal", "studio").forEach { app.insights.entered(it); app.insights.left() }
        show { HomeScreen(app, PaddingValues(0.dp)) }
        save("home")
    }

    @Test fun storage() {
        val tool = app.tools.filterIsInstance<StorageTool>().single()
        tool.last = sampleStorage()
        show { tool.ScreenWithAccess {} }
        save("storage")
    }

    @Test fun rules() {
        listOf(
            "Every night at 10pm, silence the phone and turn on do not disturb",
            "When I connect my headphones, set the volume to 40 and put it back when I disconnect",
            "When the battery drops below 15%, show a notification saying \"Battery at %battery% - time to charge\"",
        ).forEach { (RuleParser.parse(it) as RuleParser.Result.Ok).let { ok -> app.rules.add(ok.rule) } }
        val tool = app.tools.first { it.id == "rules" }
        show { tool.Screen({}, ToolLaunch()) }
        compose.onNode(hasSetTextAction()).performTextInput("when I plug in the charger, set the volume to 30% and turn on do not disturb")
        compose.onNodeWithContentDescription("Make a rule").performClick()
        compose.waitForIdle()
        save("rules")
    }

    @Test fun hosts() {
        val tool = app.tools.filterIsInstance<HostsTool>().single()
        val now = System.currentTimeMillis(); val h = 3_600_000L
        listOf(
            Host("1", "acme-api-prod", "api.acme.example", user = "deploy", group = "Work", tags = listOf("api", "ubuntu"), kind = HostKind.Linux, lastConnected = now - 2 * h),
            Host("2", "acme-db-prod", "db.acme.example", user = "deploy", group = "Work", tags = listOf("postgres"), kind = HostKind.Linux, lastConnected = now - 26 * h),
            Host("3", "build-box", "10.0.4.20", user = "ci", group = "Work", kind = HostKind.Linux, lastConnected = now - 70 * h),
            Host("4", "naspi", "192.168.2.178", user = "pi", group = "Home", tags = listOf("nas"), kind = HostKind.Pi, lastConnected = now - 5 * h),
            Host("5", "openwrt", "192.168.1.1", user = "root", group = "Home", kind = HostKind.Router, lastConnected = now - 200 * h),
            Host("6", "studio-mac", "studio.local", user = "sam", kind = HostKind.Mac),
        ).forEach { tool.vault.saveHost(it) }
        show { tool.Screen({}, ToolLaunch()) }
        save("hosts")
    }

    /** A folder that holds only files, like Movies/Sora: the map shows each file instead of one block. */
    @Test fun storageFolder() {
        val sora = Folder("Sora", "/sdcard/Movies/Sora")
        fun f(name: String, mb: Long, kind: FileKind) = BigFile(sora.path + "/" + name, name, mb * 1_000_000, System.currentTimeMillis(), kind).also { sora.addFile(it); sora.own += it.bytes; sora.ownFiles++; sora.bytes += it.bytes; sora.files++ }
        f("sora-city-walk.mp4", 612, FileKind.Video); f("sora-ocean-4k.mp4", 431, FileKind.Video); f("sora-forest.mp4", 287, FileKind.Video)
        f("sora-desert-loop.mp4", 150, FileKind.Video); f("storyboard.pdf", 64, FileKind.Document); f("sora-cat.mp4", 52, FileKind.Video); f("notes.txt", 1, FileKind.Document)
        sora.children["Drafts"] = Folder("Drafts", sora.path + "/Drafts").also { it.bytes = 74_000_000; it.files = 9; sora.bytes += it.bytes }
        show { androidx.compose.foundation.layout.Column(Modifier.wrapContentSize(androidx.compose.ui.Alignment.TopStart).testTag("frame").background(MaterialTheme.colorScheme.background).padding(16.dp)) { androidx.compose.material3.Text("All  ›  Movies  ›  Sora", color = MaterialTheme.colorScheme.primary); FolderMap(sora, { _, _ -> }, { }) } }
        compose.mainClock.advanceTimeBy(1200)
        save("storage-folder", "frame")
    }

    @Test fun about() {
        // The simulator has no install date; say it was installed ten days ago and updated yesterday.
        org.robolectric.Shadows.shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).apply {
            firstInstallTime = System.currentTimeMillis() - 10 * 86_400_000L; lastUpdateTime = System.currentTimeMillis() - 86_400_000L
        }
        show { AboutContent(app) {} }
        save("about")
    }

    private fun sampleStorage(): StorageReport {
        val gb = 1_000_000_000L
        val root = Folder("Internal storage", "/sdcard")
        fun add(parent: Folder, name: String, size: Double, files: Int): Folder = Folder(name, parent.path + "/" + name).also { it.bytes = (size * gb).toLong(); it.files = files; parent.children[name] = it }
        val dcim = add(root, "DCIM", 41.0, 9800).also { add(it, "Camera", 38.6, 8100); add(it, "Screenshots", 1.9, 1700) }
        add(root, "WhatsApp", 17.4, 21000); add(root, "Movies", 11.2, 140); add(root, "Download", 9.6, 410)
        add(root, "Music", 6.3, 1900); add(root, "Pictures", 4.8, 3600); add(root, "Documents", 2.2, 780); add(root, "Android", 1.5, 90)
        root.bytes = root.children.values.sumOf { it.bytes }; root.files = root.children.values.sumOf { it.files }
        check(dcim.bytes > 0)
        fun big(name: String, size: Double, kind: FileKind, months: Int) = BigFile("/sdcard/$name", name, (size * gb).toLong(), System.currentTimeMillis() - months * 2_592_000_000L, kind)
        val biggest = listOf(
            big("Movies/trip-2024-4k.mp4", 3.9, FileKind.Video, 14), big("DCIM/Camera/20250811_190455.mp4", 2.7, FileKind.Video, 3), big("Download/ubuntu-24.04.iso", 2.6, FileKind.Other, 11),
            big("WhatsApp/Media/VID-20250302-WA0011.mp4", 1.4, FileKind.Video, 8), big("Download/backup-phone.zip", 1.2, FileKind.Archive, 9), big("DCIM/Camera/20250614_121233.mp4", 1.1, FileKind.Video, 4),
        )
        return StorageReport(
            at = System.currentTimeMillis(), total = 256 * gb, free = 98 * gb, root = root,
            byKind = mapOf(FileKind.Video to 54 * gb, FileKind.Image to 33 * gb, FileKind.Audio to 7 * gb, FileKind.Document to 3 * gb, FileKind.Apk to 3 * gb, FileKind.Archive to 4 * gb, FileKind.Other to 6 * gb),
            biggest = biggest, forgotten = biggest.filter { it.modified < System.currentTimeMillis() - 365 * 86_400_000L },
            apps = listOf(AppSize("com.instagram.android", "Instagram", 330_000_000, 4_200_000_000, 1_900_000_000), AppSize("com.whatsapp", "WhatsApp", 190_000_000, 2_400_000_000, 600_000_000), AppSize("com.spotify.music", "Spotify", 260_000_000, 1_100_000_000, 2_300_000_000)),
            trend = (0 until 10).map { i -> (System.currentTimeMillis() - (9 - i) * 86_400_000L) to ((150 + i * 0.9) * gb).toLong() },
        )
    }
}
