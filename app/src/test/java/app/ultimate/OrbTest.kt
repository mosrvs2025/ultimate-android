package app.ultimate

import app.ultimate.orb.OrbMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbTest {
    @Test fun fanIsWideEnoughThatActionsNeverTouch() {
        val gap = 84f
        for (n in 2..7) {
            val r = OrbMath.fanRadius(n, gap, 118f)
            val pts = OrbMath.bloom(540f, 1200f, n, r, 1080f, 2400f, 44f)
            pts.zipWithNext().forEach { (a, b) -> assertTrue("$n actions: ${kotlin.math.hypot(a.first - b.first, a.second - b.second)}", kotlin.math.hypot(a.first - b.first, a.second - b.second) >= gap - 0.5f) }
        }
        assertEquals(118f, OrbMath.fanRadius(1, gap, 118f), 0f)
        assertTrue(OrbMath.fanRadius(7, gap, 118f) > 118f) // seven at the old 118 would touch
    }

    @Test fun petalsFanOutFromTheOrbAndLandOnTheirSpots() {
        val spots = listOf(700f to 1000f, 740f to 1200f, 700f to 1400f)
        val toward = OrbMath.middle(spots, 540f, 1200f)
        assertEquals(0f, toward, 1e-3f) // the fan points to the right
        spots.forEach { (px, py) ->
            val (sx, sy) = OrbMath.fanAt(0f, 540f, 1200f, px, py, toward); assertEquals(540f, sx, 1e-3f); assertEquals(1200f, sy, 1e-3f)
            val (ex, ey) = OrbMath.fanAt(1f, 540f, 1200f, px, py, toward); assertEquals(px, ex, 0.5f); assertEquals(py, ey, 0.5f)
        }
        // Halfway, the outer petals have not swung all the way round yet: a fan, not straight lines.
        val (hx, hy) = OrbMath.fanAt(0.25f, 540f, 1200f, 700f, 1000f, toward)
        val straight = kotlin.math.atan2(1000f - 1200f, 700f - 540f)
        assertTrue(kotlin.math.abs(kotlin.math.atan2(hy - 1200f, hx - 540f)) < kotlin.math.abs(straight))
    }

    @Test fun snapsToTheNearerEdgeInsideTheScreen() {
        assertEquals(10 to 500, OrbMath.snap(200, 500, 100, 1080, 2400, 10, 60, 120))
        assertEquals(970 to 500, OrbMath.snap(700, 500, 100, 1080, 2400, 10, 60, 120))
        assertEquals(10 to 70, OrbMath.snap(100, -300, 100, 1080, 2400, 10, 60, 120))
        assertEquals(970 to 2170, OrbMath.snap(1000, 3000, 100, 1080, 2400, 10, 60, 120))
    }

    @Test fun bloomOpensAwayFromTheEdgeAndStaysOnScreen() {
        val right = OrbMath.bloom(1040f, 1200f, 7, 300f, 1080f, 2400f, 100f)
        assertEquals(7, right.size)
        right.forEach { (x, y) -> assertTrue(x < 1040f && x in 100f..980f && y in 100f..2300f) }
        val left = OrbMath.bloom(40f, 1200f, 5, 300f, 1080f, 2400f, 100f)
        left.forEach { (x, _) -> assertTrue(x > 40f) }
        // In a top corner the petals fall downward.
        OrbMath.bloom(1040f, 120f, 6, 300f, 1080f, 2400f, 100f).forEach { (_, y) -> assertTrue(y >= 100f) }
        assertTrue(OrbMath.bloom(0f, 0f, 0, 1f, 1f, 1f, 0f).isEmpty())
    }

    @Test fun petalsAreSpreadApart() {
        val pts = OrbMath.bloom(1040f, 1200f, 7, 300f, 1080f, 2400f, 100f)
        pts.zipWithNext().forEach { (a, b) -> assertTrue(kotlin.math.hypot(a.first - b.first, a.second - b.second) > 60f) }
    }

    @Test fun targetReach() {
        assertTrue(OrbMath.overTarget(500f, 2000f, 540f, 2050f, 90f))
        assertFalse(OrbMath.overTarget(500f, 1500f, 540f, 2050f, 90f))
    }

    @Test fun mostUsedFirstStableOtherwise() {
        val uses = mapOf("c" to 5, "a" to 1)
        assertEquals(listOf("c", "a", "b", "d"), OrbMath.byUse(listOf("a", "b", "c", "d"), { it }) { uses[it] ?: 0 })
    }
}
