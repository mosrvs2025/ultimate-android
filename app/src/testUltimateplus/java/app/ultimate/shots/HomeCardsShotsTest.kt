package app.ultimate.shots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.ultimate.UltimateApp
import app.ultimate.core.UltimateTheme
import app.ultimate.health.ActivityLoaded
import app.ultimate.health.ActivityMath
import app.ultimate.health.HeartBrief
import app.ultimate.health.HeartGlance
import app.ultimate.launcher.CardData
import app.ultimate.launcher.HomeApp
import app.ultimate.launcher.HomeCard
import app.ultimate.launcher.LauncherHome
import app.ultimate.launcher.LauncherRepo
import app.ultimate.launcher.LookPreset
import app.ultimate.launcher.Looks
import app.ultimate.mesh.CamNodes
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The launcher's home cards (activity rings, heart rate, shared cameras) drawn in three looks. Renderings with made-up numbers, not photos. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w352dp-h780dp-notnight-xxhdpi")
class HomeCardsShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)
    private val day = 20_000L

    private fun minutes(scale: Double = 1.0): IntArray = IntArray(ActivityMath.MINUTES).also { a ->
        listOf(Triple(6 * 60 + 20, 7 * 60, 85), Triple(8 * 60, 9 * 60, 105), Triple(10 * 60 + 5, 11 * 60, 85), Triple(12 * 60, 13 * 60, 90), Triple(17 * 60, 18 * 60, 125), Triple(19 * 60 + 5, 20 * 60, 95))
            .forEach { (from, to, rate) -> for (m in from until to) a[m] = (rate * scale).toInt() }
    }

    private fun activity(): ActivityLoaded {
        val goals = ActivityMath.Goals(); val m = minutes(); val steps = m.sum().toLong()
        val at = LocalDate.ofEpochDay(day).atTime(LocalTime.of(20, 14)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val raw = ActivityMath.Raw(day, steps, steps * 0.77, 612.0, 2_229.0, 1.0, m, null, listOf(ActivityMath.Session(17 * 60, 18 * 60 + 10, "Walking")), at - 3 * 60_000, listOf("com.sec.android.app.shealth"))
        val past = (1L..20L).map { n -> val pm = minutes(0.6); ActivityMath.Past(day - n, pm.sum().toLong(), null, null, null, ActivityMath.hours(pm)) }
        val built = ActivityMath.build(raw, past, goals, 20 * 60 + 14, true)
        return ActivityLoaded(ActivityLoaded.Status.Ready, built, ActivityMath.week(past, built, goals, day), goals, miles = true, canWorkouts = true, canTotal = true, readAt = at)
    }

    private fun show(look: LookPreset, name: String, heartAgoMin: Long = 12) {
        assumeTrue(dir != null)
        val repo = LauncherRepo(ApplicationProvider.getApplicationContext())
        repo.seed(listOf("Gmail", "Phone", "Photos", "Maps", "Chrome").map { HomeApp(it.lowercase(), it) }, listOf("gmail", "phone", "photos"))
        repo.update { look.apply(it).copy(cards = HomeCard.entries.toSet(), favouritesShown = 3) }
        val now = System.currentTimeMillis()
        repo.seedCards(CardData(
            activity = activity(),
            heart = HeartGlance.Result.Ready(HeartBrief(72, now - heartAgoMin * 60_000, 58, now - 5 * 3_600_000, 51, 133)),
            cameras = listOf(CamNodes.Node("k", "Kitchen", false), CamNodes.Node("g", "Garden", true)),
            at = now,
        ))
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme { LauncherHome(repo, 0, {}, autoLoad = false) } }
        compose.mainClock.advanceTimeBy(2_500)
        compose.onRoot().captureRoboImage(dir!!.apply { mkdirs() }.resolve("$name.png").path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }

    @Test fun aurora() = show(Looks.aurora, "cards-aurora")
    @Test fun terminal() = show(Looks.terminal, "cards-terminal")
    @Test fun paper() = show(Looks.paper, "cards-paper")
}
