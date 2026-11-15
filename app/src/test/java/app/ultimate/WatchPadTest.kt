package app.ultimate

import app.ultimate.air.WatchPad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPadTest {
    @Test fun messagesRoundTrip() {
        assertEquals(WatchPad.Msg.Move(0.0125f, -0.5f, 20), WatchPad.parse(WatchPad.move(0.0125f, -0.5f, 20)))
        assertEquals(WatchPad.Msg.Tap, WatchPad.parse("pad:tap"))
        assertEquals(WatchPad.Msg.Scroll(-3f), WatchPad.parse("pad:scroll:-3"))
        assertEquals(WatchPad.Msg.Home, WatchPad.parse("pad:home"))
    }

    @Test fun nonsenseIsIgnoredOrClamped() {
        assertNull(WatchPad.parse("gesture:flick_left"))
        assertNull(WatchPad.parse("pad:m:NaN:1:5"))
        assertNull(WatchPad.parse("pad:fly"))
        assertEquals(WatchPad.Msg.Move(2f, -2f, 1_000), WatchPad.parse("pad:m:900:-900:99999"))
        assertEquals(WatchPad.Msg.Scroll(20f), WatchPad.parse("pad:scroll:1e9"))
    }

    @Test fun slowIsPreciseAndFastReachesFar() {
        val slow = WatchPad.Cursor(1080f, 2340f); repeat(10) { slow.move(0.01f, 0f, 16) } // 0.1 face at ~0.6 faces/s
        val fast = WatchPad.Cursor(1080f, 2340f); fast.move(0.1f, 0f, 16) // the same distance in one quick flick
        val slowMoved = slow.x - 540f; val fastMoved = fast.x - 540f
        assertTrue("slow $slowMoved", slowMoved in 150f..200f)
        assertTrue("fast $fastMoved vs slow $slowMoved", fastMoved > 2.5f * slowMoved)
    }

    @Test fun oneSwipeAcrossTheWatchReachesTheEdge() {
        val c = WatchPad.Cursor(1080f, 2340f); c.move(0.8f, 0.8f, 160)
        assertEquals(1079f, c.x, 0.01f); assertTrue(c.y > 1800f)
    }

    @Test fun neverLeavesTheScreen() {
        val c = WatchPad.Cursor(1080f, 2340f); repeat(50) { c.move(-1f, -1f, 10) }
        assertEquals(0f, c.x, 0f); assertEquals(0f, c.y, 0f)
        c.resize(500f, 500f); repeat(50) { c.move(1f, 1f, 10) }
        assertEquals(499f, c.x, 0f)
    }

    @Test fun gainRisesSmoothly() {
        assertEquals(1.5f, WatchPad.gain(0f), 1e-4f); assertEquals(5f, WatchPad.gain(10f), 1e-4f)
        var last = 0f; for (s in 0..40) { val g = WatchPad.gain(s / 10f); assertTrue(g >= last); last = g }
    }

    @Test fun scrollingDownDragsUpAndStaysOnScreen() {
        val c = WatchPad.Cursor(1080f, 2340f)
        val s = WatchPad.scroll(c, 2f)
        assertTrue(s.y1 < s.y0); assertEquals(s.x0, s.x1, 0f)
        val far = WatchPad.scroll(c, 20f)
        assertTrue(far.y1 >= 2340f * 0.05f - 0.01f)
    }
}
