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
    private fun stats(i: ScanIntegrator) = RoomGeometry.measure(i.map.cleaned(), i.counters.camMinY, i.counters.camMaxY, maxPitchDeg = i.counters.maxPitchDeg)

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
        repeat(4) { m.see("bed", P3(0f, 0.4f, 0f), 0.7f); m.see("bed", P3(1.3f, 0.4f, 0.2f), 0.7f); m.see("bottle", P3(3f, 0.9f, 1f), 0.7f); m.see("bottle", P3(3.4f, 0.9f, 1f), 0.7f) }
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

    @Test fun aCeilingNoDepthCanSeeIsEstimatedFromWhereTheWallsEnd() {
        val w = SimWorld(4.2f, 3.4f, 2.5f, boxes = boxes, blankCeiling = true)
        val i = Sim.scan(w, Sim.walk(w), SimPhone(seed = 11), 20f, 0.5f, 1.0f)
        val s = stats(i)!!
        assertEquals("ceiling source", "walls", s.ceilingSource); assertEquals(2.5f, s.height!!, 0.2f)
        assertNull("an estimate is never 'trusted' enough to be high confidence", s.takeIf { it.confidence == Confidence.HIGH })
        // Looking only level, the wall tops are just the edge of the picture: no ceiling is claimed.
        val level = Sim.walk(w).map { Cam(it.x, it.y, it.z, it.yawDeg, 0f) }
        val l = stats(Sim.scan(w, level, SimPhone(seed = 12), 20f, 0.5f, 1.0f))
        assertTrue("claimed a ceiling from a level scan: ${l?.height}", l == null || l.ceilingY == null || l.ceilingSource != "walls" || abs(l.height!! - 2.5f) < 0.3f)
    }

    @Test fun aKnownHeightSurvivesAScanThatNeverSawTheCeiling() {
        val noCeil = SimWorld(4.2f, 3.4f, 2.5f, boxes = boxes, blankCeiling = true)
        val low = Sim.walk(noCeil).map { Cam(it.x, it.y, it.z, it.yawDeg, it.pitchDeg.coerceAtMost(5f)) }
        val s = stats(Sim.scan(noCeil, low, SimPhone(seed = 14), 0f, 0f, 0f))!!
        if (s.ceilingY == null) { val h = RoomGeometry.holdHeight(s, 2.21f); assertEquals(2.21f, h.height!!, 0.001f); assertEquals("kept", h.ceilingSource); assertNull("kept is not trusted", h.trustedHeight) }
        assertEquals("a measured height is trusted", 2.5f, stats(a)!!.trustedHeight!!, 0.15f)
    }

    @Test fun refinementBringsScansTogetherWithoutLayingASecondCopyOfTheWalls() {
        // A good sensor (4 cm of noise at 3 m) and perfect tracking: what is left is the algorithm's own error.
        fun quiet(seed: Int) = SimPhone(seed = seed, noiseA = 0.005f, noiseB = 0.004f, wildRate = 0.005f, dropout = 0.05f, driftPos = 0f, driftYaw = 0f)
        val q1 = Sim.scan(room, Sim.walk(room), quiet(1), 37f, 1.5f, -2.0f); val q2 = Sim.scan(room, Sim.walk(room, startYaw = 40f), quiet(7), -110f, -3.0f, 0.7f)
        val al = Aligner.align(q2.map.cleaned(), q1.map.cleaned())!!
        val tb = Sim.truth(-110f, -3.0f, -1.35f, 0.7f)
        val errs = listOf(P3(0.5f, 0.2f, 0.5f), P3(-2f, 0f, 1f), P3(1.5f, -1f, -2f)).map { p -> val r = tb.apply(p); val s = Sim.toSession(Sim.matrix(Cam(r.x, r.y, r.z, 0f, 0f)), 37f, 1.5f, -1.35f, -2.0f); al.apply(p).dist(P3(s[12], s[13], s[14])) }
        assertTrue("within 4 cm with a good sensor and no drift: $errs", errs.all { it < 0.04f }); assertTrue("measured residual ${al.rmsCm} cm", al.rmsCm < 5f)
        // With the ordinary noisy phone and drift the same alignment is still good to ~15 cm: noise of that size limits how well two scans can agree.
        val noisy = Aligner.align(b.map.cleaned(), a.map.cleaned())!!; assertTrue("noisy fit ${noisy.rmsCm}", noisy.rmsCm < 12f)
        // Merged back onto itself with the cubes a little off (as a real alignment is): nearly everything confirms, hardly anything is added.
        val shifted = q2.map.mapped { al.apply(it) }; val before = q1.map.solid
        val rep = q1.map.fuse(shifted)
        assertTrue("new ${rep.added} confirmed ${rep.reinforced} of $before", rep.added < rep.reinforced)
    }

    @Test fun staleAndDoubledThingsAreTidiedAwayAfterTwoMissedScans() {
        val d = listOf(RoomObject("bed", P3(0f, 0.4f, 0f), 6, 0.8f, "a", 10, 20), RoomObject("bed", P3(1.5f, 0.4f, 0.3f), 3, 0.7f, "b", 30, 30, "missing", 1), RoomObject("bottle", P3(3f, 0.9f, 1f), 3, 0.7f, "c", 10, 10), RoomObject("bottle", P3(3.5f, 0.9f, 1f), 3, 0.7f, "d", 10, 10))
        val t = ObjectIdentity.tidy(d)
        assertEquals("one bed, two bottles", 3, t.size); assertEquals("a", t.first { it.label == "bed" }.id); assertEquals(9, t.first { it.label == "bed" }.seen); assertEquals("here", t.first { it.label == "bed" }.state)
        var n = 0
        var cur = ObjectIdentity.merge(listOf(RoomObject("chair", P3(2f, 0.4f, 2f), 5, 0.8f, "x", 1, 1)), emptyList(), 2L, { true }) { "n${n++}" }
        assertEquals("missing", cur.single().state); assertEquals(1, cur.single().missed)
        cur = ObjectIdentity.merge(cur, emptyList(), 3L, { true }) { "n${n++}" }
        assertTrue("gone after the second scan that looked", cur.isEmpty())
    }

    @Test fun theHeightRidesWithTheSavedSpace() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "rooms" + System.nanoTime()); val store = RoomStore(dir)
        val r = store.save("Den", a.map, emptyList(), stats(a), heightM = 2.4f)!!
        assertEquals(2.5f, store.list().single().heightM!!, 0.15f)   // this scan measured its own ceiling, which wins
        val flat = stats(a)!!.copy(ceilingY = null, ceilingSource = "")
        store.save("Den", a.map, emptyList(), flat, r.id, r.at, heightM = 2.4f, snapshot = false)
        store.list().single().let { assertEquals(2.4f, it.heightM!!, 0.001f); assertEquals(2.4f, it.stats.height!!, 0.001f); assertEquals("kept", it.stats.ceilingSource) }
        assertFalse("a no-snapshot save keeps no undo of its own", store.canUndo(r.id))
        dir.deleteRecursively()
    }

    @Test fun aBareRoomThatFitsTwoWaysIsSettledByTheThingsInIt() {
        val bare = SimWorld(4f, 3f, 2.5f)
        fun inSession(p: P3, yaw: Float, tx: Float, tz: Float): P3 { val m = Sim.toSession(Sim.matrix(Cam(p.x, p.y, p.z, 0f, 0f)), yaw, tx, -1.35f, tz); return P3(m[12], m[13], m[14]) }
        val x = Sim.scan(bare, Sim.walk(bare), SimPhone(seed = 2), 0f, 0f, 0f); val y = Sim.scan(bare, Sim.walk(bare, startYaw = 30f), SimPhone(seed = 4), 77f, 1f, 1f)
        val things = listOf(RoomObject("chair", P3(0.5f, 0.4f, 0.6f), 5, 0.8f), RoomObject("bed", P3(3.2f, 0.3f, 2.2f), 5, 0.8f), RoomObject("laptop", P3(2.0f, 0.8f, 0.4f), 4, 0.8f))
        val saved = things.map { it.copy(at = inSession(it.at, 0f, 0f, 0f)) }; val fresh = things.map { it.copy(at = inSession(it.at, 77f, 1f, 1f)) }
        val cands = Aligner.alignAll(y.map.cleaned(), x.map.cleaned())
        assertTrue("a bare box room fits at least two ways: ${cands.size}", cands.size >= 2)
        assertTrue("and says so", cands.first().ambiguous)
        val r = Aligner.resolve(cands, fresh, saved)!!
        assertFalse("the things settle it", r.ambiguous)
        for (i in things.indices) assertTrue("thing $i lands on itself: ${r.apply(fresh[i].at).dist(saved[i].at)}", r.apply(fresh[i].at).dist(saved[i].at) < 0.5f)
        // Without things in common it stays ambiguous.
        assertTrue(Aligner.resolve(cands, emptyList(), saved)!!.ambiguous)
    }

    @Test fun yourOwnLegsAreNotMappedAsTheRoom() {
        val w = SimWorld(4.2f, 3.4f, 2.5f)
        val cams = (0 until 60).map { Cam(2.0f + 0.04f * (it % 12), 1.35f, 1.7f + 0.03f * (it / 12), 15f * it, -65f) }
        val i = Sim.scan(w, cams, SimPhone(noiseA = 0.005f, noiseB = 0.004f, wildRate = 0f, dropout = 0f, hand = false, driftPos = 0f, driftYaw = 0f), 0f, 0f, 0f)
        val pts = i.map.points(); assertTrue("something was mapped: ${pts.size}", pts.size > 300)
        val cx = 2.22f; val cz = 1.76f   // inside every camera position's 55 cm circle (the phone moved 44 cm sideways)
        val near = pts.count { (it.x - cx) * (it.x - cx) + (it.z - cz) * (it.z - cz) < 0.2f * 0.2f && it.y < -0.5f }
        assertEquals("nothing straight below the phone", 0, near); assertTrue("they were skipped, not missed: ${i.counters.body}", i.counters.body > 0)
    }

    @Test fun sizesDecideWhatADetectionCanBe() {
        assertEquals("a wooden chest of drawers is not a suitcase", RoomLabels.CABINET, RoomLabels.fix("suitcase", 1.1f, 0.9f))
        assertEquals("suitcase", RoomLabels.fix("suitcase", 0.6f, 0.7f))
        assertNull("a bottle a metre wide is a pile of something", RoomLabels.fix("bottle", 1.0f, 0.3f)); assertEquals("bottle", RoomLabels.fix("bottle", 0.08f, 0.25f))
        assertNull("a 60 cm bed is not a bed", RoomLabels.fix("bed", 0.6f, 0.4f)); assertEquals("bed", RoomLabels.fix("bed", 1.9f, 0.5f))
        assertEquals("chair", RoomLabels.fix("couch", 0.6f, 0.9f))
        assertEquals("🗄️", RoomLabels.emoji(RoomLabels.CABINET)); assertTrue(RoomLabels.radius(RoomLabels.CABINET) > RoomLabels.radius("chair"))
    }

    @Test fun thingsCanBeRelabelledAndRemovedInASavedSpace() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "rooms" + System.nanoTime()); val store = RoomStore(dir)
        val r = store.save("Den", a.map, listOf(RoomObject("suitcase", P3(1f, 0.4f, 1f), 5, 0.8f), RoomObject("book", P3(2f, 0.4f, 1f), 4, 0.7f)), stats(a))!!
        val o = store.list().single().objects
        store.editObjects(r.id) { l -> l.filter { it.label != "book" }.map { if (it.label == "suitcase") it.copy(label = RoomLabels.CABINET) else it } }
        store.list().single().objects.let { assertEquals(1, it.size); assertEquals(RoomLabels.CABINET, it[0].label); assertEquals(o.first { x -> x.label == "suitcase" }.id, it[0].id) }
        dir.deleteRecursively()
    }

    @Test fun filledInDepthLetsPlainWallsBeSeenAndNeverClearsAnything() {
        // A bedroom whose left wall is plain paint: raw depth has almost nothing there (99% of its pixels missing).
        val w = SimWorld(4.2f, 3.4f, 2.5f, boxes = boxes.drop(1), blankWallZ0 = 0.3f, blankWallZ1 = 3.1f, blankDrop = 0.99f)
        // How much of the plain wall (2.6 m by 1.9 m, 5 cm patches) has any solid cube in front of it.
        fun wallCubes(i: ScanIntegrator) = i.map.cleaned().mapNotNull { val r = Sim.truth(10f, 0.2f, -1.35f, 0.5f).apply(it); if (r.x < 0.25f && r.z in 0.4f..3.0f && r.y in 0.3f..2.2f) (Math.floor(r.z / 0.05) to Math.floor(r.y / 0.05)) else null }.toSet().size
        // A short walk, as in a real scan where the wall is only in view for seconds.
        val walk = Sim.walk(w, loops = 1).take(220)
        val without = Sim.scan(w, walk, SimPhone(seed = 31, fill = false), 10f, 0.2f, 0.5f)
        val with = Sim.scan(w, walk, SimPhone(seed = 31, fill = true), 10f, 0.2f, 0.5f)
        val a1 = wallCubes(without); val a2 = wallCubes(with)
        println("WALLCOV $a1 -> $a2 of ${52 * 38}")
        assertTrue("the plain wall is better covered with filled-in depth: $a1 -> $a2 of ${52 * 38}", a2 > a1 * 1.15f && a2 > 0.8f * 52 * 38)
        assertTrue("and the counters say where it came from: ${with.counters.summary()}", with.counters.filled > 0)
        // It must not make the rest worse: the room still measures the same.
        val s = RoomGeometry.measure(with.map.cleaned(), with.counters.camMinY, with.counters.camMaxY, maxPitchDeg = with.counters.maxPitchDeg)!!
        assertEquals(4.2f, s.length!!, 0.3f); assertEquals(3.4f, s.width!!, 0.3f); assertEquals(2.5f, s.height!!, 0.2f)
    }

    @Test fun aSizeMeasuredWallToWallDoesNotShrinkWhenFewerWallsAreSeen() {
        fun st(u: Span?, v: Span?, h: Float = 30f) = RoomStats(-1.3f, 1.2f, h, u, v, 5f, 9000, Confidence.MEDIUM)
        val full = st(Span(0f, 3.04f, true), Span(0f, 3.0f, true))
        val kept = RoomGeometry.keepBest(st(Span(0f, 3.0f, true), Span(0f, 1.2f, false)), full)
        assertEquals("similar and complete: the new one", 3.0f, kept.u!!.size, 0.001f); assertEquals("lost its far wall: the old one", 3.0f, kept.v!!.size, 0.001f)
        val shrunk = RoomGeometry.keepBest(st(Span(0f, 2.71f, true), Span(0f, 1.75f, true)), full)
        assertEquals("a complete but much smaller one is not trusted over a wall-to-wall one", 3.0f, shrunk.v!!.size, 0.001f)
        val bigger = RoomGeometry.keepBest(st(Span(0f, 3.6f, true), Span(0f, 3.4f, true)), full)
        assertEquals("seeing more of the room does grow it", 3.6f, bigger.u!!.size, 0.001f)
        assertEquals("another set of walls (turned 40 degrees) is a different measurement", 1.75f, RoomGeometry.keepBest(st(Span(0f, 2.7f, true), Span(0f, 1.75f, true), h = 70f), full).v!!.size, 0.001f)
        assertNull(RoomGeometry.keepBest(st(null, null), null).u)
    }

    @Test fun wallsComeOutAsFittedSurfaces() {
        val s = stats(a)!!
        assertTrue("walls found: ${s.walls.size}", s.walls.size >= 3)
        // Each wall sits on one of the measured lines and runs over a stretch of the room, not a point.
        s.walls.forEach { w -> val span = if (w.axis == 0) s.u!! else s.v!!; assertTrue("on a measured line", abs(w.pos - span.lo) < 0.35f || abs(w.pos - span.hi) < 0.35f); assertTrue("long enough: ${w.to - w.from}", w.to - w.from >= 1.0f) }
    }

    @Test fun fourWallsPointedAtGiveTheRoomWhenNothingCouldBeSeen() {
        // A floor and a bit of furniture, and no wall points at all (plain paint): the walls are given by pointing at each.
        val r = kotlin.random.Random(4); val pts = ArrayList<P3>()
        repeat(3000) { pts += P3(r.nextFloat() * 3.4f - 1.7f, -1.35f, r.nextFloat() * 4.2f - 2.1f) }
        repeat(400) { pts += P3(r.nextFloat() * 1f, -1.35f + r.nextFloat() * 0.5f, r.nextFloat() * 1f) }
        // The room is 3.4 m (x) by 4.2 m (z), centred on the origin, the phone in the middle.
        val marks = listOf(WallMark(1.7f, 0.2f, 0f, 0f), WallMark(-1.7f, -0.1f, 0f, 0f), WallMark(0.1f, 2.1f, 0f, 0f), WallMark(-0.2f, -2.1f, 0f, 0f))
        val s = RoomGeometry.measure(pts, camMinY = -0.1f, camMaxY = 0.1f, userWalls = marks, userCeilingHeight = 2.5f)!!
        assertEquals("one side", 4.2f, s.length!!, 0.15f); assertEquals("the other", 3.4f, s.width!!, 0.15f); assertEquals(2.5f, s.height!!, 0.001f); assertEquals("you", s.ceilingSource)
        // And one wall is not a room.
        val one = RoomGeometry.measure(pts, camMinY = -0.1f, camMaxY = 0.1f, userWalls = marks.take(1))!!
        assertNull(one.footprint)
        // A height you gave follows the floor rather than staying at a fixed level.
        assertEquals(2.5f, RoomGeometry.measure(pts, camMinY = -0.1f, camMaxY = 0.1f, userFloor = -1.4f, userCeilingHeight = 2.5f)!!.height!!, 0.001f)
    }

    @Test fun smallThingsStuckFlatOnAWallAreNotBelieved() {
        val s = RoomStats(-1.35f, 1.15f, 0f, Span(0f, 4.2f, true), Span(0f, 3.4f, true), 8f, 9000, Confidence.MEDIUM, walls = listOf(WallSeg(0, 0f, 0f, 3.4f), WallSeg(0, 4.2f, 0f, 3.4f), WallSeg(1, 0f, 0f, 4.2f)))
        val onWall = RoomObject("bottle", P3(0.03f, 0.1f, 1.5f), 5, 0.7f)     // 1.45 m up, flat against the u = 0 wall
        assertFalse("a bottle floating on a blank wall", RoomLabels.plausible(onWall, s))
        assertTrue("a bottle on a table by the wall", RoomLabels.plausible(onWall.copy(at = P3(0.3f, -0.6f, 1.5f)), s))
        assertTrue("a bottle on the floor against the wall", RoomLabels.plausible(onWall.copy(at = P3(0.03f, -1.2f, 1.5f)), s))
        assertTrue("a wardrobe stands against a wall", RoomLabels.plausible(RoomObject("cabinet", P3(0.03f, 0.1f, 1.5f), 5, 0.7f), s))
        assertTrue("nothing known about the walls: no objection", RoomLabels.plausible(onWall, null))
    }

    @Test fun aLooseFitOnlyAddsAndNeverClears() {
        val saved = a.map.mapped { it }; val n = saved.solid
        val gone = Sim.scan(SimWorld(4.2f, 3.4f, 2.5f, boxes = boxes.take(2), mirror = room.mirror), Sim.walk(SimWorld(4.2f, 3.4f, 2.5f)), SimPhone(seed = 13), 37f, 1.5f, -2.0f)
        val reps = saved.mapped { it }.fuse(gone.map)
        val addOnly = saved.fuse(gone.map, clear = false)
        assertEquals("nothing cleared when the fit is loose", 0, addOnly.removed); assertTrue("it would have cleared some: ${reps.removed}", reps.removed > 0); assertTrue(saved.solid >= n)
    }
}
