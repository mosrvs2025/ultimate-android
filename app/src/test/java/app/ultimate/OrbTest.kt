package app.ultimate

import app.ultimate.orb.OrbMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbTest {
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
