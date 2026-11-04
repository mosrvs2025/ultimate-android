package app.ultimate

import app.ultimate.ruler.RulerMath
import org.junit.Assert.assertEquals
import org.junit.Test

class RulerTest {
    @Test fun pixelsPerMillimetreFollowTheScreenAndTheCardCheck() {
        assertEquals(10f, RulerMath.pxPerMm(254f), 1e-4f)
        assertEquals(10.5f, RulerMath.pxPerMm(254f, 1.05f), 1e-4f)
        // A bank card at 400 dpi is 1348 px wide.
        assertEquals(1348.0, RulerMath.CARD_W_MM * RulerMath.pxPerMm(400f), 1.0)
    }

    @Test fun ticksMarkCentimetresHalvesAndInchFractions() {
        assertEquals(listOf(2, 0, 0, 0, 0, 1, 0, 0, 0, 0, 2), (0..10).map(RulerMath::tick))
        assertEquals(listOf(3, 1, 2, 1, 3), listOf(0, 4, 8, 12, 16).map(RulerMath::inchTick))
    }
}
