package app.ultimate

import app.ultimate.fusion.Blueprints
import app.ultimate.fusion.Blueprints.Outcome
import app.ultimate.fusion.FusedPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BlueprintsTest {
    /** A walk round a [w] x [h] m yard, [laps] times at 1.3 m/s, starting at ([lat], [lon]) + [shift] metres, with GPS-like noise. */
    private fun yard(lat: Double = 40.0, lon: Double = -74.0, w: Double = 24.0, h: Double = 14.0, laps: Int = 3, shiftE: Double = 0.0, shiftN: Double = 0.0, seed: Long = 1): FusedPath {
        val r = Random(seed); val per = 2 * (w + h); val total = per * laps; val n = (total / 1.3).toInt()
        val t = DoubleArray(n) { it.toDouble() }; val e = DoubleArray(n); val nn = DoubleArray(n)
        for (i in 0 until n) {
            var d = (i * 1.3) % per
            val (x, y) = when { d < w -> d to 0.0; d < w + h -> w to d - w; d < 2 * w + h -> (w - (d - w - h)) to h; else -> 0.0 to (h - (d - 2 * w - h)) }
            e[i] = x + shiftE + r.nextGaussian() * 1.4; nn[i] = y + shiftN + r.nextGaussian() * 1.4
        }
        return FusedPath(t, e, nn, Array(n) { "GPS" }, "north-up (GPS)", null).also { it.anchor = doubleArrayOf(lat, lon) }
    }
    private fun street(): FusedPath { // 300 m straight out, nothing like a yard
        val n = 230; val e = DoubleArray(n) { it * 1.3 }; val nn = DoubleArray(n) { 0.0 }
        return FusedPath(DoubleArray(n) { it.toDouble() }, e, nn, Array(n) { "GPS" }, "north-up (GPS)", null).also { it.anchor = doubleArrayOf(40.0, -74.0) }
    }
    private fun fp(p: FusedPath, turns: Int = 12) = Blueprints.footprint(p, turns, p.length)!!

    @Test fun `the same yard on another day is the same place even with noise and drift`() {
        val a = fp(yard(seed = 1)); val b = fp(yard(seed = 2)); val c = fp(yard(seed = 3, shiftE = 7.0, shiftN = -4.0))
        assertTrue("noise ${Blueprints.overlap(a, b)}", Blueprints.overlap(a, b) >= 0.75)
        assertTrue("drift ${Blueprints.overlap(a, c)}", Blueprints.overlap(a, c) >= Blueprints.SAME)
    }

    @Test fun `a different yard, a street, and a far away place are not the same`() {
        val a = fp(yard(seed = 1))
        assertTrue(Blueprints.overlap(a, fp(yard(lat = 40.0004, seed = 2))) < 0.2)     // ~45 m north: next door
        assertTrue(Blueprints.overlap(a, fp(street(), turns = 0)) < Blueprints.SAME)
        assertEquals(0.0, Blueprints.overlap(a, fp(yard(lat = 41.0, seed = 3))), 0.0)  // another city
    }

    @Test fun `the second visit offers a name, then every visit is tagged with its number and pace`() {
        var places = emptyList<Blueprints.Place>()
        val f1 = fp(yard(seed = 1)); val f2 = fp(yard(seed = 2)); val f3 = fp(yard(seed = 3))
        var (p, o) = Blueprints.see(places, "w1", f1, 250.0, 200.0); places = p
        assertTrue(o is Outcome.Nothing && places.size == 1)
        val second = Blueprints.see(places, "w2", f2, 252.0, 200.0); places = second.first
        val offer = second.second as Outcome.Offer
        places = Blueprints.name(places, offer.place.id, "  Backyard loop ")
        assertEquals("Backyard loop", places.single().name)
        // Asking again about walk 2 changes nothing and now it is a named visit.
        val again = Blueprints.see(places, "w2", f2, 252.0, 200.0); assertEquals(places, again.first)
        val v2 = again.second as Outcome.Visit; assertEquals(2, v2.visit)
        // A third, 10% faster walk.
        val third = Blueprints.see(places, "w3", f3, 250.0, 180.0); places = third.first
        val v3 = third.second as Outcome.Visit; assertEquals(3, v3.visit); assertEquals(0.11, v3.pace!!, 0.02)
        assertEquals("Pace 11% faster than your usual here.", Blueprints.paceWords(v3.pace))
        assertNull(Blueprints.paceWords(0.02)); assertEquals("Pace 8% slower than your usual here.", Blueprints.paceWords(-0.08))
    }

    @Test fun `declining stops the offers and a different place is kept apart`() {
        var places = emptyList<Blueprints.Place>()
        places = Blueprints.see(places, "w1", fp(yard(seed = 1)), 250.0, 200.0).first
        val o = Blueprints.see(places, "w2", fp(yard(seed = 2)), 250.0, 200.0); places = o.first
        places = Blueprints.decline(places, (o.second as Outcome.Offer).place.id)
        assertTrue(Blueprints.see(places, "w3", fp(yard(seed = 3)), 250.0, 200.0).second is Outcome.Nothing)
        val other = Blueprints.see(places, "s1", fp(street(), 0), 300.0, 230.0); assertEquals(2, other.first.size)
    }

    @Test fun `too short or unplaced walks are not places and ordinals read well`() {
        val p = yard(); p.anchor = null
        assertNull(Blueprints.footprint(p, 5, 100.0)); assertNull(Blueprints.footprint(yard().also { }, 5, 10.0))
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "21st", "112th"), listOf(1, 2, 3, 4, 11, 12, 21, 112).map { Blueprints.ordinal(it) })
    }

    @Test fun `places survive saving and a damaged file is ignored`() {
        var places = Blueprints.see(emptyList(), "w1", fp(yard(seed = 1)), 250.0, 200.0).first
        places = Blueprints.name(places, places.single().id, "Backyard loop")
        val back = Blueprints.decode(Blueprints.encode(places))
        assertEquals(1, back.size); assertEquals("Backyard loop", back[0].name); assertEquals(places[0].footprint.cells, back[0].footprint.cells); assertEquals(places[0].runs, back[0].runs)
        assertNotNull(Blueprints.overlap(back[0].footprint, places[0].footprint)); assertEquals(1.0, Blueprints.overlap(back[0].footprint, places[0].footprint), 1e-9)
        assertEquals(emptyList<Blueprints.Place>(), Blueprints.decode("garbage")); assertEquals(emptyList<Blueprints.Place>(), Blueprints.decode(null))
    }
}
