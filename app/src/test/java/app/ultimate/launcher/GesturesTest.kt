package app.ultimate.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Trying a gesture on the settings pad: when a drag counts as a swipe. */
class GesturesTest {
    @Test fun aDragCountsOnlyWhenItWentFarEnough() {
        assertEquals(Gestures.Swipe.Up, Gestures.swipe(-200f, 48f))
        assertEquals(Gestures.Swipe.Down, Gestures.swipe(120f, 48f))
        assertEquals("exactly the threshold counts", Gestures.Swipe.Up, Gestures.swipe(-48f, 48f))
        assertNull("a wobble is not a swipe", Gestures.swipe(20f, 48f))
        assertNull(Gestures.swipe(-47.9f, 48f))
        assertNull(Gestures.swipe(0f, 48f))
    }
}
