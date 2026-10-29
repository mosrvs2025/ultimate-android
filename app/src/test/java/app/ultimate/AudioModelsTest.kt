package app.ultimate

import app.ultimate.audio.model.AudioPlanner
import app.ultimate.audio.model.AudioSettings
import app.ultimate.audio.model.AudioSource
import app.ultimate.audio.model.OutFormat
import app.ultimate.audio.model.PcmMixer
import app.ultimate.audio.model.PeakTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioModelsTest {
    private val aac = AudioSource(durationMs = 180_000, mime = "audio/mp4a-latm", sampleRate = 44100, channels = 2, bitrate = 128_000)
    private val opus = aac.copy(mime = "audio/opus", sampleRate = 48000)

    @Test fun aacIsCopiedOnlyWhenNothingAboutTheSoundChanges() {
        val m4a = AudioSettings(format = OutFormat.M4a)
        assertTrue(AudioPlanner.copies(aac, m4a))
        assertTrue("non-AAC is encoded to AAC, not swapped for MP3", !AudioPlanner.copies(opus, m4a))
        assertTrue(!AudioPlanner.copies(aac, m4a.copy(speed = 1.5f)))
        assertTrue(!AudioPlanner.copies(aac, m4a.copy(mono = true)))
        assertTrue(!AudioPlanner.copies(aac, m4a.copy(join = true)))
        assertTrue(!AudioPlanner.copies(aac, AudioSettings(format = OutFormat.Mp3)))
    }

    @Test fun m4aEncodeIsSizedByTheChosenBitrateCappedForAac() {
        assertEquals(128_000 / 8L * 180, AudioPlanner.estimateBytes(opus, AudioSettings(format = OutFormat.M4a, kbps = 128)))
        assertEquals(256_000 / 8L * 180, AudioPlanner.estimateBytes(opus, AudioSettings(format = OutFormat.M4a, kbps = 320)))
    }

    @Test fun speedChangesTheLengthAndSize() {
        assertEquals(90_000, AudioPlanner.outputMs(aac, AudioSettings(speed = 2f)))
        assertEquals(360_000, AudioPlanner.outputMs(aac, AudioSettings(speed = 0.5f)))
        assertEquals(192_000 / 8L * 90, AudioPlanner.estimateBytes(aac, AudioSettings(kbps = 192, speed = 2f)))
    }

    @Test fun joinedFilesAreStereoIfAnyInputIsAndNamedAfterTheFirst() {
        val mono = aac.copy(channels = 1)
        assertEquals(2, AudioPlanner.outputChannels(listOf(mono, aac), AudioSettings()))
        assertEquals(1, AudioPlanner.outputChannels(listOf(mono, mono), AudioSettings()))
        assertEquals(1, AudioPlanner.outputChannels(listOf(aac, aac), AudioSettings(mono = true)))
        assertEquals("intro + 2 more", AudioPlanner.joinedName("intro.mp3", 3))
    }

    @Test fun stretchingKeepsSampleCountProportionalToSpeed() {
        fun stretched(speed: Float): Int {
            val rate = 44100
            val s = app.ultimate.audio.model.Stretcher(rate, 2, speed)
            var total = 0
            val chunk = ShortArray(4410 * 2)
            repeat(20) { block ->
                for (f in 0 until 4410) {
                    val v = (Math.sin(2 * Math.PI * 440 * (block * 4410 + f) / rate) * 12000).toInt().toShort()
                    chunk[2 * f] = v; chunk[2 * f + 1] = v
                }
                total += s.process(chunk, chunk.size).second
            }
            total += s.finish().second
            return total / 2
        }
        val input = 20 * 4410
        listOf(2f, 0.5f, 1.5f).forEach { x ->
            val frames = stretched(x)
            val expected = input / x
            assertTrue("$x×: $frames frames, expected about $expected", kotlin.math.abs(frames - expected) < expected * 0.03)
        }
    }

    @Test fun mp3SizeFollowsBitrateAndTrim() {
        val full = AudioPlanner.estimateBytes(aac, AudioSettings(kbps = 192))
        assertEquals(192_000 / 8L * 180, full)
        val half = AudioPlanner.estimateBytes(aac, AudioSettings(kbps = 192, trimStartMs = 0, trimEndMs = 90_000))
        assertEquals(full / 2, half)
    }

    @Test fun trimIsClampedToTheVideo() {
        assertEquals(60_000, AudioPlanner.keptMs(aac, AudioSettings(trimStartMs = 120_000, trimEndMs = 999_999)))
        assertEquals(0, AudioPlanner.keptMs(aac, AudioSettings(trimStartMs = 500_000)))
    }

    @Test fun wavSizeIsRawPcm() {
        assertEquals(44100 * 2 * 2L * 180 + 44, AudioPlanner.estimateBytes(aac, AudioSettings(format = OutFormat.Wav)))
        assertEquals(44100 * 1 * 2L * 180 + 44, AudioPlanner.estimateBytes(aac, AudioSettings(format = OutFormat.Wav, mono = true)))
    }

    @Test fun oddSampleRatesAreResampledForMp3() {
        assertEquals(48000, AudioPlanner.encoderRate(48000))
        assertEquals(44100, AudioPlanner.encoderRate(96000))
    }

    @Test fun downmixAveragesStereoToMono() {
        val mixer = PcmMixer(2, 1, 44100, 44100)
        val (out, n) = mixer.process(shortArrayOf(1000, 3000, -2000, 2000), 4)
        assertEquals(2, n)
        assertEquals(2000, out[0].toInt())
        assertEquals(0, out[1].toInt())
    }

    @Test fun resamplingKeepsDurationAcrossBuffers() {
        val mixer = PcmMixer(1, 1, 96000, 44100)
        var produced = 0
        repeat(10) { produced += mixer.process(ShortArray(9600) { (it % 100).toShort() }, 9600).second }
        // 96000 input frames at 96 kHz is one second, which should become about 44100 frames.
        assertTrue("produced $produced", produced in 44090..44110)
    }

    @Test fun resamplerPreservesALinearRamp() {
        val mixer = PcmMixer(1, 1, 22050, 44100)
        val ramp = ShortArray(100) { (it * 100).toShort() }
        val (out, n) = mixer.process(ramp, 100)
        assertTrue(n >= 195)
        for (i in 2 until n - 2) assertTrue("monotonic at $i", out[i] >= out[i - 1])
    }

    @Test fun peaksBucketTheLoudestSample() {
        val tracker = PeakTracker(totalFrames = 1600, buckets = 160) // 10 frames per bucket
        val pcm = ShortArray(1600) { if (it == 25) 16384 else 100 }
        tracker.add(pcm, pcm.size, 1)
        val peaks = tracker.snapshot()
        assertEquals(160, peaks.size)
        assertEquals(0.5f, peaks[2], 0.001f)
        assertTrue(peaks[3] < 0.01f)
    }
}
