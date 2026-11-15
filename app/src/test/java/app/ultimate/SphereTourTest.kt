package app.ultimate

import app.ultimate.spheres.SphereTour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

class SphereTourTest {
    private fun stop(id: String, x: Double, z: Double, y: Double = 0.0) = SphereTour.Stop(id, x, y, z, 0.95, 1L)
    private val yard = SphereTour("t", "Backyard", 1L, listOf(stop("door", 0.0, 0.0), stop("lawn", 0.0, -3.0), stop("fence", 3.0, -3.0, y = 0.5), stop("shed", 20.0, -20.0)))

    @Test fun doorwaysPointTheRightWay() {
        val l = yard.links("door").associateBy { it.to }
        assertEquals(0.0, l.getValue("lawn").lonDeg, 1e-9) // straight ahead (-Z)
        assertEquals(3.0, l.getValue("lawn").metres, 1e-9)
        assertEquals(45.0, l.getValue("fence").lonDeg, 1e-9) // ahead and to the right
        assertTrue(l.getValue("fence").pitchDeg > 0) // a little uphill
        assertTrue("shed" !in l) // too far, and the door already has two nearer
        val fromLawn = yard.links("lawn").associateBy { it.to }
        assertEquals(180.0, kotlin.math.abs(fromLawn.getValue("door").lonDeg), 1e-9) // behind
        assertEquals(90.0, fromLawn.getValue("fence").lonDeg, 1e-9) // to the right
    }

    @Test fun aLonelyStopStillGetsItsNearest() {
        val l = yard.links("shed")
        assertEquals(2, l.size)
    }

    @Test fun keptAndReadBack() {
        val withGeo = yard.copy(stops = yard.stops.map { if (it.id == "door") it.copy(lat = 51.5, lon = -0.12) else it })
        val back = SphereTour.fromJson(withGeo.toJson())
        assertEquals(withGeo, back)
        assertNotNull(org.json.JSONObject(withGeo.toViewerJson()).getJSONObject("links").getJSONArray("door"))
    }

    @Test fun theNextSpotIsAheadAndNotOnTopOfAnother() {
        val first = SphereTour.nextSpot(emptyList(), 0.0, 0.0, 0.0)
        assertEquals(0.0, first[0], 1e-9); assertEquals(-2.0, first[1], 1e-9) // two metres straight ahead
        val taken = listOf(stop("a", 0.0, 0.0), stop("b", 0.0, -2.0))
        val n = SphereTour.nextSpot(taken, 0.0, 0.0, 0.0)
        assertTrue(taken.all { hypot(it.x - n[0], it.z - n[1]) >= 1.3 })
        val right = SphereTour.nextSpot(emptyList(), 0.0, 0.0, PI / 2)
        assertEquals(2.0, right[0], 1e-9); assertEquals(0.0, right[1], 1e-9)
    }

    @Test fun theStoreOnlyServesThePlacesOwnFiles() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val st = app.ultimate.spheres.SphereStore(dir)
        st.save(yard); st.image("t", "door").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(listOf("t"), st.list().map { it.id })
        assertEquals(3, st.serve("t", "sphere/door.jpg")!!.first.size)
        assertEquals(null, st.serve("t", "sphere/../../etc/passwd.jpg"))
        assertEquals(null, st.serve("t", "sphere/nobody.jpg"))
        assertTrue(String(st.serve("t", "tour.json")!!.first).contains("links"))
        st.delete("t"); assertTrue(st.list().isEmpty())
        dir.deleteRecursively()
    }
}
