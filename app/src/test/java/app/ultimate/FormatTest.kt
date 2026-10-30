package app.ultimate

import app.ultimate.core.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun sizesKeepOnlyTheDecimalsThatMatter() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("999 B", formatBytes(999))
        assertEquals("12 KB", formatBytes(12_345))
        assertEquals("4.5 MB", formatBytes(4_500_000))
        assertEquals("250 MB", formatBytes(250_000_000))
        assertEquals("1.50 GB", formatBytes(1_500_000_000))
        assertEquals("9.60 GB", formatBytes(9_600_000_000))
        assertEquals("98.0 GB", formatBytes(98_000_000_000))
        assertEquals("158 GB", formatBytes(158_000_000_000))
        assertEquals("0 B", formatBytes(-5))
    }
}
