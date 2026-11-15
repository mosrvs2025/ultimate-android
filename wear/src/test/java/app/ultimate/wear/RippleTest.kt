package app.ultimate.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RippleTest {
    @Test fun stillWaterStaysStill() {
        val r = Ripple(32); repeat(50) { r.step() }
        assertEquals(0f, r.energy(), 0f)
    }

    @Test fun aDropSpreadsOutAsRings() {
        val r = Ripple(64); r.drop(0.5f, 0.5f)
        assertTrue(abs(r.height(32, 32)) > 0.1f && r.height(50, 32) == 0f)
        repeat(18) { r.step() }
        // The edge of the ring has travelled out to where nothing was disturbed at first.
        assertTrue((44..56).any { abs(r.height(it, 32)) > 0.005f })
    }

    @Test fun ringsCalmDown() {
        val r = Ripple(48); r.drop(0.4f, 0.6f)
        repeat(5) { r.step() }; val early = r.energy()
        repeat(600) { r.step() }
        assertTrue(r.energy() < early * 0.01f)
    }

    @Test fun theRimNeverMoves() {
        val r = Ripple(40); r.drop(0.02f, 0.02f, radius = 0.2f); repeat(40) { r.step() }
        assertEquals(0f, r.height(0, 0), 0f); assertEquals(0f, r.height(39, 0), 0f)
    }

    @Test fun shadingIsOpaqueAndLitByTheWaves() {
        val r = Ripple(32); val px = IntArray(32 * 32)
        r.shade(px); val flat = px[16 * 32 + 16]
        assertTrue(px.all { it ushr 24 == 0xFF })
        r.drop(0.5f, 0.5f); repeat(4) { r.step() }; r.shade(px)
        assertTrue(px.any { it != flat && it != 0xFF000000.toInt() })
    }
}
