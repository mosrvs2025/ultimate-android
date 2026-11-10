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
        assertEquals(6, BodyEffect.entries.size)
        assertTrue(BodyEffect.entries.any { it.parts.contains("hands") } && BodyEffect.entries.any { it.parts.contains("feet") } && BodyEffect.entries.any { it.parts.contains("whole body") })
        assertEquals(33, 1 + BodyPose.BONES.flatMap { listOf(it.first, it.second) }.max())
    }
}
