package app.ultimate

import app.ultimate.core.LiquidTouch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidTouchTest {
    @Test fun theRingSpreadsAndFades() {
        val a = LiquidTouch.ring(0.1f, 400f, 200f, 2f)!!; val b = LiquidTouch.ring(0.5f, 400f, 200f, 2f)!!; val c = LiquidTouch.ring(0.9f, 400f, 200f, 2f)!!
        assertTrue(a.radius < b.radius && b.radius < c.radius)
        assertTrue(a.alpha > b.alpha && b.alpha > c.alpha)
        assertTrue(a.strokePx > c.strokePx)
    }

    @Test fun neverLoudAndNeverLeftBehind() {
        for (i in 1..99) assertTrue(LiquidTouch.ring(i / 100f, 300f, 300f, 3f)!!.alpha <= 0.32f)
        assertNull(LiquidTouch.ring(0f, 300f, 300f, 3f)); assertNull(LiquidTouch.ring(1f, 300f, 300f, 3f))
    }

    @Test fun bigCardsDoNotGetGiantRings() {
        // A full-width card: the ring is capped (240 dp reach), not the width of the screen.
        val r = LiquidTouch.ring(0.99f, 1_400f, 900f, 3f)!!
        assertTrue(r.radius <= 240f * 3f * 0.62f + 1f)
    }

    @Test fun theSecondRingTrailsTheFirst() {
        assertNull(LiquidTouch.ring(0.1f, 300f, 300f, 2f, delay = 0.16f))
        assertNotNull(LiquidTouch.ring(0.3f, 300f, 300f, 2f, delay = 0.16f))
    }

    @Test fun rapidTapsDoNotBuzz() {
        assertFalse(LiquidTouch.Pace.allow(1_050L, 1_000L)); assertTrue(LiquidTouch.Pace.allow(1_080L, 1_000L))
        assertEquals(70L, LiquidTouch.Pace.MIN_GAP_MS)
    }
}
