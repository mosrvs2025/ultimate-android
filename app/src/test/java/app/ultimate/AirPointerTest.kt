package app.ultimate

import app.ultimate.air.AirPointer
import app.ultimate.air.AirPointer.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class AirPointerTest {
    private val w = 1080f; private val h = 2400f

    /** Holds the finger at (x, y) for [ms] at 30 frames a second, with [pinch], returning any actions. */
    private fun hold(p: AirPointer, x: Float, y: Float, pinch: Float, ms: Long, t0: Long, jitter: Float = 0f, rnd: Random = Random(1)): Pair<List<Action>, Long> {
        val out = ArrayList<Action>(); var t = t0
        while (t < t0 + ms) { p.feed(x + (rnd.nextFloat() - 0.5f) * jitter, y + (rnd.nextFloat() - 0.5f) * jitter, pinch, t)?.let(out::add); t += 33 }
        return out to t
    }

    @Test fun theMiddleOfThePictureReachesTheWholeScreen() {
        val p = AirPointer(w, h)
        hold(p, 0.5f, 0.5f, 1f, 500, 0)
        assertEquals(w / 2, p.x, 5f); assertEquals(h / 2, p.y, 5f)
        hold(p, 0.2f, 0.8f, 1f, 1_500, 1_000) // the edge of the reach is the corner of the screen
        assertEquals(0f, p.x, 5f); assertEquals(h, p.y, 10f)
    }

    @Test fun aSteadyHandGivesASteadyCursor() {
        val p = AirPointer(w, h)
        val xs = ArrayList<Float>(); var t = 0L; val rnd = Random(7)
        repeat(90) { p.feed(0.5f + (rnd.nextFloat() - 0.5f) * 0.01f, 0.5f, 1f, t); xs += p.x; t += 33 }
        val spread = xs.drop(30).let { it.max() - it.min() }
        // Raw jitter of 1% of the picture is ~18 px on screen; smoothed, it should be a fraction of that.
        assertTrue("spread $spread px", spread < 8f)
    }

    @Test fun aQuickPinchIsATapWhereItStarted() {
        val p = AirPointer(w, h)
        var (_, t) = hold(p, 0.5f, 0.5f, 1f, 500, 0)
        val (a1, t1) = hold(p, 0.5f, 0.5f, 0.1f, 200, t); t = t1
        val (a2, _) = hold(p, 0.5f, 0.5f, 0.8f, 100, t)
        val tap = (a1 + a2).single() as Action.Tap
        assertEquals(w / 2, tap.x, 10f)
    }

    @Test fun aHeldPinchIsALongPressOnce() {
        val p = AirPointer(w, h)
        var (_, t) = hold(p, 0.5f, 0.5f, 1f, 500, 0)
        val (a1, t1) = hold(p, 0.5f, 0.5f, 0.1f, 1_200, t); t = t1
        val (a2, _) = hold(p, 0.5f, 0.5f, 0.8f, 100, t)
        assertEquals(1, (a1 + a2).size); assertTrue((a1 + a2).single() is Action.LongPress)
    }

    @Test fun aPinchThatMovesIsADrag() {
        val p = AirPointer(w, h)
        var (_, t) = hold(p, 0.5f, 0.6f, 1f, 500, 0)
        hold(p, 0.5f, 0.6f, 0.1f, 100, t).let { t = it.second }
        val (a, t2) = hold(p, 0.5f, 0.35f, 0.1f, 400, t); t = t2 // move up while pinched: a scroll
        val drag = (a + hold(p, 0.5f, 0.35f, 0.8f, 100, t).first).single() as Action.Drag
        assertTrue(drag.y0 - drag.y1 > 500)
        assertTrue(abs(drag.x1 - drag.x0) < 30)
    }

    @Test fun aPinchNearTheThresholdDoesNotFlicker() {
        val p = AirPointer(w, h)
        var (_, t) = hold(p, 0.5f, 0.5f, 1f, 300, 0)
        hold(p, 0.5f, 0.5f, 0.2f, 100, t).let { t = it.second }
        // Opening a little (0.3) stays pinched; only past 0.4 does it let go.
        val (a, _) = hold(p, 0.5f, 0.5f, 0.32f, 300, t)
        assertTrue(a.isEmpty()); assertTrue(p.pinched)
        assertNull(p.feed(0.5f, 0.5f, 1f, t + 2_000).takeIf { it !is Action.LongPress && it != null })
    }
}

class ShortcutHoldTest {
    private fun run(h: app.ultimate.air.ShortcutHold, sign: String?, ms: Long, t0: Long, x: Float = 500f, drift: Float = 0f): Pair<List<String>, Long> {
        val out = ArrayList<String>(); var t = t0; var i = 0
        while (t < t0 + ms) { h.feed(sign, x + drift * i++, 500f, t)?.let(out::add); t += 33 }
        return out to t
    }

    @Test fun aShapeHeldStillForASecondFiresOnce() {
        val h = app.ultimate.air.ShortcutHold()
        val (a, _) = run(h, "Open_Palm", 1_400, 0)
        assertEquals(listOf("Open_Palm"), a)
    }

    @Test fun aBriefShapeDoesNothing() {
        val h = app.ultimate.air.ShortcutHold()
        val (a, t) = run(h, "Closed_Fist", 500, 0)
        assertTrue(a.isEmpty())
        assertTrue(run(h, null, 500, t).first.isEmpty())
    }

    @Test fun movingTheHandCancelsIt() {
        // Moving the cursor while the hand looks like a fist (normal pointing around) never goes Home.
        val h = app.ultimate.air.ShortcutHold()
        assertTrue(run(h, "Closed_Fist", 3_000, 0, drift = 6f).first.isEmpty())
    }

    @Test fun theRingFillsAsYouHold() {
        val h = app.ultimate.air.ShortcutHold()
        run(h, "Open_Palm", 500, 0)
        val p = h.progress!!
        assertEquals("Open_Palm", p.first); assertTrue(p.second in 0.4f..0.6f)
    }
}

class ShapeLearnerTest {
    private val rnd = java.util.Random(3)
    // Two clearly different "shapes": a cluster around +1s and one around -1s.
    private fun shape(c: Float) = FloatArray(42) { c + (rnd.nextFloat() - 0.5f) * 0.2f }

    @Test fun startsAtAFullSecond() {
        assertEquals(1_000L, app.ultimate.air.ShapeLearner().holdMs("Open_Palm", shape(1f)))
    }

    @Test fun holdShrinksAsYouConfirmAShape() {
        val l = app.ultimate.air.ShapeLearner()
        repeat(20) { l.add("Open_Palm", shape(1f)); l.add("none", shape(-1f)) }
        l.train()
        val h = l.holdMs("Open_Palm", shape(1f))!!
        assertTrue("hold $h", h < 500)
    }

    @Test fun aShapeYouDontMeanStartsNoRing() {
        val l = app.ultimate.air.ShapeLearner()
        repeat(20) { l.add("Open_Palm", shape(1f)); l.add("none", shape(-1f)) }
        l.train()
        assertEquals(null, l.holdMs("Open_Palm", shape(-1f)))
    }

    @Test fun undoTakesTrustBack() {
        val l = app.ultimate.air.ShapeLearner()
        repeat(4) { l.add("Closed_Fist", shape(1f)) }
        l.undo("Closed_Fist", shape(1f))
        assertEquals(2, l.confirmed["Closed_Fist"])
    }

    @Test fun abandonedHoldsAreReported() {
        val h = app.ultimate.air.ShortcutHold()
        var t = 0L; while (t < 500) { h.feed("Closed_Fist", 500f, 500f, t); t += 33 }
        h.feed("Closed_Fist", 700f, 500f, t)
        assertEquals("Closed_Fist", h.abandoned)
    }
}

class IntentEngineTest {
    @Test fun twoHalfSureSensesAgreeingAreSure() {
        val e = app.ultimate.air.IntentEngine()
        e.offer("pinch", "camera", 0.5f, 0); e.offer("pinch", "watch", 0.65f, 0)
        assertTrue(e.belief("pinch", 0) > 0.8f)
        e.offer("pinch", "watch", 0f, 0)
        assertTrue(e.belief("pinch", 0) < 0.6f)
    }

    @Test fun evidenceFades() {
        val e = app.ultimate.air.IntentEngine(windowMs = 250)
        e.offer("pinch", "watch", 0.9f, 0)
        assertTrue(e.belief("pinch", 300) == 0f)
    }

    @Test fun aWatchTapClosesANearPinchTheCameraMissed() {
        val f = app.ultimate.air.PinchFusion()
        assertEquals(0.35f, f.feed(0.35f, 0), 1e-4f)
        f.watchTap(10)
        assertTrue(f.feed(0.35f, 20) < 0.25f)
        assertTrue(f.feed(0.4f, 60) < 0.25f)       // stays closed while the fingers are still close
        assertEquals(0.6f, f.feed(0.6f, 100), 1e-4f) // opens when the camera sees them apart
    }

    @Test fun aWatchTapWithOpenFingersDoesNothing() {
        val f = app.ultimate.air.PinchFusion()
        f.watchTap(0)
        assertEquals(0.8f, f.feed(0.8f, 10), 1e-4f)
    }

    @Test fun wristTapFeelsASharpJoltButNotSteadyMotion() {
        val w = app.ultimate.air.WristTap()
        val r = java.util.Random(1)
        var taps = 0
        for (i in 0 until 200) if (w.feed(i * 20L, 9.8f + (r.nextFloat() - 0.5f) * 0.1f, 0.2f)) taps++
        assertEquals(0, taps)
        assertTrue(w.feed(4_000, 11.5f, 0.3f))
        assertTrue(!w.feed(4_020, 11.5f, 0.3f)) // pause after a tap
        assertTrue(!w.feed(5_000, 12f, 3f))     // the wrist turning hard isn't a finger tap
    }
}

class AirCommandsTest {
    private val apps = listOf("Spotify", "YouTube", "YouTube Music", "Google Maps", "Messages", "Samsung Messages")
    private fun p(s: String) = app.ultimate.air.AirCommands.parse(s, apps)

    @Test fun opensAppsByName() {
        assertEquals(app.ultimate.air.AirCommands.Command.OpenApp("Spotify"), p("Open Spotify"))
        assertEquals(app.ultimate.air.AirCommands.Command.OpenApp("YouTube"), p("open youtube"))
        assertEquals(app.ultimate.air.AirCommands.Command.OpenApp("YouTube Music"), p("launch youtube music"))
        assertEquals(app.ultimate.air.AirCommands.Command.OpenApp("Google Maps"), p("open maps"))
        assertEquals(app.ultimate.air.AirCommands.Command.OpenApp("Messages"), p("open the messages app"))
        assertEquals(null, p("open banana"))
    }

    @Test fun navigation() {
        assertEquals(app.ultimate.air.AirCommands.Command.Back, p("go back"))
        assertEquals(app.ultimate.air.AirCommands.Command.Home, p("Home."))
        assertEquals(app.ultimate.air.AirCommands.Command.Recents, p("recent apps"))
        assertEquals(app.ultimate.air.AirCommands.Command.Scroll(true), p("scroll down"))
        assertEquals(null, p("turn on the flashlight"))
    }
}

class PhoneContextTest {
    @Test fun aWobblyPhoneIsHeldAndAStillOneSettles() {
        val c = app.ultimate.air.PhoneContext()
        val r = java.util.Random(2)
        var t = 0L
        repeat(50) { c.feed(t, 9.8f + (r.nextFloat() - 0.5f) * 0.05f); t += 20 }
        assertTrue(!c.held)
        repeat(50) { c.feed(t, 9.8f + (r.nextFloat() - 0.5f) * 2.5f); t += 20 }
        assertTrue(c.held)
        repeat(150) { c.feed(t, 9.8f); t += 20 }   // 3 s still on the table (1 s window + 1.5 s settle)
        assertTrue(!c.held)
    }
}

class PinchDriftTest {
    @Test fun aTapLandsWhereYouAimedNotWhereThePinchPulledTheFinger() {
        val p = app.ultimate.air.AirPointer(1000f, 1000f)
        var t = 0L
        repeat(60) { p.feed(0.5f, 0.5f, 0.8f, t); t += 33 }        // aiming still at the middle
        val aimX = p.x
        // Fingers close over ~200 ms; the tip drifts 4% of the picture (about 70 px on screen) toward the thumb.
        for (i in 1..6) { p.feed(0.5f - 0.007f * i, 0.5f + 0.007f * i, 0.8f - 0.1f * i, t); t += 33 }
        p.feed(0.458f, 0.542f, 0.1f, t); t += 33
        var tap: app.ultimate.air.AirPointer.Action? = null
        repeat(3) { p.feed(0.458f, 0.542f, 0.1f, t)?.let { tap = it }; t += 33 }
        p.feed(0.458f, 0.542f, 0.6f, t)?.let { tap = it }
        val a = tap as app.ultimate.air.AirPointer.Action.Tap
        assertTrue("tap ${a.x} vs aim $aimX", kotlin.math.abs(a.x - aimX) < 25f)
    }
}
