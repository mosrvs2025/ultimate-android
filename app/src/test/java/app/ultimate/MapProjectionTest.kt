package app.ultimate

import app.ultimate.fusion.MapCamera
import app.ultimate.fusion.WebMercator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapProjectionTest {
    @Test fun tilesRoundTrip() {
        // London, a well-known tile: zoom 15 contains (51.5007, -0.1246) in x 16372, y 10897.
        assertEquals(16372, WebMercator.tileX(-0.1246, 15).toInt()); assertEquals(10897, WebMercator.tileY(51.5007, 15).toInt())
        assertEquals(51.5007, WebMercator.latOf(WebMercator.tileY(51.5007, 15), 15), 1e-9)
        assertEquals(-0.1246, WebMercator.lonOf(WebMercator.tileX(-0.1246, 15), 15), 1e-9)
    }

    @Test fun localMetresRoundTrip() {
        val l = WebMercator.toLocal(40.7130, -74.0050, 40.7128, -74.0060)
        assertEquals(84.4, l[0], 0.5); assertEquals(22.2, l[1], 0.2)
        val g = WebMercator.toGeo(l[0], l[1], 40.7128, -74.0060)
        assertEquals(40.7130, g[0], 1e-9); assertEquals(-74.0050, g[1], 1e-9)
    }

    @Test fun zoomMatchesScreenDetail() {
        // About 1 screen pixel per metre at the equator is zoom ~17 (tile pixels ~1.19 m); 0.01 px/m is far out.
        assertEquals(17, WebMercator.zoomFor(1.0, 0.0)); assertTrue(WebMercator.zoomFor(0.01, 0.0) <= 10)
        assertEquals(19, WebMercator.zoomFor(1_000.0, 0.0)); assertEquals(3, WebMercator.zoomFor(1e-9, 0.0))
    }

    @Test fun tilesCoverTheBoxAndAreCapped() {
        val t = WebMercator.tilesFor(-300.0, -300.0, 300.0, 300.0, 51.5, -0.12, 16)
        assertTrue(t.size in 4..16); assertTrue(t.all { it.z == 16 })
        val cs = WebMercator.corners(t.first(), 51.5, -0.12)
        assertTrue(cs[0][1] > cs[2][1] && cs[1][0] > cs[0][0]) // north above south, east right of west
        assertEquals(10, WebMercator.tilesFor(-1e5, -1e5, 1e5, 1e5, 51.5, -0.12, 16, cap = 10).size)
    }

    @Test fun flatCameraIsAPlainMap() {
        val c = MapCamera(0.0, 0.0, 2.0)
        val p = c.project(10.0, 5.0, 0.0, 100f, 100f, 1000.0)!!
        assertEquals(120f, p[0], 1e-4f); assertEquals(90f, p[1], 1e-4f) // east right, north up
        val back = c.ground(p[0], p[1], 100f, 100f, 1000.0)!!
        assertEquals(10.0, back[0], 1e-6); assertEquals(5.0, back[1], 1e-6)
    }

    @Test fun bearingTurnsTheMap() {
        val c = MapCamera(0.0, 0.0, 1.0, bearing = Math.PI / 2) // east at the top
        val p = c.project(10.0, 0.0, 0.0, 0f, 0f, 1e6)!!
        assertEquals(0f, p[0], 1e-3f); assertEquals(-10f, p[1], 1e-3f)
    }

    @Test fun tiltRecedesAndHeightRises() {
        val c = MapCamera(0.0, 0.0, 1.0, tilt = 0.9)
        val near = c.project(0.0, -50.0, 0.0, 0f, 0f, 800.0)!!; val far = c.project(0.0, 50.0, 0.0, 0f, 0f, 800.0)!!
        assertTrue("far things are smaller", far[2] < near[2])
        val hill = c.project(0.0, 0.0, 20.0, 0f, 0f, 800.0)!!; val flat = c.project(0.0, 0.0, 0.0, 0f, 0f, 800.0)!!
        assertTrue("height lifts the point", hill[1] < flat[1])
        val g = c.ground(far[0], far[1], 0f, 0f, 800.0)!!
        assertEquals(0.0, g[0], 1e-6); assertEquals(50.0, g[1], 1e-6)
        assertNull(c.ground(0f, -5000f, 0f, 0f, 800.0)) // above the horizon
    }
}
