package app.ultimate.pluck

import app.ultimate.pluck.model.ArscStrings
import app.ultimate.pluck.model.FeatureGap
import app.ultimate.pluck.model.GapMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureGapTest {
    /** A minimal resources.arsc: table header, then a UTF-8 string pool. */
    private fun arsc(strings: List<String>): ByteArray {
        val data = java.io.ByteArrayOutputStream(); val offsets = ArrayList<Int>()
        strings.forEach { s -> offsets += data.size(); val b = s.toByteArray(); data.write(s.length); data.write(b.size); data.write(b); data.write(0) }
        while (data.size() % 4 != 0) data.write(0)
        val poolHeader = 28; val stringsStart = poolHeader + strings.size * 4
        val poolSize = stringsStart + data.size()
        val out = java.nio.ByteBuffer.allocate(12 + poolSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.putShort(2).putShort(12).putInt(12 + poolSize).putInt(1)
        out.putShort(1).putShort(poolHeader.toShort()).putInt(poolSize).putInt(strings.size).putInt(0).putInt(0x100).putInt(stringsStart).putInt(0)
        offsets.forEach { out.putInt(it) }
        out.put(data.toByteArray())
        return out.array()
    }

    @Test fun readsTheStringPool() {
        val s = listOf("A-weighting", "Export to CSV", "Ünïcode label")
        assertEquals(s, ArscStrings.read(arsc(s)))
        assertTrue(ArscStrings.read(ByteArray(8)).isEmpty())
    }

    @Test fun keepsFeaturePhrasesAndDropsNoise() {
        val got = FeatureGap.phrases(listOf("A-weighting", "Frequency spectrum", "Navigate up", "Get Google Play services", "res/layout/x.xml", "ok", "Calibrate", "Frequency spectrum"))
        assertEquals(listOf("A-weighting", "Frequency spectrum", "Calibrate"), got)
    }

    @Test fun ourToolsCoverWhatTheyDoAndTheBriefListsTheRest() {
        assertTrue(FeatureGap.covered("sound-meter", "Export history"))
        assertFalse(FeatureGap.covered("sound-meter", "Frequency spectrum"))
        val scan = FeatureGap.scan("p", "Meter", "1", "sound-meter", listOf("Frequency spectrum", "Export history", "Noise alarm"), FeatureGap.declared(listOf("android.permission.RECORD_AUDIO"), listOf("Live dB"), 0, emptyList()))
        assertEquals(listOf("Home-screen widget: Live dB", "Frequency spectrum", "Noise alarm"), scan.open.map { it.text })
        val brief = FeatureGap.brief(listOf(scan), mapOf("p" to listOf("dB(A)")), mapOf(FeatureGap.key("p", "Noise alarm") to GapMark.Need, FeatureGap.key("p", "Frequency spectrum") to GapMark.Skip), { "Sound meter" }, "today")
        assertTrue(brief, brief.contains("Needed:\n- Noise alarm"))
        assertTrue(brief.contains("Not needed: Frequency spectrum"))
        assertTrue(brief.contains("- Home-screen widget: Live dB"))
        assertTrue(brief.contains("Already known missing:\n- dB(A)"))
    }
}
