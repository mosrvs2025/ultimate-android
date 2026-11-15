package app.ultimate

import app.ultimate.fusion.Buildings
import app.ultimate.fusion.Buildings.Building
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildingsTest {
    private fun house(x0: Double = 0.0, y0: Double = 0.0, w: Double = 12.0, h: Double = 8.0, height: Double = 5.5) =
        Building(listOf(doubleArrayOf(x0, y0), doubleArrayOf(x0 + w, y0), doubleArrayOf(x0 + w, y0 + h), doubleArrayOf(x0, y0 + h)), height)

    @Test fun `heights come from the tag, the levels, or the kind of building`() {
        fun h(json: String) = Buildings.heightOf(JSONObject(json))
        assertEquals(7.5, h("""{"building":"yes","height":"7.5"}"""), 1e-9); assertEquals(9.0, h("""{"height":"9 m"}"""), 1e-9)
        assertEquals(6.8, h("""{"building":"house","building:levels":"2"}"""), 1e-9)
        assertEquals(3.0, h("""{"building":"shed"}"""), 1e-9); assertEquals(5.5, h("""{"building":"house"}"""), 1e-9); assertEquals(12.0, h("""{"building":"apartments"}"""), 1e-9)
        assertEquals(150.0, h("""{"height":"900"}"""), 1e-9); assertEquals(2.0, h("""{"height":"0.2"}"""), 1e-9)
        assertEquals(5.5, h("""{"height":"tall","building":"yes"}"""), 1e-9)   // junk falls through to the guess
    }

    @Test fun `an overpass answer becomes buildings in metres and damaged ones are skipped`() {
        val json = """{"elements":[
          {"type":"way","tags":{"building":"house","building:levels":"2"},"geometry":[{"lat":40.0,"lon":-74.0},{"lat":40.0,"lon":-73.9998},{"lat":40.0001,"lon":-73.9998},{"lat":40.0001,"lon":-74.0},{"lat":40.0,"lon":-74.0}]},
          {"type":"way","tags":{"building":"yes"},"geometry":[{"lat":40.0,"lon":-74.0},{"lat":40.0,"lon":-73.9998}]},
          {"type":"node","id":5},{"type":"way","tags":{"building":"yes"}}]}"""
        val b = Buildings.parse(json, 40.0, -74.0)
        assertEquals(1, b.size); assertEquals(4, b[0].ring.size); assertEquals(6.8, b[0].height, 1e-9)
        assertEquals(17.1, b[0].maxE, 0.3); assertEquals(11.1, b[0].maxN, 0.3)
        assertEquals(emptyList<Building>(), Buildings.parse("not json", 40.0, -74.0))
    }

    @Test fun `inside and outside`() {
        val b = house(); assertTrue(Buildings.contains(b, 6.0, 4.0)); assertFalse(Buildings.contains(b, 13.0, 4.0)); assertFalse(Buildings.contains(b, 6.0, -0.1))
        val (p, d) = Buildings.nearestEdge(b, 11.0, 4.0); assertEquals(12.0, p[0], 1e-9); assertEquals(4.0, p[1], 1e-9); assertEquals(1.0, d, 1e-9)
    }

    @Test fun `a few points smeared into the house are moved just outside, the rest untouched`() {
        // A walk along the back of the house (y = -3..-1) whose first four points are smeared inside it.
        val e = DoubleArray(30) { 2.0 + it * 0.9 }; val n = DoubleArray(30) { if (it < 4) 1.5 else -2.0 }
        val (oe, on) = Buildings.tidy(e, n, listOf(house()))
        for (i in 0 until 4) { assertFalse("point $i still inside", Buildings.contains(house(), oe[i], on[i])); assertTrue(on[i] < 0.0) }
        for (i in 4 until 30) { assertEquals(e[i], oe[i], 0.0); assertEquals(n[i], on[i], 0.0) }
        assertEquals(e[0], oe[0], 0.0)   // pushed through the nearest wall (south), not sideways
    }

    @Test fun `someone really indoors is left alone`() {
        val e = DoubleArray(40) { 2.0 + (it % 8) }; val n = DoubleArray(40) { 3.0 + (it % 3) }
        val (oe, on) = Buildings.tidy(e, n, listOf(house()))
        assertSame(e, oe); assertSame(n, on)
    }

    @Test fun `nothing near a building changes nothing`() {
        val e = DoubleArray(10) { 50.0 + it }; val n = DoubleArray(10) { 50.0 }
        assertSame(e, Buildings.tidy(e, n, listOf(house())).first); assertSame(e, Buildings.tidy(e, n, emptyList()).first)
    }

    @Test fun `the same yard always asks for the same box, and a huge route asks for none`() {
        val a = Buildings.box(40.00012, -74.00031, -5.0, 30.0, -4.0, 22.0)!!; val b = Buildings.box(40.00019, -74.00022, -3.0, 28.0, -6.0, 20.0)!!
        assertEquals(a.toList(), b.toList()); assertTrue(a[0] < 40.0 && a[2] > 40.0 && a[1] < -74.0 && a[3] > -74.0)
        assertNull(Buildings.box(40.0, -74.0, -5_000.0, 5_000.0, 0.0, 10.0)); assertNotNull(Buildings.box(40.0, -74.0, 0.0, 500.0, 0.0, 500.0))
        assertTrue(Buildings.query(a[0], a[1], a[2], a[3]).startsWith("[out:json]") && "way[\"building\"]" in Buildings.query(a[0], a[1], a[2], a[3]))
    }
}
