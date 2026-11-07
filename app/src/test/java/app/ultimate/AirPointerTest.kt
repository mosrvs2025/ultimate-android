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

class FingerShapesTest {
    /** A rough right hand, palm to the camera, wrist at the bottom; [out] says which fingers are straight. */
    private fun hand(out: List<Boolean>): List<Pair<Float, Float>> {
        val p = MutableList(21) { 0.5f to 0.8f }
        p[0] = 0.5f to 0.8f
        // Thumb: base, joints and tip going left; curled tips fold back across the palm.
        p[1] = 0.42f to 0.75f; p[2] = 0.37f to 0.7f; p[3] = 0.33f to 0.66f; p[4] = if (out[0]) 0.27f to 0.6f else 0.45f to 0.62f
        val xs = listOf(0.43f, 0.49f, 0.55f, 0.61f)
        for (f in 0 until 4) {
            val base = 5 + f * 4; val x = xs[f]
            p[base] = x to 0.6f; p[base + 1] = x to 0.5f; p[base + 2] = x to 0.43f
            p[base + 3] = if (out[f + 1]) x to 0.36f else x to 0.58f
        }
        return p
    }

    @Test fun readsTheMiddleFingerRockOnAndCallMe() {
        assertEquals(listOf(false, false, true, false, false), app.ultimate.air.FingerShapes.mask(hand(listOf(false, false, true, false, false))))
        val s = app.ultimate.air.FingerShapes()
        assertEquals(null, s.feed(hand(listOf(false, false, true, false, false)), 0))
        assertEquals("middle_finger", s.feed(hand(listOf(false, false, true, false, false)), 600))
        assertEquals(null, s.feed(hand(listOf(false, false, true, false, false)), 900)) // once per hold
        val r = app.ultimate.air.FingerShapes()
        r.feed(hand(listOf(true, true, false, false, true)), 0)
        assertEquals("rock_on", r.feed(hand(listOf(true, true, false, false, true)), 600))
        val c = app.ultimate.air.FingerShapes()
        c.feed(hand(listOf(true, false, false, false, true)), 0)
        assertEquals("call_me", c.feed(hand(listOf(true, false, false, false, true)), 600))
    }

    @Test fun anOpenHandOrFistIsNotACustomShape() {
        val s = app.ultimate.air.FingerShapes()
        s.feed(hand(listOf(true, true, true, true, true)), 0)
        assertEquals(null, s.feed(hand(listOf(true, true, true, true, true)), 900))
    }
}

class PlaygroundTest {
    @Test fun aFingerComingDownThroughTheDeskPlaysTheKeyUnderIt() {
        val p = app.ultimate.air.DeskPiano()
        p.setDesk(listOf(0.2f to 0.7f, 0.4f to 0.7f, 0.5f to 0.7f, 0.6f to 0.7f, 0.7f to 0.7f))
        fun tips(y: Float) = listOf<Pair<Float, Float>?>(null, 0.5f to y, null, null, null)
        assertTrue(p.feed(tips(0.70f)).isEmpty())         // resting: nothing
        assertTrue(p.feed(tips(0.62f)).isEmpty())         // lift
        assertEquals(listOf(p.keyAt(0.5f)), p.feed(tips(0.70f)))  // down, moving: plays
        assertTrue(p.feed(tips(0.71f)).isEmpty())         // staying down doesn't repeat
        assertTrue(p.feed(tips(0.675f)).isEmpty())        // a small wobble isn't a new press
        assertTrue(p.feed(tips(0.70f)).isEmpty())
    }

    @Test fun padModeGivesEachFingerItsOwnNoteWhereItRests() {
        val p = app.ultimate.air.DeskPiano()
        // Fingers of different lengths rest at different heights; each has its own pad.
        p.setDesk(listOf(0.2f to 0.8f, 0.35f to 0.6f, 0.5f to 0.55f, 0.65f to 0.6f, 0.8f to 0.7f), padMode = true)
        fun only(i: Int, y: Float) = List<Pair<Float, Float>?>(5) { j -> if (j == i) (0.2f + 0.15f * j) to y else null }
        assertTrue(p.feed(only(2, 0.55f)).isEmpty())       // the middle finger resting doesn't play
        p.feed(only(2, 0.48f))
        assertEquals(listOf(2), p.feed(only(2, 0.55f)))     // its tap plays its note (E)
        p.feed(only(0, 0.72f))
        assertEquals(listOf(0), p.feed(only(0, 0.8f)))      // the thumb plays C at its own lower rest
    }

    @Test fun aSlowDriftDownIsNotAStrike() {
        val p = app.ultimate.air.DeskPiano()
        p.setDesk(List(5) { 0.5f to 0.7f })
        fun one(y: Float) = listOf<Pair<Float, Float>?>(null, 0.5f to y, null, null, null)
        p.feed(one(0.6f))
        var y = 0.6f; var played = 0
        while (y < 0.72f) { y += 0.002f; played += p.feed(one(y)).size }
        assertEquals(0, played)
    }

    @Test fun fingersPlayIndependently() {
        val p = app.ultimate.air.DeskPiano()
        p.setDesk(List(5) { 0.5f to 0.7f })
        p.feed(listOf(0.1f to 0.6f, null, 0.5f to 0.6f, null, 0.9f to 0.6f))
        assertEquals(listOf(0, 4, 7), p.feed(listOf(0.1f to 0.7f, null, 0.5f to 0.7f, null, 0.9f to 0.7f)))
    }

    @Test fun pinchDrawsAndOpeningLifts() {
        val c = app.ultimate.air.AirCanvas()
        c.feed(0.1f, 0.1f, 0.8f, 1); assertEquals(0, c.strokes.size)
        c.feed(0.1f, 0.1f, 0.2f, 1); c.feed(0.2f, 0.2f, 0.2f, 1); c.feed(0.3f, 0.3f, 0.6f, 1)
        assertEquals(1, c.strokes.size); assertEquals(2, c.strokes[0].points.size)
    }

    @Test fun faceDrawingsFollowTheFace() {
        fun face(ox: Float, scale: Float): List<Pair<Float, Float>> = MutableList(468) { 0f to 0f }.also {
            it[1] = ox to 0.5f; it[234] = ox - scale / 2 to 0.5f; it[454] = ox + scale / 2 to 0.5f
        }
        val f1 = app.ultimate.air.FaceFrame.of(face(0.5f, 0.2f))!!
        val (u, v) = app.ultimate.air.FaceFrame.toFace(f1, 0.55f, 0.55f)   // on the right cheek
        val f2 = app.ultimate.air.FaceFrame.of(face(0.3f, 0.4f))!!          // moved left and came closer
        val (x, y) = app.ultimate.air.FaceFrame.toPicture(f2, u, v)
        assertEquals(0.4f, x, 1e-4f); assertEquals(0.6f, y, 1e-4f)
    }
}

class FaceNavigatorTest {
    private fun run(n: app.ultimate.air.FaceNavigator, s: Map<String, Float>, ms: Long, t0: Long): Pair<List<app.ultimate.air.FaceNavigator.Move>, Long> {
        val out = ArrayList<app.ultimate.air.FaceNavigator.Move>(); var t = t0
        while (t < t0 + ms) { n.feed(s, t)?.let(out::add); t += 33 }
        return out to t
    }
    private val rest = mapOf("browInnerUp" to 0.1f, "jawOpen" to 0.05f)
    private val M = app.ultimate.air.FaceNavigator.Move.entries

    @Test fun browsUpMovesUpOnceThenRepeatsSlowly() {
        val n = app.ultimate.air.FaceNavigator()
        var (_, t) = run(n, rest, 1_000, 0)
        val (moves, _) = run(n, mapOf("browInnerUp" to 0.8f, "browOuterUpLeft" to 0.7f, "browOuterUpRight" to 0.7f, "jawOpen" to 0.05f), 1_600, t)
        assertEquals(app.ultimate.air.FaceNavigator.Move.Up, moves.first())
        assertTrue(moves.size in 2..3)
        assertTrue(moves.all { it == M[0] })
    }

    @Test fun mouthOpenHeldChoosesButABriefOneDoesNot() {
        val n = app.ultimate.air.FaceNavigator()
        var (_, t) = run(n, rest, 1_000, 0)
        val (brief, t2) = run(n, rest + ("jawOpen" to 0.7f), 400, t)
        assertTrue(brief.isEmpty())
        val (_, t3) = run(n, rest, 500, t2)
        val (held, _) = run(n, rest + ("jawOpen" to 0.7f), 1_000, t3)
        assertEquals(listOf(app.ultimate.air.FaceNavigator.Move.Select), held)
    }

    @Test fun aRestingFaceDoesNothing() {
        assertTrue(run(app.ultimate.air.FaceNavigator(), rest + ("browDownLeft" to 0.2f) + ("browDownRight" to 0.2f), 5_000, 0).first.isEmpty())
    }
}

class SenseRecorderTest {
    @Test fun linesAreCompactAndParseable() {
        val l = app.ultimate.air.SenseRecorder.Lines.hand(5, "Open_Palm", 0.9f, 0.31f, List(21) { 0.5f to 0.25f })
        val parts = l.split(',')
        assertEquals("h", parts[0]); assertEquals(5 + 42, parts.size)
        assertEquals("0.2500", parts.last())
        assertEquals("e,7,tap,a;b", app.ultimate.air.SenseRecorder.Lines.event(7, "tap", "a,b"))
    }

    @Test fun recordsExportsAndTrims() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val r = app.ultimate.air.SenseRecorder(dir, capBytes = 1)
        r.start(1); r.write("e,1,tap,"); r.stop()
        r.start(2); r.write("e,2,tap,"); r.stop()
        assertEquals(2, r.sessions().size)
        val zip = r.export(java.io.File(dir.parentFile, "x-${System.nanoTime()}.zip"))!!
        assertTrue(zip.length() > 0)
        r.trim(); assertEquals(1, r.sessions().size)   // over the cap: the oldest goes, the newest stays
        zip.delete(); dir.deleteRecursively()
    }
}

class TongueDetectorTest {
    private fun face(): List<Pair<Float, Float>> = MutableList(468) { 0.5f to 0.5f }.also {
        it[17] = 0.5f to 0.6f; it[152] = 0.5f to 0.8f; it[61] = 0.42f to 0.55f; it[291] = 0.58f to 0.55f
        it[50] = 0.35f to 0.45f; it[280] = 0.65f to 0.45f
    }
    private val skin = (220 shl 16) or (170 shl 8) or 140
    private val tongue = (225 shl 16) or (90 shl 8) or 110

    @Test fun aPinkStripBelowTheLipIsATongue() {
        val d = app.ultimate.air.TongueDetector()
        repeat(20) { assertTrue(d.feed(face()) { _, _ -> skin } < 0.1f) }
        val out = d.feed(face()) { _, y -> if (y in 0.62f..0.7f) tongue else skin }
        assertTrue("level $out", out > 0.8f)
    }

    @Test fun evenLightingChangesDontLookLikeATongue() {
        val d = app.ultimate.air.TongueDetector()
        repeat(20) { d.feed(face()) { _, _ -> skin } }
        val warm = (240 shl 16) or (160 shl 8) or 120
        assertTrue(d.feed(face()) { _, _ -> warm } < 0.2f)
    }
}

class SearchVerbTest {
    @Test fun dropsTheLeadingVerb() {
        val w = app.ultimate.search.PaletteMath::withoutVerb
        assertEquals("YouTube", w("Play YouTube"))
        assertEquals("YouTube", w("open the YouTube app"))
        assertEquals("lofi beats", w("play lofi beats"))
        assertEquals("Mom", w("call Mom"))
        assertEquals("YouTube", w("YouTube"))
        assertEquals("play", w("play"))
    }
}

class FaceNavigatorNodTest {
    @Test fun noddingScrollsAndMetersShowHowClose() {
        val n = app.ultimate.air.FaceNavigator()
        var t = 0L
        repeat(30) { n.feed(emptyMap(), t, 0.45f); t += 33 }
        assertEquals(0f, n.levels["down"]!!, 0.01f)
        var moves = 0
        repeat(10) { if (n.feed(emptyMap(), t, 0.53f) == app.ultimate.air.FaceNavigator.Move.Down) moves++; t += 33 }
        assertTrue(moves >= 1)
        // A small nod shows on the meter without moving.
        val m = app.ultimate.air.FaceNavigator(); t = 0
        repeat(30) { m.feed(emptyMap(), t, 0.45f); t += 33 }
        assertTrue(m.feed(emptyMap(), t, 0.48f) == null); assertTrue(m.levels["down"]!! in 0.3f..0.7f)
    }
}

class VoiceGateTest {
    private fun tone(n: Int, f: Double, amp: Float, rate: Int = 48_000) = FloatArray(n) { (amp * kotlin.math.sin(2 * Math.PI * f * it / rate)).toFloat() }

    @Test fun echosToneAloneIsNotSpeechButAVoiceIs() {
        val g = app.ultimate.echo.VoiceGate()
        var t = 0L
        repeat(30) { assertTrue(!g.feed(tone(2048, 19_000.0, 0.3f), t)); t += 43 }
        val speech = tone(2048, 19_000.0, 0.3f).also { v -> val s = tone(2048, 220.0, 0.1f); for (i in v.indices) v[i] += s[i] }
        assertTrue(g.feed(speech, t)); t += 43
        assertTrue(g.feed(tone(2048, 19_000.0, 0.3f), t))   // still paused right after
        t += 400
        assertTrue(!g.feed(tone(2048, 19_000.0, 0.3f), t))
    }
}

class SearchLeftoverTest {
    @Test fun keepsWhatTheRequestIsAbout() {
        assertEquals("YouTube", app.ultimate.search.PaletteMath.leftover("YouTube notifications"))
        assertEquals("Sam", app.ultimate.search.PaletteMath.leftover("old messages from Sam"))
        assertEquals(null, app.ultimate.search.PaletteMath.leftover("notification history"))
    }
}

class MindCheckTest {
    @Test fun aRoughNightReadsHeavierThanAGoodOne() {
        val rough = app.ultimate.health.MindCheck.read(300, 30.0, 50.0, 66, 58.0, 90, 120, 60.0, 0)!!
        val good = app.ultimate.health.MindCheck.read(480, 60.0, 50.0, 56, 58.0, 0, 40, 60.0, 3)!!
        assertTrue(rough.load > 75); assertTrue(good.load < 30)
        assertTrue(rough.factors.none { it.good }); assertTrue(good.factors.all { it.good })
    }
    @Test fun nothingKnownSaysNothing() {
        assertEquals(null, app.ultimate.health.MindCheck.read(null, null, null, null, null, null, null, null, null))
    }
}

class HeartWatchTest {
    @Test fun aSustainedHighHeartRateCountsOnceAndCalmingDownToo() {
        val w = app.ultimate.health.HeartWatch()
        val out = ArrayList<String>(); var t = 0L
        fun run(bpm: Int, secs: Int) { repeat(secs / 5) { w.feed(bpm, t)?.let(out::add); t += 5_000 } }
        run(75, 120); run(125, 20); run(75, 60)        // a brief spike: nothing
        assertTrue(out.isEmpty())
        run(120, 120)                                  // two minutes high
        assertEquals(listOf("heart_high"), out)
        run(105, 120)                                  // still near the line: not calm yet
        assertEquals(1, out.size)
        run(80, 90)
        assertEquals(listOf("heart_high", "heart_calm"), out)
    }
}

class PhoneCommandsTest {
    private fun p(s: String) = app.ultimate.ai.PhoneCommands.parse(s)
    @Test fun brightnessIsNeverVolume() {
        assertEquals("set_brightness", p("Set the brightness to 50")!!.name)
        assertEquals(50, p("Set the brightness to 50")!!.args["percent"])
        assertEquals(100, p("max brightness")!!.args["percent"])
        assertEquals(-2, p("make the screen dimmer")!!.args["percent"])
        assertEquals("set_volume", p("volume to 30%")!!.name)
        assertEquals(0, p("mute")!!.args["percent"])
        assertEquals(false, p("turn the flashlight off")!!.args["on"])
        assertEquals(null, p("open spotify"))
        assertTrue(!app.ultimate.ai.PhoneCommands.plausible(app.ultimate.ai.AiCall("set_volume", emptyMap(), 0.8), "Set the brightness to 50"))
    }
}
