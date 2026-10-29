package app.ultimate

import app.ultimate.photos.PhotoCrop
import app.ultimate.photos.PhotoPlanner
import app.ultimate.photos.PhotoSettings
import app.ultimate.photos.PhotoSize
import app.ultimate.prompter.PrompterMath
import app.ultimate.recorder.RecorderMath
import app.ultimate.sensors.SensorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewToolsTest {
    @Test fun photoPlanCropsFromTheCentreThenScales() {
        val p = PhotoPlanner.plan(4000, 3000, PhotoSettings(size = PhotoSize.Hd, crop = PhotoCrop.Square))
        assertEquals(500 to 0, p.cropLeft to p.cropTop)
        assertEquals(3000 to 3000, p.cropWidth to p.cropHeight)
        assertEquals(1920 to 1920, p.outWidth to p.outHeight)
    }

    @Test fun photoPlanNeverUpscalesAndKeepsShape() {
        val p = PhotoPlanner.plan(1000, 800, PhotoSettings(size = PhotoSize.K4))
        assertEquals(1000 to 800, p.outWidth to p.outHeight)
        val tall = PhotoPlanner.plan(3000, 4000, PhotoSettings(size = PhotoSize.Web, crop = PhotoCrop.Story))
        assertEquals(2250 to 4000, tall.cropWidth to tall.cropHeight)
        assertEquals(720 to 1280, tall.outWidth to tall.outHeight)
    }

    @Test fun smallerSettingsEstimateSmaller() {
        val plan = PhotoPlanner.plan(4000, 3000, PhotoSettings())
        assertTrue(PhotoPlanner.estimateBytes(plan, PhotoSettings(quality = 60)) < PhotoPlanner.estimateBytes(plan, PhotoSettings(quality = 95)))
    }

    @Test fun recorderLevelsAndDownsampling() {
        assertEquals(0f, RecorderMath.level(0), 0f)
        assertEquals(1f, RecorderMath.level(32767), 1e-4f)
        assertEquals(0.5f, RecorderMath.level(1036), 0.01f) // -30 dB is halfway up a 60 dB scale
        val d = RecorderMath.downsample(List(1000) { if (it == 503) 1f else 0.1f }, 100)
        assertEquals(100, d.size)
        assertEquals(1f, d[50], 0f)
    }

    @Test fun prompterTiming() {
        assertEquals(4, PrompterMath.words("  one two\nthree   four "))
        assertEquals(60_000L, PrompterMath.readingMs(List(140) { "w" }.joinToString(" "), 140))
        assertEquals(500L, PrompterMath.remainingMs(500, 1000, 1000))
        assertEquals(0L, PrompterMath.remainingMs(10, 0, 1000))
    }

    @Test fun sensorHintsExplainSilence() {
        assertEquals("Listening…", SensorMath.waitingHint(1, false, 200))
        assertEquals("Wave a hand over the top of the screen", SensorMath.waitingHint(8, true, 3000))
        assertEquals("Reports only when something changes", SensorMath.waitingHint(99, true, 3000))
        assertTrue(SensorMath.waitingHint(99, false, 9000).startsWith("Quiet"))
        assertEquals("12,\"Light\",1.5,2.0", SensorMath.csvRow(12, "Light", floatArrayOf(1.5f, 2f)))
    }
}
