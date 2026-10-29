package app.ultimate.send

import org.junit.Assert.*
import org.junit.Test

class SendRangeTest {
    @Test fun browserSeekAndSuffix() {
        assertEquals(200L..999L, SendRange.parse("bytes=200-", 1000))
        assertEquals(900L..999L, SendRange.parse("bytes=-100", 1000))
        assertEquals(0L..999L, SendRange.parse("bytes=0-9999", 1000))
        assertEquals(0L..999L, SendRange.parse(null, 1000))
    }
    @Test fun rejectInvalidRanges() {
        listOf("bytes=1000-", "bytes=20-10", "bytes=-0", "bytes=0-1,5-6", "cats=0-5", "bytes=wat-").forEach {
            assertNull(it, SendRange.parse(it, 1000))
        }
        assertNull(SendRange.parse("bytes=0-", 0))
    }
}
