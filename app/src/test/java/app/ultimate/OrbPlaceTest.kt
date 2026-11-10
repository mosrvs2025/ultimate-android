package app.ultimate

import app.ultimate.orb.OrbMath
import app.ultimate.orb.OrbMath.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbPlaceTest {
    private val W = 1080; private val H = 2400; private val orb = 150; private val m = 18; private val top = 80; private val bottom = 120; private val r = 80
    private fun at(mode: Place, x: Int, y: Int) = OrbMath.place(mode, x, y, orb, W, H, m, top, bottom, r)

    @Test fun edgeModeStillSpringsToTheNearerSide() {
        assertEquals(m, at(Place.Edge, 400, 900).first); assertEquals(W - orb - m, at(Place.Edge, 700, 900).first)
    }

    @Test fun anywhereKeepsWhereYouLetGoAndOnlyStaysOnScreen() {
        assertEquals(400 to 900, at(Place.Free, 400, 900))
        assertEquals(m to (top + m), at(Place.Free, -300, -50)); assertEquals((W - orb - m) to (H - bottom - orb - m), at(Place.Free, 5000, 5000))
    }

    @Test fun softEdgesStickOnlyWhenLetGoCloseToASide() {
        assertEquals("close to the left", m, at(Place.Soft, 70, 900).first); assertEquals("close to the right", W - orb - m, at(Place.Soft, W - orb - 60, 900).first)
        assertEquals("well inside, it stays", 400, at(Place.Soft, 400, 900).first)
        assertEquals("height is never snapped", 1234, at(Place.Soft, 70, 1234).second)
    }
}
