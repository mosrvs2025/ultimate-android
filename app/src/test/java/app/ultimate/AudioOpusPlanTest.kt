package app.ultimate

import app.ultimate.audio.model.AudioPlanner
import app.ultimate.audio.model.AudioSettings
import app.ultimate.audio.model.AudioSource
import app.ultimate.audio.model.AudioTemplates
import app.ultimate.audio.model.OutFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opus as the planner sees it: which rates and steps exist, how big it is, and what happens on a phone that can't make it. */
class AudioOpusPlanTest {
    private val hour = 3_600_000L
    private val aac = AudioSource(durationMs = 8 * hour, mime = "audio/mp4a-latm", sampleRate = 48_000, channels = 2, bitrate = 128_000)

    @Test fun opusOnlyTakesItsOwnSampleRates() {
        assertEquals(48_000, AudioPlanner.encoderRate(48_000, OutFormat.Opus))
        assertEquals(16_000, AudioPlanner.encoderRate(16_000, OutFormat.Opus))
        assertEquals("44.1 kHz is not an Opus rate", 48_000, AudioPlanner.encoderRate(44_100, OutFormat.Opus))
        assertEquals("22.05 kHz neither", 48_000, AudioPlanner.encoderRate(22_050, OutFormat.Opus))
        assertEquals("MP3 and AAC keep 44.1", 44_100, AudioPlanner.encoderRate(44_100, OutFormat.Mp3))
        assertEquals("WAV keeps whatever it is", 11_111, AudioPlanner.encoderRate(11_111, OutFormat.Wav))
    }

    @Test fun aNumberFromAnotherFormatLandsOnARealOpusStep() {
        assertEquals(16, AudioPlanner.opusKbps(8)); assertEquals(24, AudioPlanner.opusKbps(24)); assertEquals(96, AudioPlanner.opusKbps(100))
        assertEquals("the default MP3 192 is held to the top step", 128, AudioPlanner.opusKbps(192))
        assertTrue(AudioPlanner.OPUS_BITRATES.all { AudioPlanner.opusLabel(it).isNotEmpty() })
    }

    @Test fun anEightHourOpusFileIsAFifthOfTheFixedMp3() {
        val mp3 = AudioPlanner.estimateBytes(aac, AudioSettings(format = OutFormat.Mp3, kbps = 128))
        val opus = AudioPlanner.estimateBytes(aac, AudioSettings(format = OutFormat.Opus, kbps = 24, mono = true))
        assertEquals(24_000 / 8L * 8 * 3600, opus)
        assertTrue("24 kbps is under a fifth of 128, was ${opus.toDouble() / mp3}", opus < mp3 / 5)
    }

    @Test fun aPhoneWithNoOpusEncoderGetsASmallMp3WithTheRestKept() {
        val asked = AudioSettings(format = OutFormat.Opus, kbps = 24, mono = true, splitMin = 60, speed = 1.5f)
        val got = AudioPlanner.withoutOpus(asked)
        assertEquals(OutFormat.Mp3, got.format); assertEquals(6, got.vbr)
        assertTrue(got.mono); assertEquals(60, got.splitMin); assertEquals(1.5f, got.speed, 0f)
        val mp3 = AudioSettings(format = OutFormat.Mp3, vbr = 8)
        assertEquals("other formats are left alone", mp3, AudioPlanner.withoutOpus(mp3))
    }

    @Test fun theOpusTemplateAsksForSmallMonoOpusAndThePreviewUsesTheRealCodec() {
        val t = AudioTemplates.all.first { it.title.contains("Opus") }.apply(AudioSettings())
        assertEquals(OutFormat.Opus, t.format); assertTrue(t.mono); assertTrue(t.kbps <= 32)
        assertEquals(OutFormat.Opus, AudioPlanner.previewFormat(aac, t))
        assertEquals("audio/ogg", OutFormat.Opus.mime); assertEquals("opus", OutFormat.Opus.ext)
        assertEquals("the formats still have distinct file types", OutFormat.entries.size, OutFormat.entries.map { it.ext }.toSet().size)
    }
}
