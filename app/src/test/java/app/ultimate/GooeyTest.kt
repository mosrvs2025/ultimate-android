package app.ultimate

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ultimate.core.GooeyMath
import app.ultimate.core.GooeyProgress
import app.ultimate.core.GooeySwitch
import app.ultimate.core.GooeyTabs
import app.ultimate.core.MotionPrefs
import app.ultimate.core.SuccessCheck
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Star

class GooeyMathTest {
    @Test fun motionIsOffOnlyAtZeroScale() {
        assertFalse(MotionPrefs.on(0f)); assertTrue(MotionPrefs.on(1f)); assertTrue(MotionPrefs.on(0.5f))
    }

    @Test fun thumbTravelsBetweenInsetsAndStaysInside() {
        assertEquals(16f, GooeyMath.thumbX(0f, 52f, 16f), 1e-4f)
        assertEquals(36f, GooeyMath.thumbX(1f, 52f, 16f), 1e-4f)
        assertEquals(26f, GooeyMath.thumbX(0.5f, 52f, 16f), 1e-4f)
        assertEquals(36f, GooeyMath.thumbX(1.3f, 52f, 16f), 1e-4f) // a bouncy overshoot never leaves the track
    }

    @Test fun dropletsStartOnTheRingAndEndInTheMiddle() {
        val first = GooeyMath.converge(0, 6, 0f, 10f)
        assertEquals(0f, first.x, 1e-4f); assertEquals(-10f, first.y, 1e-4f) // the first drop starts straight above
        (0 until 6).forEach { i -> assertEquals(10f, GooeyMath.converge(i, 6, 0f, 10f).getDistance(), 1e-3f) }
        (0 until 6).forEach { i -> assertEquals(Offset.Zero.getDistance(), GooeyMath.converge(i, 6, 1f, 10f).getDistance(), 1e-4f) }
    }

    @Test fun movingDropStretchesAndThinsThenRestsRound() {
        assertEquals(64f to 32f, GooeyMath.squish(64f, 32f, 0f))
        val (w, h) = GooeyMath.squish(64f, 32f, 40f)
        assertEquals(88f, w, 1e-3f); assertTrue(h < 32f && h > 26f)
        assertEquals(GooeyMath.squish(64f, 32f, 40f), GooeyMath.squish(64f, 32f, -40f)) // same either way
        assertEquals(GooeyMath.squish(64f, 32f, 96f), GooeyMath.squish(64f, 32f, 5000f))  // the stretch has a limit
    }

    @Test fun thumbSwellsOnlyMidway() {
        assertEquals(1f, GooeyMath.swell(0f), 1e-4f); assertEquals(1f, GooeyMath.swell(1f), 1e-4f)
        assertEquals(1.3f, GooeyMath.swell(0.5f), 1e-4f); assertEquals(1f, GooeyMath.swell(1.5f), 1e-4f)
    }

    @Test fun flowingDropsStayNearTheTrack() {
        for (k in 0..100) GooeyMath.flowing(k / 100f).forEach { x -> assertTrue("$x", x in -0.15f..1.15f) }
        assertEquals(3, GooeyMath.flowing(0f).size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GooeyComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchIsASwitchAndToggles() {
        var changed = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                var on by remember { mutableStateOf(false) }
                GooeySwitch(on, { on = it; changed += it }, Modifier.testTag("s"))
            }
        }
        compose.onNodeWithTag("s").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOff()
        compose.onNodeWithTag("s").performClick(); compose.onNodeWithTag("s").assertIsOn()
        compose.onNodeWithTag("s").performClick(); compose.onNodeWithTag("s").assertIsOff()
        assertEquals(listOf(true, false), changed)
    }

    @Test fun bottomBarSelectsTabsAndShowsTheBadge() {
        val picked = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                var tab by remember { mutableIntStateOf(0) }
                app.ultimate.core.GooeyNavBar(
                    listOf(
                        app.ultimate.core.GooeyNavItem("Home", Icons.Rounded.Home),
                        app.ultimate.core.GooeyNavItem("Library", Icons.Rounded.Star),
                        app.ultimate.core.GooeyNavItem("Activity", Icons.Rounded.Info, badge = true),
                    ),
                    tab, onSelect = { tab = it; picked += it },
                )
            }
        }
        compose.onNodeWithText("Home").assertIsSelected()
        compose.onNodeWithText("Activity").performClick()
        compose.onNodeWithText("Activity").assertIsSelected(); compose.onNodeWithText("Home").assertIsNotSelected()
        compose.onNodeWithText("Library").performClick()
        assertEquals(listOf(2, 1), picked)
    }

    @Test fun disabledSwitchDoesNothing() {
        var calls = 0
        compose.setContent { MaterialTheme { GooeySwitch(false, { calls++ }, Modifier.testTag("s"), enabled = false) } }
        compose.onNodeWithTag("s").performClick()
        assertEquals(0, calls)
    }

    @Test fun tabsSelectWhatYouTap() {
        compose.setContent {
            MaterialTheme {
                var tab by remember { mutableIntStateOf(0) }
                GooeyTabs(listOf("Hosts", "Snippets", "Keys"), tab, { tab = it })
            }
        }
        compose.onNodeWithText("Hosts").assertIsSelected(); compose.onNodeWithText("Keys").assertIsNotSelected()
        compose.onNodeWithText("Keys").performClick()
        compose.onNodeWithText("Keys").assertIsSelected(); compose.onNodeWithText("Hosts").assertIsNotSelected()
    }

    @Test fun progressReportsItsValue() {
        compose.setContent { MaterialTheme { GooeyProgress({ 0.4f }, Modifier.testTag("p"), live = false) } }
        compose.onNodeWithTag("p").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.4f, 0f..1f)))
    }

    @Test fun progressIgnoresNonsense() {
        compose.setContent { MaterialTheme { Column { GooeyProgress({ Float.NaN }, Modifier.testTag("nan"), live = false); GooeyProgress({ 7f }, Modifier.testTag("big"), live = false) } } }
        compose.onNodeWithTag("nan").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0f, 0f..1f)))
        compose.onNodeWithTag("big").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f, 0f..1f)))
    }

    @Test fun successCheckDraws() {
        compose.setContent { MaterialTheme { SuccessCheck(64.dp, Color(0xFF2E7D32), Modifier.testTag("ok")) } }
        compose.waitForIdle()
        compose.onNodeWithTag("ok").assertExists()
    }
}
