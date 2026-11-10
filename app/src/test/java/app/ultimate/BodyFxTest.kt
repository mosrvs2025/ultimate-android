package app.ultimate

import app.ultimate.air.BodyEffect
import app.ultimate.air.BodyPose
import app.ultimate.air.CameraFilters
import app.ultimate.air.Joints
import app.ultimate.air.RepCounter
import app.ultimate.air.Sparks
import app.ultimate.air.Trails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyFxTest {
    private fun pose(vararg set: Pair<Int, Pair<Float, Float>>, sure: Float = 0.9f): BodyPose {
        val pts = MutableList(33) { Triple(0.5f, 0.5f, 0f) }
        set.forEach { (i, p) -> pts[i] = Triple(p.first, p.second, sure) }
        return BodyPose(pts, 0)
    }

    @Test fun anglesAreMeasuredAtTheMiddleJoint() {
        assertEquals(180f, Joints.angle(0f to 0f, 0.5f to 0f, 1f to 0f), 0.5f)
        assertEquals(90f, Joints.angle(0f to 0f, 0f to 1f, 1f to 1f), 0.5f)
        assertEquals(45f, Joints.angle(1f to 0f, 0f to 0f, 1f to 1f), 0.5f)
        assertEquals("a point on top of the joint can't be measured, so it reads as straight", 180f, Joints.angle(0f to 0f, 0f to 0f, 1f to 1f), 0.1f)
    }

    @Test fun unseenPointsAreNotUsedAndTheFrontCameraMirrors() {
        val p = pose(BodyPose.L_WRIST to (0.2f to 0.4f))
        assertEquals(0.2f to 0.4f, p.at(BodyPose.L_WRIST, false)); assertEquals(0.8f, p.at(BodyPose.L_WRIST, true)!!.first, 1e-4f)
        assertNull(p.at(BodyPose.R_WRIST, false)); assertNull(Joints.knee(p)); assertNull(Joints.shoulderTilt(p)); assertNull(Joints.scale(p))
        assertNull(pose(BodyPose.L_WRIST to (0.2f to 0.4f), sure = 0.2f).at(BodyPose.L_WRIST, false))
    }

    @Test fun kneeUsesTheMoreBentVisibleLegAndShouldersReportTilt() {
        val straight = pose(BodyPose.L_HIP to (0.4f to 0.4f), BodyPose.L_KNEE to (0.4f to 0.6f), BodyPose.L_ANKLE to (0.4f to 0.8f))
        assertEquals(180f, Joints.knee(straight)!!, 1f)
        val bent = pose(BodyPose.L_HIP to (0.4f to 0.4f), BodyPose.L_KNEE to (0.4f to 0.6f), BodyPose.L_ANKLE to (0.4f to 0.8f),
            BodyPose.R_HIP to (0.6f to 0.5f), BodyPose.R_KNEE to (0.75f to 0.6f), BodyPose.R_ANKLE to (0.6f to 0.7f))
        assertTrue(Joints.knee(bent)!! < 120f)
        assertEquals(0f, Joints.shoulderTilt(pose(BodyPose.L_SHOULDER to (0.6f to 0.3f), BodyPose.R_SHOULDER to (0.4f to 0.3f)))!!, 0.5f)
        assertEquals(45f, Joints.shoulderTilt(pose(BodyPose.L_SHOULDER to (0.6f to 0.5f), BodyPose.R_SHOULDER to (0.4f to 0.3f)))!!, 0.5f)
        assertEquals(0.2f, Joints.scale(pose(BodyPose.L_SHOULDER to (0.6f to 0.3f), BodyPose.R_SHOULDER to (0.4f to 0.3f)))!!, 1e-4f)
    }

    @Test fun squatsAreCountedOncePerFullRepAndWobbleIsNot() {
        val r = RepCounter(); var t = 0L
        fun go(vararg angles: Float) = angles.forEach { r.feed(it, t); t += 300 }
        go(170f, 150f, 120f, 95f, 80f, 95f, 130f, 160f); assertEquals(1, r.count)
        go(170f, 110f, 105f, 110f, 150f, 170f); assertEquals("never got low enough", 1, r.count)
        go(90f, 120f, 140f, 120f, 90f, 150f, 152f, 160f); assertEquals("half way up and back down is still one rep", 2, r.count)
        r.feed(90f, t); r.feed(170f, t + 50); assertEquals("too fast to be a rep", 2, r.count)
        assertFalse(r.feed(null, t)); r.reset(); assertEquals(0, r.count)
    }

    @Test fun trailsFadeAndForget() {
        val tr = Trails(maxAgeMs = 1000)
        tr.add(1, 0.1f, 0.1f, 0); tr.add(1, 0.2f, 0.2f, 500); tr.add(2, 0.9f, 0.9f, 500)
        val at600 = tr.lines(600)
        assertEquals(setOf(1), at600.keys); assertEquals(2, at600.getValue(1).size)
        assertTrue(at600.getValue(1)[0].third < at600.getValue(1)[1].third)
        assertTrue("old points drop out", tr.lines(1200).isEmpty())
        repeat(200) { tr.add(3, it / 200f, 0.5f, 2000L + it) }; assertEquals(60, tr.lines(2200).getValue(3).size)
    }

    @Test fun sparksFallFadeAndStayBounded() {
        val s = Sparks(seed = 1)
        s.emit(0.5f, 0.5f, 50); assertEquals(50, s.all.size)
        val y0 = s.all.map { it.y }.average()
        repeat(10) { s.step(0.05f) }
        assertTrue("gravity wins", s.all.map { it.y }.average() > y0 - 0.1)
        repeat(40) { s.step(0.05f) }; assertTrue("all burnt out", s.all.isEmpty())
        repeat(100) { s.emit(0.5f, 0.5f, 10) }; assertTrue(s.all.size <= 400)
        val a = Sparks(seed = 3).apply { emit(0.1f, 0.1f, 5) }.all.map { it.vx }; val b = Sparks(seed = 3).apply { emit(0.1f, 0.1f, 5) }.all.map { it.vx }
        assertEquals(a, b)
    }

    @Test fun looksDoWhatTheirNamesSay() {
        val m = CameraFilters.all.toMap()
        assertEquals(10, CameraFilters.all.size); assertTrue(CameraFilters.all.all { it.second.size == 20 })
        fun near(e: Triple<Float, Float, Float>, a: Triple<Float, Float, Float>) = assertTrue("$e vs $a", kotlin.math.abs(e.first - a.first) < 1.5f && kotlin.math.abs(e.second - a.second) < 1.5f && kotlin.math.abs(e.third - a.third) < 1.5f)
        near(Triple(200f, 100f, 50f), CameraFilters.apply(m.getValue("None"), 200f, 100f, 50f))
        val mono = CameraFilters.apply(m.getValue("Mono"), 200f, 100f, 50f); assertEquals(mono.first, mono.second, 0.5f); assertEquals(mono.second, mono.third, 0.5f)
        near(Triple(55f, 155f, 205f), CameraFilters.apply(m.getValue("Negative"), 200f, 100f, 50f))
        val grey = 120f
        assertTrue(CameraFilters.apply(m.getValue("Warm"), grey, grey, grey).let { it.first > it.third })
        assertTrue(CameraFilters.apply(m.getValue("Cool"), grey, grey, grey).let { it.third > it.first })
        assertTrue(CameraFilters.apply(m.getValue("Night"), grey, grey, grey).let { it.second > it.first && it.second > it.third })
        assertTrue(CameraFilters.apply(m.getValue("Sepia"), grey, grey, grey).let { it.first > it.second && it.second > it.third })
        val vivid = CameraFilters.apply(m.getValue("Vivid"), 200f, 100f, 50f); assertTrue("more colourful", vivid.first - vivid.third > 150f)
        assertEquals("white stays white in Mono", 255f, CameraFilters.apply(m.getValue("Mono"), 255f, 255f, 255f).first, 1f)
    }

    @Test fun everyEffectSaysWhatItDecorates() {
        assertEquals(8, BodyEffect.entries.size)
        assertTrue(BodyEffect.entries.any { it.parts.contains("hands") } && BodyEffect.entries.any { it.parts.contains("feet") } && BodyEffect.entries.any { it.parts.contains("whole body") })
        assertEquals(33, 1 + BodyPose.BONES.flatMap { listOf(it.first, it.second) }.max())
    }
}

class BodyGesturesTest {
    private val B = app.ultimate.air.BodyPose
    /** A person facing the camera: shoulders 0.2 apart at y 0.4, nose at 0.3; wrists where asked. */
    private fun pose(lw: Pair<Float, Float>?, rw: Pair<Float, Float>?): app.ultimate.air.BodyPose {
        val pts = MutableList(33) { Triple(0.5f, 0.5f, 0f) }
        fun set(i: Int, p: Pair<Float, Float>) { pts[i] = Triple(p.first, p.second, 0.9f) }
        set(B.NOSE, 0.5f to 0.3f); set(B.L_SHOULDER, 0.6f to 0.4f); set(B.R_SHOULDER, 0.4f to 0.4f)
        lw?.let { set(B.L_WRIST, it) }; rw?.let { set(B.R_WRIST, it) }
        return app.ultimate.air.BodyPose(pts, 0)
    }
    private val rest = 0.65f to 0.7f to (0.35f to 0.7f)
    private fun kinds(e: List<app.ultimate.air.BodyGestures.Event>) = e.filterIsInstance<app.ultimate.air.BodyGestures.Event.Did>().map { it.kind }

    @Test fun aRaisedHandSweptSidewaysIsOneSwipeInThatDirection() {
        val g = app.ultimate.air.BodyGestures(); val got = ArrayList<app.ultimate.air.BodyGestures.Kind>(); var t = 0L
        for (i in 0..8) { got += kinds(g.feed(pose(0.45f + i * 0.04f to 0.35f, rest.second), false, t)); t += 40 }
        assertEquals(listOf(app.ultimate.air.BodyGestures.Kind.SwipeRight), got)
        for (i in 0..8) { got += kinds(g.feed(pose(0.77f + i * 0.01f to 0.35f, rest.second), false, t)); t += 40 }
        assertEquals("the follow-through right after doesn't count again", 1, got.size)
        t += 1000; got.clear()
        for (i in 0..8) { got += kinds(g.feed(pose(0.8f - i * 0.04f to 0.35f, rest.second), false, t)); t += 40 }
        assertEquals(listOf(app.ultimate.air.BodyGestures.Kind.SwipeLeft), got)
    }

    @Test fun handsBelowTheShouldersAndSlowOrVerticalMovesAreNotSwipes() {
        val g = app.ultimate.air.BodyGestures(); val got = ArrayList<app.ultimate.air.BodyGestures.Kind>(); var t = 0L
        for (i in 0..8) { got += kinds(g.feed(pose(0.45f + i * 0.04f to 0.6f, rest.second), false, t)); t += 40 }   // low: walking arm swing
        for (i in 0..40) { got += kinds(g.feed(pose(0.45f + i * 0.008f to 0.35f, rest.second), false, t)); t += 40 } // raised but slow
        t += 1000
        for (i in 0..8) { got += kinds(g.feed(pose(0.7f to 0.38f - i * 0.03f, rest.second), false, t)); t += 40 }   // straight up
        assertTrue(got.toString(), got.isEmpty())
    }

    @Test fun bothHandsHeldAboveTheHeadOpenTheMenuOnceUntilLowered() {
        val g = app.ultimate.air.BodyGestures(); val got = ArrayList<app.ultimate.air.BodyGestures.Kind>(); var t = 0L
        repeat(10) { got += kinds(g.feed(pose(0.62f to 0.15f, 0.38f to 0.15f), false, t)); t += 50 }
        assertTrue("not before it has been held", got.isEmpty())
        repeat(30) { got += kinds(g.feed(pose(0.62f to 0.15f, 0.38f to 0.15f), false, t)); t += 50 }
        assertEquals(listOf(app.ultimate.air.BodyGestures.Kind.Menu), got)
        repeat(5) { g.feed(pose(rest.first, rest.second), false, t); t += 50 }; t += 800
        repeat(20) { got += kinds(g.feed(pose(0.62f to 0.15f, 0.38f to 0.15f), false, t)); t += 50 }
        assertEquals(2, got.size)
    }

    @Test fun handsTogetherChargeAndThrowingThemApartFiresAwayFromTheBody() {
        val g = app.ultimate.air.BodyGestures(); var t = 0L
        repeat(13) { g.feed(pose(0.52f to 0.6f, 0.48f to 0.6f), false, t); t += 50 }
        assertEquals(0.5f, g.charge, 0.06f); assertEquals(0.5f, g.chargeAt!!.first, 0.01f)
        val fired = g.feed(pose(0.75f to 0.6f, 0.25f to 0.6f), false, t).filterIsInstance<app.ultimate.air.BodyGestures.Event.Fired>()
        assertEquals(1, fired.size)
        val s = fired[0].shot; assertTrue("downwards, away from the shoulders", s.dy > 0.9f); assertEquals(0.5f, s.power, 0.1f)
        assertEquals(0f, g.charge, 0f); assertNull(g.chargeAt)
        // A touch that is too short is not a blast.
        g.feed(pose(0.52f to 0.6f, 0.48f to 0.6f), false, t + 1000); val none = g.feed(pose(0.75f to 0.6f, 0.25f to 0.6f), false, t + 1050)
        assertTrue(none.isEmpty())
    }

    @Test fun blastsFlyAndFadeAndFeetAreTrustedFromFurtherBack() {
        val b = app.ultimate.air.Blasts(); b.fire(app.ultimate.air.BlastShot(0.5f, 0.5f, 1f, 0f, 1f))
        b.step(0.1f); assertTrue(b.all[0].x > 0.6f); repeat(30) { b.step(0.1f) }; assertTrue(b.all.isEmpty())
        val pts = MutableList(33) { Triple(0.5f, 0.5f, 0.3f) }
        val p = app.ultimate.air.BodyPose(pts, 0)
        assertTrue("an ankle at 0.3 counts", p.ok(B.L_ANKLE)); assertFalse("a wrist at 0.3 does not", p.ok(B.L_WRIST))
    }

    @Test fun outfitsCoverTheBodyAndTheTorsoIsWiderThanTheJoints() {
        assertEquals(8, app.ultimate.air.Outfits.all.size); assertTrue(app.ultimate.air.Outfits.all.any { it.cape != null } && app.ultimate.air.Outfits.all.any { it.legs == 1 })
        val pts = MutableList(33) { Triple(0.5f, 0.5f, 0f) }
        pts[B.L_SHOULDER] = Triple(0.6f, 0.4f, 0.9f); pts[B.R_SHOULDER] = Triple(0.4f, 0.4f, 0.9f); pts[B.L_HIP] = Triple(0.58f, 0.7f, 0.9f); pts[B.R_HIP] = Triple(0.42f, 0.7f, 0.9f)
        val q = app.ultimate.air.Outfits.torso(app.ultimate.air.BodyPose(pts, 0), false)!!
        assertEquals(4, q.size); assertTrue(q[0].first > 0.6f && q[1].first < 0.4f)
        pts[B.L_HIP] = Triple(0.58f, 0.7f, 0.1f); assertNull(app.ultimate.air.Outfits.torso(app.ultimate.air.BodyPose(pts, 0), false))
        assertEquals(21, 1 + app.ultimate.air.HAND_BONES.flatMap { listOf(it.first, it.second) }.max())
    }
}
