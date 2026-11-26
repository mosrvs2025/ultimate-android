package app.ultimate

import app.ultimate.audio.model.AudioPlanner
import app.ultimate.audio.model.AudioSettings
import app.ultimate.audio.model.AudioSource
import app.ultimate.audio.model.AudioTemplates
import app.ultimate.audio.model.OutFormat
import app.ultimate.audio.model.SplitPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The smaller-files and splitting choices of Video to Audio, as the planner sees them. */
class AudioSmallerTest {
    private val hour = 3_600_000L
    private val aac = AudioSource(durationMs = 8 * hour, mime = "audio/mp4a-latm", sampleRate = 48_000, channels = 2, bitrate = 128_000)

    @Test fun smartSizesAreOrderedBySizeAndMonoIsAboutHalf() {
        val s = AudioPlanner.SMART
        assertEquals(listOf(8, 6, 4, 2), s.map { it.vbr })
        val stereo = s.map { AudioPlanner.vbrKbps(it.vbr, mono = false) }
        assertEquals("each step up costs more", stereo.sorted(), stereo)
        s.forEach { lv -> assertTrue(AudioPlanner.vbrKbps(lv.vbr, true) in (AudioPlanner.vbrKbps(lv.vbr, false) * 0.5).toInt()..(AudioPlanner.vbrKbps(lv.vbr, false) * 0.6).toInt()) }
        assertEquals("a level that isn't listed still gets a sensible figure", 130, AudioPlanner.vbrKbps(5, false))
    }

    @Test fun aVariableRateEightHourFileIsFarSmallerThanTheFixedRateOne() {
        val fixed = AudioPlanner.estimateBytes(aac, AudioSettings(format = OutFormat.Mp3, kbps = 128))
        val tiny = AudioPlanner.estimateBytes(aac, AudioSettings(format = OutFormat.Mp3, vbr = 8, mono = true))
        assertEquals(128_000 / 8L * 8 * 3600, fixed)
        // Measured on speech: level 8 mono averaged 44 kbps against 128, about 66% smaller; the estimate says 46.
        assertTrue("tiny mono is well under half of 128 kbps, was ${tiny.toDouble() / fixed}", tiny < fixed * 0.4)
        assertTrue("and not absurdly small", tiny > fixed * 0.25)
        assertTrue("fixed ignores mono: same size", AudioPlanner.estimateBytes(aac, AudioSettings(format = OutFormat.Mp3, kbps = 128, mono = true)) == fixed)
        assertEquals("the variable level only applies to MP3", AudioPlanner.estimateBytes(aac, AudioSettings(format = OutFormat.M4a, kbps = 128, mono = true)),
            AudioPlanner.estimateBytes(aac, AudioSettings(format = OutFormat.M4a, kbps = 128, mono = true, vbr = 8)))
    }

    @Test fun anEightHourFileInHourPartsIsEightParts() {
        val s = AudioSettings(splitMin = 60)
        val p = AudioPlanner.parts(aac, s)
        assertEquals(8, p.size); assertTrue(p.all { it.ms == hour })
        assertEquals("no split asked for is one part, the whole file", listOf(0L to 8 * hour), AudioPlanner.parts(aac, AudioSettings()).map { it.startMs to it.endMs })
    }

    @Test fun trimmingFirstSplitsOnlyTheKeptSection() {
        val p = AudioPlanner.parts(aac, AudioSettings(splitMin = 30, trimStartMs = hour, trimEndMs = 3 * hour))
        assertEquals(4, p.size)
        assertEquals(hour, p.first().startMs); assertEquals(3 * hour, p.last().endMs)
        assertEquals("an end beyond the file is held to the file", 2, AudioPlanner.parts(aac.copy(durationMs = 90 * 60_000L), AudioSettings(splitMin = 30, trimStartMs = 30 * 60_000L, trimEndMs = 99 * hour)).size)
    }

    @Test fun thePreviewIsMadeWithTheChosenCodecUnlessThereIsNothingToHear() {
        assertEquals(OutFormat.Mp3, AudioPlanner.previewFormat(aac, AudioSettings(format = OutFormat.Mp3, vbr = 8)))
        assertEquals(OutFormat.M4a, AudioPlanner.previewFormat(aac, AudioSettings(format = OutFormat.M4a, mono = true)))
        assertEquals("AAC copied untouched: nothing is compressed again", OutFormat.Wav, AudioPlanner.previewFormat(aac, AudioSettings(format = OutFormat.M4a)))
        assertEquals(OutFormat.Wav, AudioPlanner.previewFormat(aac, AudioSettings(format = OutFormat.Wav)))
    }

    @Test fun theTemplatesForLongRecordingsAreMonoAndSmall() {
        val tiny = AudioTemplates.all.first { it.title == "Long recording, tiny" }.apply(AudioSettings())
        assertTrue(tiny.mono); assertEquals(8, tiny.vbr); assertEquals(OutFormat.Mp3, tiny.format); assertEquals(0, tiny.splitMin)
        val parts = AudioTemplates.all.first { it.title == "Long recording in 1-hour parts" }.apply(AudioSettings())
        assertEquals(60, parts.splitMin); assertTrue(parts.splitQuiet); assertTrue(parts.mono); assertTrue(parts.vbr >= 0)
        assertTrue("every template still names a real format", AudioTemplates.all.all { it.apply(AudioSettings()).format in OutFormat.entries })
        assertEquals("the lengths offered are real part lengths", SplitPlan.CHOICES.sorted(), SplitPlan.CHOICES)
    }
}
