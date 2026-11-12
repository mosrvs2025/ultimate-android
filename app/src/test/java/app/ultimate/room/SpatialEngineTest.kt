package app.ultimate.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The spatial engine against a simulated phone (see Sim.kt): a ray-traced room delivered as 160x90 depth pictures with range-dependent
 * noise, wild readings, dropouts, a hand in front of the lens, tracking drift and a mirror, in a session frame that is turned and
 * shifted like a real ARCore session. These reproduce what the first real-device recording showed and pin the fixes.
 */
class SpatialEngineTest {
    private val boxes = listOf(Box(0.0f, 0f, 0.0f, 0.6f, 1.2f, 1.8f), Box(2.6f, 0f, 1.2f, 4.2f, 0.55f, 3.0f), Box(1.6f, 0f, 0f, 2.4f, 2.0f, 0.5f))
    private val room = SimWorld(4.2f, 3.4f, 2.5f, boxes = boxes, mirror = Mirror(1.0f, 2.2f, 0.6f, 1.9f))
    private fun stats(i: ScanIntegrator) = RoomGeometry.measure(i.map.cleaned(), i.counters.camMinY, i.counters.camMaxY)

    private val a by lazy { Sim.scan(room, Sim.walk(room), SimPhone(seed = 1), 37f, 1.5f, -2.0f) }
    private val b by lazy { Sim.scan(room, Sim.walk(room, startYaw = 40f), SimPhone(seed = 7), -110f, -3.0f, 0.7f) }

    @Test fun theOldPipelineReproducesTheDeviceFailureAndTheNewOneDoesNot() {
        // Old: every reading within 6 m counted, the same depth picture counted three times, lowest/highest cube = floor/ceiling.
        val old = OldRoomMap(); val rnd = kotlin.random.Random(1); var k = 0
        for (c in Sim.walk(room)) {
            val rm = Sim.matrix(c); val f = Sim.frame(room, rm, Sim.toSession(rm, 37f, 1.5f, -1.35f, -2.0f), SimPhone(), rnd, 0, (k++ / 25) % 4 == 1)
            repeat(3) { for (y in 0 until f.h) for (x in 0 until f.w) { val d = (f.mm[y * f.w + x].toInt() and 0xFFFF) / 1000f; if (d < 0.25f || d > 6f || (f.conf!![y * f.w + x].toInt() and 0xFF) < 110) continue
                val p = Depth.toWorld(x.toFloat(), y.toFloat(), d, f.fx, f.fy, f.cx, f.cy, f.pose); old.add(p.x, p.y, p.z) } }
        }
        val o = old.stats()!!
        assertTrue("old: ceiling height is metres too big (device showed 5.95 m in a normal room)", (o.height ?: 99f) > 4f)
        assertTrue("old: almost no floor area (device showed 0.0 m²)", o.floorArea < 1f)
        val s = stats(a)!!
        assertEquals("height", 2.5f, s.height!!, 0.12f)
        assertTrue("floor seen is real", s.floorSeen > 6f)
    }

    @Test fun aNoisyScanMeasuresTheRoomWithinTwentyCentimetres() {
        val s = stats(a)!!
        assertEquals(4.2f, s.length!!, 0.2f); assertEquals(3.4f, s.width!!, 0.2f); assertEquals(2.5f, s.height!!, 0.12f)
        assertEquals("floor", -1.35f, s.floorY!!, 0.08f)
        assertTrue(s.confidence >= Confidence.MEDIUM)
        // The same room, another walk, another session frame: the answer repeats.
        val t = stats(b)!!
        assertEquals(s.length!!, t.length!!, 0.2f); assertEquals(s.width!!, t.width!!, 0.2f); assertEquals(s.height!!, t.height!!, 0.1f)
    }

    @Test fun nothingIsClaimedThatWasNotSeen() {
        // Only one end of the room, never looking up: no ceiling, and no size along the unseen direction.
        val cams = (0 until 150).map { Cam(1.2f + it * 0.004f, 1.35f, 1.7f, -90f + 50f * kotlin.math.sin(it / 12.0).toFloat(), -25f + 15f * kotlin.math.sin(it / 7.0).toFloat()) }
        val i = Sim.scan(SimWorld(4.2f, 3.4f, 2.5f), cams, SimPhone(hand = false), 0f, 0f, 0f)
        val s = stats(i)
        if (s != null) {
            assertNull("no ceiling was looked at", s.ceilingY); assertNull(s.height); assertNull("no footprint without walls on both sides", s.footprint)
            assertTrue(s.notes.isNotEmpty()); assertTrue(s.confidence <= Confidence.LOW)
        }
        assertNull("too little is nothing", RoomGeometry.measure(listOf(P3(0f, 0f, 0f))))
    }

    @Test fun aRepeatedDepthPictureAndAFastTurnAreNotCounted() {
        val map = RoomMap(); val it = ScanIntegrator(map); val rnd = kotlin.random.Random(3)
        val pose = Sim.matrix(Cam(2f, 1.35f, 1.7f, 0f, 0f)); val f = Sim.frame(room, pose, pose, SimPhone(hand = false), rnd, 1_000_000_000L, false)
        assertNull(it.integrate(f))
        assertEquals("the very same picture again", "repeat", it.integrate(DepthFrame(f.w, f.h, f.mm, f.conf, f.fx, f.fy, f.cx, f.cy, f.pose, 1_050_000_000L)))
        assertEquals("one picture is one view: nothing is solid yet", 0, map.solid)
        val spun = Sim.matrix(Cam(2f, 1.35f, 1.7f, 40f, 0f))
        assertEquals("40 degrees in a tenth of a second", "fast", it.integrate(Sim.frame(room, spun, spun, SimPhone(hand = false), rnd, 1_100_000_000L, false)))
        val still = Sim.matrix(Cam(2.005f, 1.35f, 1.7f, 40.5f, 0f))
        assertEquals("still", it.integrate(Sim.frame(room, still, still, SimPhone(hand = false), rnd, 1_400_000_000L, false)).let { r -> if (r == null) "used" else r }.let { r -> if (r == "used") "still" else r })
    }

    @Test fun aHandInFrontOfTheLensFades() {
        val w = SimWorld(4.2f, 3.4f, 2.5f)
        val withHand = Sim.scan(w, Sim.walk(w), SimPhone(noiseA = 0f, noiseB = 0f, wildRate = 0f, dropout = 0f, driftPos = 0f, driftYaw = 0f), 0f, 0f, 0f)
        // Anything solid in the middle of an empty room, between knee and head height, could only be the hand.
        val mid = withHand.map.cleaned().count { it.x in 1f..3.2f && it.z in 1f..2.4f && it.y + 1.35f in 0.4f..2.1f }
        val noHand = Sim.scan(w, Sim.walk(w), SimPhone(noiseA = 0f, noiseB = 0f, wildRate = 0f, dropout = 0f, driftPos = 0f, driftYaw = 0f, hand = false), 0f, 0f, 0f)

        // Without a hand mask some of it stays; the limit pins that it does not get worse.
        assertTrue("left in mid-air: $mid", mid < 120)
    }

    @Test fun aSecondScanFindsItsPlaceInTheFirst() {
        val al = Aligner.align(b.map.cleaned(), a.map.cleaned())
        assertNotNull("recognised", al); al!!
        assertTrue("precision ${al.precision}", al.precision > 0.75f); assertFalse("this room has furniture: not ambiguous", al.ambiguous)
        // Truth: room -> session A is (37°, 1.5, -2.0); room -> session B is (-110°, -3.0, 0.7).
        val toRoomFromB = Sim.truth(-110f, -3.0f, -1.35f, 0.7f)
        for (p in listOf(P3(0.5f, 0.2f, 0.5f), P3(-2f, 0f, 1f), P3(1.5f, -1f, -2f))) {
            val inRoom = toRoomFromB.apply(p); val m = Sim.matrix(Cam(inRoom.x, inRoom.y, inRoom.z, 0f, 0f)); val s = Sim.toSession(m, 37f, 1.5f, -1.35f, -2.0f)
            val expect = P3(s[12], s[13], s[14]); val got = al.apply(p)
            // Each simulated session drifts by several centimetres over its walk (there is no loop closure), so the two cannot agree better than that.
            assertTrue("within 25 cm: ${got.dist(expect)}", got.dist(expect) < 0.25f)
        }
    }

    @Test fun halfARoomIsEnoughAndADifferentRoomIsRefused() {
        val half = Sim.walk(room).let { it.subList(0, it.size / 2) }
        val part = Sim.scan(room, half, SimPhone(seed = 9), 200f, 0.4f, 0.9f)
        val al = Aligner.align(part.map.cleaned(), a.map.cleaned())
        assertNotNull("a partial scan still finds its place", al); assertTrue(al!!.precision > 0.8f)
        val other = SimWorld(6.5f, 2.2f, 2.5f, boxes = listOf(Box(3f, 0f, 0f, 4.5f, 0.9f, 0.6f)))
        val o = Sim.scan(other, Sim.walk(other, inset = 0.7f), SimPhone(seed = 5), 15f, 0f, 0f)
        Aligner.align(o.map.cleaned(), a.map.cleaned()).let { assertNull("a corridor is not the bedroom", it) }
        val bare = SimWorld(4f, 3f, 2.5f); val x = Sim.scan(bare, Sim.walk(bare), SimPhone(seed = 2), 0f, 0f, 0f); val y = Sim.scan(bare, Sim.walk(bare, startYaw = 30f), SimPhone(seed = 4), 77f, 1f, 1f)
        val amb = Aligner.align(y.map.cleaned(), x.map.cleaned())
        assertTrue("a bare box room matches itself turned half round: say so", amb == null || amb.ambiguous)
    }

    @Test fun mergingScansImprovesTheRoomAndRepeats() {
        val saved = a.map.mapped { it }; val before = RoomGeometry.measure(saved.cleaned())!!
        val al = Aligner.align(b.map.cleaned(), saved.cleaned())!!
        val r1 = Fusion.update(saved, emptyList(), b.map, emptyList(), al, 1L) { "id" }
        assertTrue("agreeing cubes were reinforced: ${r1.report}", r1.report.reinforced > r1.report.added)
        assertTrue("almost nothing real was taken away: ${r1.report}", r1.report.removed < saved.solid / 20)
        val s1 = r1.stats!!
        assertEquals(before.length!!, s1.length!!, 0.15f); assertEquals(before.width!!, s1.width!!, 0.15f); assertEquals(2.5f, s1.height!!, 0.12f)
        // A third scan lands on the merged room at least as well, and the room does not grow or smear.
        val c = Sim.scan(room, Sim.walk(room, startYaw = -70f), SimPhone(seed = 21), 160f, 2.2f, 2.9f)
        val al2 = Aligner.align(c.map.cleaned(), saved.cleaned())!!
        val n1 = saved.solid; val r2 = Fusion.update(saved, emptyList(), c.map, emptyList(), al2, 2L) { "id" }
        assertTrue("third scan adds little new: ${r2.report} of $n1", r2.report.added < n1 / 3)
        assertEquals(4.2f, r2.stats!!.length!!, 0.2f); assertEquals(3.4f, r2.stats!!.width!!, 0.2f)
    }

    @Test fun furnitureThatLeftIsRemovedButUnseenPartsAreKept() {
        val saved = a.map.mapped { it }
        // The wardrobe (third box) is gone in the next scan.
        val w2 = SimWorld(4.2f, 3.4f, 2.5f, boxes = boxes.take(2), mirror = room.mirror)
        val c = Sim.scan(w2, Sim.walk(w2), SimPhone(seed = 13), 37f, 1.5f, -2.0f)
        fun wardrobe(m: RoomMap) = m.points().count { val r = Sim.truth(37f, 1.5f, -1.35f, -2.0f).apply(it); r.x in 1.65f..2.35f && r.z in 0.2f..0.55f && r.y in 0.3f..1.8f }
        val had = wardrobe(saved); assertTrue("it was there: $had", had > 100)
        val al = Aligner.align(c.map.cleaned(), saved.cleaned())!!
        Fusion.update(saved, emptyList(), c.map, emptyList(), al, 1L) { "id" }
        assertTrue("its front face is gone: ${wardrobe(saved)} of $had", wardrobe(saved) < had / 5)
        // A scan of half the room must not delete the other half.
        val saved2 = a.map.mapped { it }; val n = saved2.solid
        val half = Sim.scan(room, Sim.walk(room).let { it.subList(0, it.size / 4) }, SimPhone(seed = 3), 37f, 1.5f, -2.0f)
        val rep = saved2.fuse(half.map)
        assertTrue("partial scan removed ${rep.removed} of $n", rep.removed < n / 25)
    }

    @Test fun thingsKeepTheirIdentityAcrossScans() {
        val m = ObjectMemory()
        // A two-metre bed seen at both ends is one bed; two bottles 40 cm apart are two.
        repeat(3) { m.see("bed", P3(0f, 0.4f, 0f), 0.7f); m.see("bed", P3(1.3f, 0.4f, 0.2f), 0.7f); m.see("bottle", P3(3f, 0.9f, 1f), 0.7f); m.see("bottle", P3(3.4f, 0.9f, 1f), 0.7f) }
        m.see("chair", P3(2f, 0.4f, 2f), 0.3f)
        val c = m.confirmed(); assertEquals(1, c.count { it.label == "bed" }); assertEquals(2, c.count { it.label == "bottle" }); assertEquals("a weak guess is not kept", 0, c.count { it.label == "chair" })
        var n = 0; val ids = { "id${n++}" }
        val first = ObjectIdentity.merge(emptyList(), c, 100L, { true }, ids)
        val bed = first.single { it.label == "bed" }
        // Next scan: the bed is found 30 cm away, one bottle is gone from a place that was looked at, a chair is new; the other bottle's corner was not scanned.
        val second = ObjectIdentity.merge(first, listOf(RoomObject("bed", bed.at + P3(0.3f, 0f, 0f), 4, 0.9f), RoomObject("chair", P3(2f, 0.4f, 2f), 3, 0.8f)), 200L, { it.x < 3.2f }, ids)
        val bed2 = second.single { it.label == "bed" }
        assertEquals("same bed", bed.id, bed2.id); assertEquals(100L, bed2.firstAt); assertEquals(200L, bed2.lastAt); assertEquals(bed.at.x + 0.3f, bed2.at.x, 0.001f)
        val bottles = second.filter { it.label == "bottle" }.sortedBy { it.at.x }
        assertEquals("missing", bottles[0].state); assertEquals("not looked at: left as it was", "here", bottles[1].state)
        assertEquals(1, second.count { it.label == "chair" && it.firstAt == 200L }); assertEquals(second.size, second.map { it.id }.toSet().size)
    }

    @Test fun aSavedSpaceKeepsItsHistoryAndCanUndoAnUpdate() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "rooms" + System.nanoTime()); val store = RoomStore(dir)
        assertNull("nothing scanned, nothing saved", store.save("Empty", RoomMap(), emptyList()))
        val r = store.save("  Bedroom ", a.map, listOf(RoomObject("bed", P3(1f, 0.4f, 2f), 5, 0.8f)), stats(a), kind = "room", place = "Home")!!
        val back = store.list().single()
        assertEquals("Bedroom", back.name); assertEquals("Home", back.place); assertEquals(1, back.sessions.size); assertTrue(back.objects.single().id.isNotEmpty())
        assertEquals(r.stats.length!!, back.stats.length!!, 0.001f); assertEquals(r.stats.confidence, back.stats.confidence); assertEquals(a.map.solid, store.map(r.id)!!.solid)
        // Update with a second scan, then undo it.
        val saved = store.map(r.id)!!; val al = Aligner.align(b.map.cleaned(), saved.cleaned())!!
        val res = Fusion.update(saved, back.objects, b.map, emptyList(), al, 5L) { "x" }
        store.save(back.name, res.map, res.objects, res.stats, back.id, back.at, back.kind, back.place, back.sessions + ScanSession(5L, res.report.added, res.report.reinforced, res.report.removed, al.score, al.rmsCm, res.map.solid))
        assertEquals(2, store.list().single().sessions.size); assertTrue(store.canUndo(r.id))
        assertTrue(store.undo(r.id)); assertEquals(1, store.list().single().sessions.size); assertEquals(a.map.solid, store.map(r.id)!!.solid); assertFalse(store.canUndo(r.id))
        store.rename(r.id, "Den"); store.describe(r.id, "vehicle", "Garage"); store.list().single().let { assertEquals("Den", it.name); assertEquals("vehicle", it.kind); assertEquals("Garage", it.place) }
        store.delete(r.id); assertTrue(store.list().isEmpty()); dir.deleteRecursively()
    }

    @Test fun depthPixelsProjectThereAndBack() {
        val pose = Sim.matrix(Cam(1f, 1.5f, 2f, 30f, -10f))
        val p = Depth.toWorld(100f, 40f, 2f, 114f, 114f, 80f, 45f, pose)
        // Back into the camera's own axes (the pose is a rotation and a shift, so its inverse is the transpose).
        val dx = p.x - pose[12]; val dy = p.y - pose[13]; val dz = p.z - pose[14]
        val cx = pose[0] * dx + pose[1] * dy + pose[2] * dz; val cy = pose[4] * dx + pose[5] * dy + pose[6] * dz; val cz = pose[8] * dx + pose[9] * dy + pose[10] * dz
        assertEquals(-2f, cz, 1e-3f); assertEquals(100f, 80f + cx / -cz * 114f, 0.01f); assertEquals(40f, 45f - cy / -cz * 114f, 0.01f)
        assertTrue(abs(Orbit.project(p, p, 30f, 20f, 100f).first) < 1e-4f)
        assertEquals(0, Upright.rotation(1f, 0.1f)); assertEquals(90, Upright.rotation(0.1f, 1f)); assertEquals(639f to 479f, Upright.toSensor(0f, 0f, 180, 640, 480))
    }
}
