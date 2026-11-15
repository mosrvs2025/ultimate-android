package app.ultimate

import app.ultimate.hid.HidReports
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HidReportsTest {
    @Test fun descriptorIsBalanced() {
        // Every collection that opens (0xA1) closes (0xC0), and every report id appears once.
        val d = HidReports.DESCRIPTOR.map { it.toInt() and 0xFF }
        var i = 0; var depth = 0; val ids = ArrayList<Int>()
        while (i < d.size) {
            val prefix = d[i]; val size = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            when (prefix and 0xFC) { 0xA0 -> depth++; 0xC0 -> depth--; 0x84 -> ids += d[i + 1] }
            assertTrue(depth >= 0); i += 1 + size
        }
        assertEquals(0, depth); assertEquals(d.size, i); assertEquals(listOf(1, 2, 3), ids)
    }

    @Test fun lettersDigitsAndSymbols() {
        assertEquals(HidReports.Stroke(0, 0x04), HidReports.stroke('a'))
        assertEquals(HidReports.Stroke(HidReports.SHIFT, 0x1D), HidReports.stroke('Z'))
        assertEquals(HidReports.Stroke(0, 0x27), HidReports.stroke('0'))
        assertEquals(HidReports.Stroke(HidReports.SHIFT, 0x1F), HidReports.stroke('@'))
        assertEquals(HidReports.Stroke(HidReports.SHIFT, 0x38), HidReports.stroke('?'))
        assertEquals(HidReports.Stroke(0, 0x28), HidReports.stroke('\n'))
        assertNull(HidReports.stroke('é'))
    }

    @Test fun typingPressesAndReleasesEachKey() {
        val t = HidReports.type("Hi!\r\nok é")
        assertEquals(1, t.skipped)
        assertEquals(2 * 7, t.reports.size) // H i ! enter o k space
        assertArrayEquals(byteArrayOf(HidReports.SHIFT.toByte(), 0, 0x0B, 0, 0, 0, 0, 0), t.reports[0])
        assertArrayEquals(HidReports.KEYS_UP, t.reports[1])
        assertEquals(8, t.reports.all { it.size == 8 }.let { 8 })
    }

    @Test fun smallMovesAccumulate() {
        val m = HidReports.Mouse()
        val r = (1..10).flatMap { m.move(0.3f, -0.3f) }
        assertEquals(3, r.sumOf { it[1].toInt() }); assertEquals(-3, r.sumOf { it[2].toInt() })
    }

    @Test fun bigMovesSplitIntoSeveralReports() {
        val m = HidReports.Mouse()
        val r = m.move(400f, -10f)
        assertEquals(4, r.size)
        assertEquals(400, r.sumOf { it[1].toInt() }); assertEquals(-10, r.sumOf { it[2].toInt() })
        assertTrue(r.all { it[1] in -127..127 })
    }

    @Test fun clicksAndScroll() {
        val m = HidReports.Mouse()
        val c = m.click(HidReports.RIGHT)
        assertEquals(HidReports.RIGHT.toByte(), c[0][0]); assertEquals(0.toByte(), c[1][0])
        val s = m.move(0f, 0f, wheel = -3)
        assertEquals(1, s.size); assertEquals((-3).toByte(), s[0][3])
    }

    @Test fun mediaKeysPressThenRelease() {
        val r = HidReports.media(HidReports.Media.PlayPause)
        assertArrayEquals(byteArrayOf(4), r[0]); assertArrayEquals(byteArrayOf(0), r[1])
    }
}
