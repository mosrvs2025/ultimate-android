package app.ultimate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.core.app.ApplicationProvider
import app.ultimate.core.FaceMath
import app.ultimate.core.UltimateTheme
import app.ultimate.focus.Check
import app.ultimate.focus.FocusClassifier
import app.ultimate.focus.FocusMath
import app.ultimate.focus.FocusPolicy
import app.ultimate.focus.FocusScreen
import app.ultimate.focus.FocusStore
import app.ultimate.focus.Session
import app.ultimate.focus.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class FocusMathTest {
    private val me = "app.ultimate"

    @Test fun appsAreJudgedByWhatTheyAre() {
        assertEquals(Verdict.OnTask, FocusClassifier.classify("com.google.android.apps.docs", setOf("com.google.android.apps.docs"), null, me).first)
        assertEquals(Verdict.OnTask, FocusClassifier.classify(me, emptySet(), null, me).first)
        assertEquals(Verdict.Unsure, FocusClassifier.classify("com.sec.android.app.launcher", emptySet(), null, me, setOf("com.sec.android.app.launcher")).first)
        assertEquals(Verdict.Drift to 0.95f, FocusClassifier.classify("com.zhiliaoapp.musically", emptySet(), null, me))
        assertEquals(Verdict.Drift to 0.9f, FocusClassifier.classify("com.some.game", emptySet(), FocusClassifier.CATEGORY_GAME, me))
        assertEquals(Verdict.Drift, FocusClassifier.classify("com.some.news", emptySet(), FocusClassifier.CATEGORY_NEWS, me).first)
        assertEquals(Verdict.Unsure, FocusClassifier.classify("com.spotify.music", emptySet(), FocusClassifier.CATEGORY_AUDIO, me).first) // music is fine to work to
        assertEquals(Verdict.OnTask, FocusClassifier.classify("com.instagram.android", setOf("com.instagram.android"), null, me).first) // your pick wins
    }

    @Test fun strongDriftNudgesSoonerAndUnsureResets() {
        val p = FocusPolicy(cooldownMs = 120_000)
        fun c(t: Long, v: Verdict, s: Float = 0f) = Check(t * 1000, "x", v, s)
        // A feed: two drifting checks in a row.
        assertFalse(p.next(c(0, Verdict.Drift, 0.9f))); assertTrue(p.next(c(30, Verdict.Drift, 0.9f)))
        // Still drifting, but in the quiet spell after a nudge: nothing.
        assertFalse(p.next(c(60, Verdict.Drift, 0.9f))); assertFalse(p.next(c(90, Verdict.Drift, 0.9f)))
        // Still drifting when the quiet spell is over: the next check nudges again.
        assertTrue(p.next(c(160, Verdict.Drift, 0.9f)))
        // An unsure check in between starts the count again, and the quiet spell still applies.
        assertFalse(p.next(c(290, Verdict.Drift, 0.9f))); assertFalse(p.next(c(320, Verdict.Unsure)))
        assertFalse(p.next(c(350, Verdict.Drift, 0.9f))); assertTrue(p.next(c(380, Verdict.Drift, 0.9f)))
        // A mild drift needs four in a row.
        val mild = FocusPolicy()
        assertEquals(listOf(false, false, false, true), (0 until 4).map { mild.next(c(it * 30L, Verdict.Drift, 0.5f)) })
        assertEquals(listOf(2, 3, 4), listOf(0.9f, 0.7f, 0.3f).map { mild.needed(it) })
    }

    @Test fun countdownReadsLikeAClock() {
        assertEquals("30:00", FocusMath.countdown(30 * 60_000L)); assertEquals("0:59", FocusMath.countdown(58_400))
        assertEquals("1:30:00", FocusMath.countdown(90 * 60_000L)); assertEquals("0:00", FocusMath.countdown(0))
    }

    @Test fun pausesDoNotCount() {
        val s = Session(1, "Essay", 0, 30 * 60_000L, pausedMs = 5 * 60_000L)
        assertEquals(10 * 60_000L, s.elapsed(15 * 60_000L)); assertEquals(20 * 60_000L, s.left(15 * 60_000L))
        val paused = s.copy(pausedAt = 15 * 60_000L)
        assertEquals(10 * 60_000L, paused.elapsed(40 * 60_000L)) // the clock stands still while paused
        assertEquals(0L, s.left(99 * 60_000L))
    }

    @Test fun timelineMergesRunsAndShowsGaps() {
        val s = Session(1, "t", 0, 600_000, checks = listOf(Check(30_000, "a", Verdict.OnTask), Check(60_000, "a", Verdict.OnTask), Check(90_000, "b", Verdict.Drift), Check(300_000, "a", Verdict.OnTask)))
        val segs = FocusMath.timeline(s, 330_000)
        assertEquals(listOf(Verdict.OnTask, Verdict.Drift, null, Verdict.OnTask, null), segs.map { it.verdict })
        assertEquals(0L to 60_000L, segs[0].start to segs[0].end)
        assertEquals(90_000L to 270_000L, segs[2].start to segs[2].end) // nothing checked: screen off or paused
        assertEquals(330_000L, segs.last().end)
    }

    @Test fun shareIgnoresUnsureAndNudgesVary() {
        val s = Session(1, "t", 0, 1, checks = listOf(Check(1, "a", Verdict.OnTask), Check(2, "b", Verdict.Drift), Check(3, "c", Verdict.Unsure), Check(4, "a", Verdict.OnTask)))
        assertEquals(2f / 3, FocusMath.onTaskShare(s)!!, 1e-4f)
        assertNull(FocusMath.onTaskShare(s.copy(checks = emptyList())))
        val lines = (0 until 5).map { FocusMath.nudge("Essay", "Instagram", it) }
        assertEquals(5, lines.toSet().size); assertTrue(lines.all { "Essay" in it })
        assertTrue(FocusMath.nudge("", "X", 0).contains("your task"))
    }

    @Test fun eyesLookTowardsTheFingerButStayInTheirSockets() {
        assertEquals(Offset.Zero, FaceMath.gaze(Offset(100f, 100f), null, 50f))
        val near = FaceMath.gaze(Offset(100f, 100f), Offset(125f, 100f), 50f)
        assertEquals(0.5f, near.x, 1e-4f); assertEquals(0f, near.y, 1e-4f)
        val far = FaceMath.gaze(Offset(100f, 100f), Offset(100f, 5000f), 50f)
        assertEquals(1f, far.getDistance(), 1e-4f); assertEquals(1f, far.y, 1e-4f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun aSessionSurvivesAndEndsInHistory() {
        java.io.File(context.filesDir, "focus.json").delete()
        val store = FocusStore(context)
        store.start("Write the intro", 25, setOf("com.google.android.apps.docs"), now = 1_000)
        store.addCheck(Check(31_000, "com.google.android.apps.docs", Verdict.OnTask))
        store.addCheck(Check(61_000, "com.instagram.android", Verdict.Drift, 0.9f))
        store.addNudge(61_000)
        store.pause(now = 70_000); store.resume(now = 100_000)
        // A fresh store (Ultimate closed and opened again) finds the session as it was.
        val again = FocusStore(context)
        val s = again.current.value!!
        assertEquals("Write the intro", s.task); assertEquals(25 * 60_000L, s.plannedMs); assertEquals(30_000L, s.pausedMs)
        assertEquals(listOf(Verdict.OnTask, Verdict.Drift), s.checks.map { it.verdict }); assertEquals(listOf(61_000L), s.nudges)
        assertEquals(setOf("com.google.android.apps.docs"), again.lastAllowed); assertEquals(25, again.lastMinutes)
        again.end(now = 200_000)
        assertNull(again.current.value); assertEquals(1, again.history.value.size)
        assertEquals(200_000L, FocusStore(context).history.value.single().endedAt)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UltimateApp::class)
class FocusScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun askForTheTaskThenRunTheCountdown() {
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        java.io.File(app.filesDir, "focus.json").delete()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val store = FocusStore(app)
        compose.setContent { UltimateTheme { FocusScreen(store) {} } }
        compose.onNodeWithText("What are we working on?").assertExists()
        compose.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToNode(androidx.compose.ui.test.hasText("Start focusing"))
        compose.onNodeWithText("Start focusing").assertIsNotEnabled()
        compose.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("Write the intro")
        compose.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToNode(androidx.compose.ui.test.hasText("Start focusing"))
        compose.onNodeWithText("Start focusing").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Write the intro").assertExists()
        compose.onNodeWithText("Off you go. I'll keep an eye out.").assertExists()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("End session")).assertExists()
        store.end()
    }
}
