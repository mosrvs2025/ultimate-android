package app.ultimate

import app.ultimate.room.RoomStore
import app.ultimate.room.RoomObject
import app.ultimate.room.Upright
import app.ultimate.room.Alignment
import app.ultimate.room.Depth
import app.ultimate.room.ObjectMemory
import app.ultimate.room.Orbit
import app.ultimate.room.P3
import app.ultimate.room.Relocalizer
import app.ultimate.room.RoomMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class RoomScanTest {
    /** An L-shaped room (so it only fits itself one way round): 4 m by 3 m with a 1.5 by 1 m corner cut out, 2.4 m high, plus a table. Noise in millimetres. */
    private fun room(seed: Long, noise: Float = 0.01f, ceiling: Boolean = true, xFrom: Float = 0f, xTo: Float = 4f): List<P3> {
        val r = Random(seed); val out = ArrayList<P3>()
        fun n() = (r.nextGaussian() * noise).toFloat()
        fun wall(x0: Float, z0: Float, x1: Float, z1: Float) { var t = 0f; while (t <= 1f) { var y = 0f; while (y <= 2.4f) { out += P3(x0 + (x1 - x0) * t + n(), y + n(), z0 + (z1 - z0) * t + n()); y += 0.04f }; t += 0.012f } }
        wall(0f, 0f, 4f, 0f); wall(4f, 0f, 4f, 2f); wall(4f, 2f, 2.5f, 2f); wall(2.5f, 2f, 2.5f, 3f); wall(2.5f, 3f, 0f, 3f); wall(0f, 3f, 0f, 0f)
        var x = 0f; while (x <= 4f) { var z = 0f; while (z <= 3f) { if (!(x > 2.5f && z > 2f)) { out += P3(x + n(), n(), z + n()); if (ceiling) out += P3(x + n(), 2.4f + n(), z + n()) }; z += 0.04f }; x += 0.04f }
        x = 1f; while (x <= 1.8f) { var z = 1f; while (z <= 1.5f) { out += P3(x + n(), 0.75f + n(), z + n()); z += 0.04f }; x += 0.04f }
        return out.filter { it.x in (xFrom - 0.05f)..(xTo + 0.05f) }
    }
    private fun mapOf(pts: List<P3>, times: Int = 3) = RoomMap().also { m -> repeat(times) { pts.forEach { p -> m.add(p.x, p.y, p.z) } } }
    private fun moved(pts: List<P3>, yawDeg: Float, dx: Float, dy: Float, dz: Float): List<P3> { val a = Math.toRadians(yawDeg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat(); return pts.map { P3(it.x * c - it.z * s + dx, it.y + dy, it.x * s + it.z * c + dz) } }

    @Test fun aRoomIsMeasuredWithinAFewCentimetres() {
        val s = mapOf(room(1)).stats()!!
        assertEquals(0f, s.floorY, 0.05f); assertEquals(2.4f, s.height!!, 0.08f)
        assertEquals(4.0f, s.length, 0.15f); assertEquals(3.0f, s.width, 0.15f)
        assertEquals("L-shaped floor: 12 minus the 1.5 m2 corner", 10.5f, s.floorArea, 0.8f)
    }

    @Test fun aRoomScannedAtAnAngleIsStillMeasuredAlongItsWalls() {
        val s = mapOf(moved(room(2), 33f, 2f, 0.4f, -1f)).stats()!!
        assertEquals(4.0f, s.length, 0.2f); assertEquals(3.0f, s.width, 0.2f); assertEquals(0.4f, s.floorY, 0.06f)
    }

    @Test fun withoutACeilingNoHeightIsClaimedAndTooLittleDataSaysNothing() {
        assertNull(mapOf(room(3, ceiling = false).filter { it.y < 1.2f }).stats()!!.height)
        assertNull(RoomMap().stats()); assertNull(mapOf(listOf(P3(0f, 0f, 0f), P3(1f, 1f, 1f))).stats())
    }

    @Test fun strayReadingsSeenOnceAreNotSolid() {
        val m = mapOf(room(4)); val before = m.solid
        val r = Random(9); repeat(5000) { m.add(r.nextFloat() * 4, r.nextFloat() * 2.4f, r.nextFloat() * 3) }
        assertTrue("noise seen once adds almost nothing solid: $before -> ${m.solid}", m.solid - before < before * 0.05f)
    }

    @Test fun aMapSavesAndLoadsExactlyAndRejectsOtherFiles() {
        val m = mapOf(room(5)); val bytes = ByteArrayOutputStream().also { m.write(it) }.toByteArray()
        val back = RoomMap.read(ByteArrayInputStream(bytes))
        assertEquals(m.solid, back.solid); assertEquals(m.points().toSet(), back.points().toSet()); assertEquals(m.stats(), back.stats())
        assertTrue("about 13 bytes a cube", bytes.size < m.solid * 14 + 16)
        assertTrue(runCatching { RoomMap.read(ByteArrayInputStream(ByteArray(64))) }.isFailure)
    }

    @Test fun depthPixelsBecomeRoomPointsAndBack() {
        val id = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        assertEquals(P3(0f, 0f, -2f), Depth.toWorld(80f, 45f, 2f, 100f, 100f, 80f, 45f, id))
        val p = Depth.toWorld(130f, 20f, 2f, 100f, 100f, 80f, 45f, id); assertEquals(1f, p.x, 1e-4f); assertEquals("up in the picture is up in the room", 0.5f, p.y, 1e-4f)
        val shifted = id.copyOf().also { it[12] = 3f; it[13] = 1f }; assertEquals(P3(3f, 1f, -2f), Depth.toWorld(80f, 45f, 2f, 100f, 100f, 80f, 45f, shifted))
        val proj = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, -1f, -1f, 0f, 0f, -0.2f, 0f)
        val s = Depth.toScreen(P3(0f, 0f, -2f), id, proj)!!; assertEquals(0.5f, s.first, 1e-4f); assertEquals(0.5f, s.second, 1e-4f)
        assertTrue(Depth.toScreen(P3(1f, 0f, -2f), id, proj)!!.first > 0.5f); assertNull("behind the camera", Depth.toScreen(P3(0f, 0f, 2f), id, proj))
    }

    @Test fun aSavedRoomIsRecognisedFromANewSessionThatStartedSomewhereElse() {
        val saved = mapOf(room(10)).points()
        val fresh = mapOf(moved(room(11), -70f, 1.3f, 0.2f, -2.1f)).points()
        val a = Relocalizer.align(fresh, 0.2f, saved, 0f)!!
        assertTrue("score ${a.score}", a.score > 0.85f)
        // A known spot (the table's corner) lands back where it was, to within 15 cm.
        val there = moved(listOf(P3(1f, 0.75f, 1f)), -70f, 1.3f, 0.2f, -2.1f)[0]
        assertTrue("error ${a.apply(there).dist(P3(1f, 0.75f, 1f))}", a.apply(there).dist(P3(1f, 0.75f, 1f)) < 0.15f)
    }

    @Test fun halfTheRoomIsEnoughToFindYourPlaceInIt() {
        val saved = mapOf(room(12)).points()
        val half = mapOf(moved(room(13, xFrom = 0f, xTo = 2.2f), 140f, -0.6f, 0f, 0.9f)).points()
        val a = Relocalizer.align(half, 0f, saved, 0f)!!
        val there = moved(listOf(P3(0f, 1f, 0f)), 140f, -0.6f, 0f, 0.9f)[0]
        assertTrue("error ${a.apply(there).dist(P3(0f, 1f, 0f))}", a.apply(there).dist(P3(0f, 1f, 0f)) < 0.2f)
    }

    @Test fun aDifferentRoomIsNotMistakenForTheSavedOne() {
        val saved = mapOf(room(14)).points()
        val r = Random(3); val other = ArrayList<P3>()
        // A long corridor, 7 m by 1.2 m.
        var t = 0f; while (t <= 7f) { var y = 0f; while (y <= 2.4f) { other += P3(t, y, 0f); other += P3(t, y, 1.2f); y += 0.04f }; t += 0.04f }
        repeat(2000) { other += P3(r.nextFloat() * 7, 0f, r.nextFloat() * 1.2f) }
        assertNull(Relocalizer.align(mapOf(other).points(), 0f, saved, 0f))
        assertNull("too little scanned to say", Relocalizer.align(listOf(P3(0f, 1f, 0f)), 0f, saved, 0f))
    }

    @Test fun objectsNeedSeveralSightingsAndNearbyOnesOfTheSameKindMerge() {
        val m = ObjectMemory()
        m.see("chair", P3(1f, 0.4f, 1f), 0.6f); m.see("chair", P3(1.1f, 0.45f, 0.95f), 0.8f)
        assertTrue("two sightings are not yet enough", m.confirmed().isEmpty())
        m.see("chair", P3(0.9f, 0.4f, 1.05f), 0.7f); m.see("chair", P3(3f, 0.4f, 2f), 0.9f); m.see("tv", P3(1f, 1f, 1f), 0.9f)
        val c = m.confirmed(); assertEquals(1, c.size); assertEquals("chair", c[0].label); assertEquals(3, c[0].seen); assertEquals(0.8f, c[0].best, 0f)
        assertEquals(1.0f, c[0].at.x, 0.01f); assertEquals(1.0f, c[0].at.z, 0.01f)
    }

    @Test fun theViewerOrbitsRoundTheMiddleOfTheRoom() {
        val c = P3(2f, 1f, 1.5f)
        Orbit.project(c, c, 30f, 20f, 100f).let { assertTrue(abs(it.first) + abs(it.second) + abs(it.third) < 1e-5f) }
        val right = Orbit.project(P3(3f, 1f, 1.5f), c, 0f, 0f, 100f); assertEquals(100f, right.first, 0.01f); assertEquals(0f, right.second, 0.01f)
        val up = Orbit.project(P3(2f, 2f, 1.5f), c, 0f, 0f, 100f); assertEquals("up in the room is up on screen", -100f, up.second, 0.01f)
        val turned = Orbit.project(P3(3f, 1f, 1.5f), c, 90f, 0f, 100f); assertTrue(abs(turned.first) < 0.01f && abs(turned.third - 1f) < 0.01f)
        assertNotNull(Alignment(0f, 0f, 0f, 0f, 1f).apply(c))
    }

    @Test fun aSavedRoomComesBackRenamesAndIsForgotten() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "rooms" + System.nanoTime())
        val store = RoomStore(dir); val map = mapOf(room(14))
        assertNull("an empty scan is not saved", store.save("Empty", RoomMap(), emptyList()))
        val r = store.save("  Living room ", map, listOf(RoomObject("couch", P3(1f, 0.4f, 2f), 5, 0.8f)))!!
        val back = store.list().single()
        assertEquals(r.id, back.id); assertEquals("Living room", back.name); assertEquals("couch", back.objects.single().label); assertEquals(2f, back.objects.single().at.z, 0.001f)
        assertEquals(map.stats()!!.voxels, store.map(r.id)!!.stats()!!.voxels); assertTrue(store.bytes(r.id) > 0)
        store.rename(r.id, "Den"); assertEquals("Den", store.list().single().name)
        // Updating a room keeps its id, so it stays one room.
        store.save("Den", map, emptyList(), r.id, r.at); assertEquals(1, store.list().size)
        store.delete(r.id); assertTrue(store.list().isEmpty()); assertNull(store.map(r.id))
        dir.deleteRecursively()
    }

    @Test fun mergingAnotherScanAddsItsPointsAsSolid() {
        val m = RoomMap(); m.addSolid(1f, 1f, 1f); assertEquals(1, m.solid)
        m.add(2f, 1f, 1f); assertEquals("one sighting is still not solid", 1, m.solid)
    }

    @Test fun theDetectorsPictureIsTurnedUprightAndItsPixelsMapBack() {
        assertEquals(0, Upright.rotation(1f, 0.1f)); assertEquals(90, Upright.rotation(0.1f, 1f)); assertEquals(180, Upright.rotation(-1f, 0f)); assertEquals(270, Upright.rotation(0f, -1f))
        val w = 640; val h = 480
        assertEquals(10f to 20f, Upright.toSensor(10f, 20f, 0, w, h))
        // Turned a quarter: the upright picture is 480 wide; its top-left is the sensor's bottom-left.
        assertEquals(0f to 479f, Upright.toSensor(0f, 0f, 90, w, h)); assertEquals(639f to 0f, Upright.toSensor(479f, 639f, 90, w, h))
        assertEquals(639f to 479f, Upright.toSensor(0f, 0f, 180, w, h)); assertEquals(639f to 0f, Upright.toSensor(0f, 0f, 270, w, h))
        for (rot in listOf(0, 90, 180, 270)) { val (x, y) = Upright.toSensor(5f, 7f, rot, w, h); assertTrue(x in 0f..639f && y in 0f..479f) }
    }
}
