package app.ultimate

import app.ultimate.fusion.WatchBout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WatchBoutTest {
    private val now = 100_000_000L

    @Test fun `the walk's own start is adopted while the watch says it is moving`() {
        assertEquals(now - 720_000, WatchBout.adoptedStart(now, "walking", now - 720_000, now - 20_000))
        assertEquals(now - 60_000, WatchBout.adoptedStart(now, "running", now - 60_000, now - 100_000))
    }

    @Test fun `nothing is adopted when the watch isn't sure, isn't moving, or is out of date`() {
        assertNull(WatchBout.adoptedStart(now, "still", now - 720_000, now - 20_000))
        assertNull(WatchBout.adoptedStart(now, null, now - 720_000, now - 20_000))
        assertNull(WatchBout.adoptedStart(now, "walking", 0L, now - 20_000))               // an older watch that sent no start
        assertNull(WatchBout.adoptedStart(now, "walking", now + 5_000, now))                  // a start in the future is a clock error
        assertNull(WatchBout.adoptedStart(now, "walking", now - 720_000, now - 600_000))    // it hasn't been heard from for ten minutes
        assertNull(WatchBout.adoptedStart(now, "walking", now - WatchBout.MAX_AGE_MS - 1, now))
        assertNotNull(WatchBout.adoptedStart(now, "walking", now - WatchBout.MAX_AGE_MS + 1, now))
    }

    @Test fun `the note says what came from the watch`() {
        assertEquals("The first 12:00 comes from your watch (about 1,350 steps), before this phone started recording. It is counted in the time, but not drawn on the map.", WatchBout.note(720.0, 1350))
        assertEquals("The first 1:02:03 comes from your watch, before this phone started recording. It is counted in the time, but not drawn on the map.", WatchBout.note(3723.0, null))
        assertNull(WatchBout.note(5.0, 10)); assertNull(WatchBout.note(0.0, null))
    }
}
