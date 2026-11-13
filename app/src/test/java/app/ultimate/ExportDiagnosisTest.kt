package app.ultimate

import androidx.media3.common.MimeTypes
import app.ultimate.studio.ExportDiagnosis
import app.ultimate.studio.StudioClip
import app.ultimate.studio.StudioProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportDiagnosisTest {
    private fun clip(speed: Float = 1f, volume: Float = 1f, rotation: Int = 0) = StudioClip(uri = "content://x/1", name = "private name.mp4", sourceMs = 10_000, speed = speed, volume = volume, rotation = rotation)

    @Test fun describesWhatWasUnusualWithoutAnyNamesOrContent() {
        val p = StudioProject(name = "My secret project", clips = listOf(clip(), clip(speed = 2f, volume = 0f, rotation = 90)))
        val line = ExportDiagnosis.summarize(p, listOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264), "ERROR_CODE_FAILED_RUNTIME_CHECK")
        assertTrue(line, line.startsWith("ERROR_CODE_FAILED_RUNTIME_CHECK · 2 clips"))
        assertTrue(line.contains("hevc,avc")); assertTrue(line.contains("speed 1.0-2.0")); assertTrue(line.contains("rotated")); assertTrue(line.contains("muted clip"))
        assertFalse(line.contains("secret")); assertFalse(line.contains("private"))
        assertTrue(line.length <= 150)
    }

    @Test fun anUnknownCodecAndNoCodeStillMakeALine() {
        val line = ExportDiagnosis.summarize(StudioProject(name = "p", clips = listOf(clip())), listOf(null), null)
        assertEquals("error · 1 clip ${minOf(StudioProject(name = "p").width, StudioProject(name = "p").height)}p · ?", line)
    }
}
