package app.ultimate.shots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ultimate.UltimateApp
import app.ultimate.core.UltimateTheme
import app.ultimate.health.ActivityContent
import app.ultimate.health.ActivityHomeCard
import app.ultimate.health.ActivityLoaded
import app.ultimate.health.ActivityMath
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

/**
 * The Activity page and the Home card, drawn from a made-up day shaped like a big walking day (about 27,000 steps) with a
 * made-up usual day behind it. Renderings, not photos: nothing here came from a real Health Connect.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = UltimateApp::class, qualifiers = "w412dp-h2300dp-notnight-xxhdpi")
class ActivityShotsTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = System.getProperty("screenshots.dir")?.let(::File)

    private val day = 20_000L

    /** Steps by the minute: a hundred or so a minute while walking, in stretches like a long day on foot. */
    private fun minutes(scale: Double = 1.0): IntArray = IntArray(ActivityMath.MINUTES).also { a ->
        listOf(
            Triple(6 * 60 + 20, 7 * 60, 85), Triple(7 * 60 + 20, 7 * 60 + 30, 60), Triple(8 * 60, 9 * 60, 105), Triple(9 * 60 + 5, 10 * 60, 100),
            Triple(10 * 60 + 5, 11 * 60, 85), Triple(11 * 60, 12 * 60, 95), Triple(12 * 60, 13 * 60, 90), Triple(13 * 60, 14 * 60, 75),
            Triple(14 * 60 + 10, 15 * 60, 70), Triple(15 * 60 + 40, 15 * 60 + 50, 60), Triple(17 * 60, 18 * 60, 125), Triple(18 * 60 + 5, 19 * 60, 100),
            Triple(19 * 60 + 5, 20 * 60, 95), Triple(20 * 60 + 10, 21 * 60, 75), Triple(21 * 60 + 5, 21 * 60 + 25, 55),
        ).forEach { (from, to, rate) -> for (m in from until to) a[m] = (rate * scale).toInt() }
    }

    private fun loaded(): ActivityLoaded {
        val goals = ActivityMath.Goals()
        val m = minutes()
        val steps = m.sum().toLong()
        val raw = ActivityMath.Raw(day, steps, steps * 0.77, 987.0, 2_229.0, 1.0, m, null, listOf(ActivityMath.Session(17 * 60, 18 * 60 + 10, "Walking")), null, listOf("com.sec.android.app.shealth"))
        // Twenty earlier days at about a third of today, so the usual day is a lower dashed line.
        val past = (1L..20L).map { n ->
            val pm = minutes(0.33); val h = ActivityMath.hours(pm)
            ActivityMath.Past(day - n, pm.sum().toLong().let { if (n == 2L) 4_100 else if (n == 4L) 11_800 else it }, null, null, null, h)
        }
        val nine34pm = LocalDate.ofEpochDay(day).atTime(LocalTime.of(21, 34)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val built = ActivityMath.build(raw.let { ActivityMath.Raw(it.day, it.steps, it.metres, it.activeKcal, it.totalKcal, it.floors, it.minuteSteps, it.hourSteps, it.sessions, nine34pm - 8 * 60_000, it.sources) }, past, goals, 21 * 60 + 34, true)
        return ActivityLoaded(ActivityLoaded.Status.Ready, built, ActivityMath.week(past, built, goals, day), goals, miles = true, canWorkouts = true, canTotal = true, readAt = nine34pm)
    }

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        assumeTrue(dir != null)
        compose.mainClock.autoAdvance = false
        compose.setContent { UltimateTheme { content() } }
        compose.mainClock.advanceTimeBy(1500)
        val out = dir!!.apply { mkdirs() }.resolve(name)
        compose.onRoot().captureRoboImage(out.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record, recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }

    @Test fun activityPage() = shot("activity-page.png") { ActivityContent(loaded(), {}, {}, {}, {}) }

    @Test fun homeCard() = shot("activity-home-card.png") { Box(Modifier.padding(16.dp)) { ActivityHomeCard(loaded()) {} } }

    @Test fun needsPermission() = shot("activity-allow.png") {
        ActivityContent(ActivityLoaded(ActivityLoaded.Status.NeedsPermission, null, null, ActivityMath.Goals(), true, false, false, 0L), {}, {}, {}, {})
    }
}
