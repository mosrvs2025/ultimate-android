package app.ultimate

import androidx.test.core.app.ApplicationProvider
import app.ultimate.context.HabitStore
import app.ultimate.context.Morph
import app.ultimate.context.Motion
import app.ultimate.context.MotionClassifier
import app.ultimate.context.UiMorph
import app.ultimate.home.HomeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UltimateApp::class)
class ContextAdaptTest {
    private val rate = 50f
    private fun signal(seconds: Float, f: (Int, Random) -> Float, seed: Long = 3): FloatArray { val r = Random(seed); return FloatArray((seconds * rate).toInt()) { 9.81f + f(it, r) } }

    @Test fun aPhoneOnATableIsStill() {
        assertEquals(Motion.Still, MotionClassifier.classify(signal(2.5f, { _, r -> r.nextGaussian().toFloat() * 0.02f }), rate))
    }

    @Test fun aPhoneInAHandIsHeldNotWalking() {
        // Slow drift plus tremor: lively, but no steady step rhythm.
        val held = signal(2.5f, { i, r -> 0.5f * sin(2 * PI * 0.4 * i / rate).toFloat() + r.nextGaussian().toFloat() * 0.25f })
        assertEquals(Motion.Held, MotionClassifier.classify(held, rate))
    }

    @Test fun walkingAtTypicalPacesIsWalking() {
        for (hz in listOf(1.5f, 1.8f, 2.0f, 2.4f, 3.0f)) {
            val w = signal(2.5f, { i, r -> 2.2f * sin(2 * PI * hz * i / rate).toFloat() + r.nextGaussian().toFloat() * 0.2f })
            assertEquals("at $hz steps/s", Motion.Walking, MotionClassifier.classify(w, rate))
        }
    }

    @Test fun aStrongButNotStepLikeShakeIsNotWalking() {
        val slow = signal(2.5f, { i, _ -> 3f * sin(2 * PI * 0.6 * i / rate).toFloat() })
        val buzz = signal(2.5f, { i, _ -> 3f * sin(2 * PI * 9.0 * i / rate).toFloat() })
        assertEquals(Motion.Held, MotionClassifier.classify(slow, rate))
        assertEquals(Motion.Held, MotionClassifier.classify(buzz, rate))
    }

    @Test fun tooLittleDataSaysStill() {
        assertEquals(Motion.Still, MotionClassifier.classify(FloatArray(3), rate))
        assertEquals(Motion.Still, MotionClassifier.classify(FloatArray(100), 0f))
    }

    @Test fun onlyWalkingBendsTheTilesAndNothingIsRemoved() {
        assertEquals(UiMorph.NORMAL, UiMorph.forMotion(Motion.Still)); assertEquals(UiMorph.NORMAL, UiMorph.forMotion(Motion.Held))
        val g: Morph = UiMorph.forMotion(Motion.Walking)
        assertTrue(g.glance && g.tileMinDp > 0 && g.note != null)
        assertEquals(0, UiMorph.NORMAL.tileMinDp)
    }

    @Test fun aSuggestionGoesAfterThePinsOnceAndOnlyIfItExists() {
        val all = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("p", "d", "a", "b"), HomeLayout.withSuggestion(listOf("p", "a", "b", "d"), listOf("p"), "d", all + "p"))
        assertEquals(listOf("a", "b"), HomeLayout.withSuggestion(listOf("a", "b"), emptyList(), null, all))
        assertEquals(listOf("a", "b"), HomeLayout.withSuggestion(listOf("a", "b"), emptyList(), "zzz", all))
        assertEquals(listOf("a", "b"), HomeLayout.withSuggestion(listOf("a", "b"), listOf("b"), "b", all))
        assertEquals(listOf("c", "a", "b"), HomeLayout.withSuggestion(listOf("a", "b"), emptyList(), "c", all))
    }

    @Test fun habitsAreOffByDefaultLearnNothingUntilOnAndForgetEverything() {
        val ctx = ApplicationProvider.getApplicationContext<UltimateApp>()
        ctx.getSharedPreferences("habits", 0).edit().clear().commit()
        val h = HabitStore(ctx)
        assertFalse(h.enabled.value)
        h.record("studio"); assertEquals(0, h.count.value)
        assertNull(h.suggest(listOf("studio")))
        h.setEnabled(true)
        repeat(7) { h.record("terminal", System.currentTimeMillis() + it) }
        assertEquals(7, h.count.value)
        Thread.sleep(200)
        assertEquals("it persists across restarts", 7, HabitStore(ctx).count.value)
        h.forget(); assertEquals(0, h.count.value); Thread.sleep(100)
        assertEquals(0, HabitStore(ctx).count.value)
        h.setEnabled(true); h.record("files"); h.setEnabled(false)
        assertEquals("turning it off deletes what it learned", 0, h.count.value)
        assertEquals(0, HabitStore(ctx).count.value)
    }
}
