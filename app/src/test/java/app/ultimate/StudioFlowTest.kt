package app.ultimate

import androidx.media3.common.MimeTypes
import app.ultimate.studio.ExportPlanner
import app.ultimate.studio.SourceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioFlowTest {
    private val hevc = MimeTypes.VIDEO_H265; private val avc = MimeTypes.VIDEO_H264
    private fun mbps(x: Double) = (x * 1_000_000).toLong()

    @Test fun aTrimmedHevcSourceStaysHevcAtAboutItsOwnBitrate() {
        // The reported case: a Squeeze-made HEVC original re-encoded at a fixed 8 Mbps H.264 came out bigger than the original.
        val p = ExportPlanner.plan(listOf(SourceInfo(hevc, mbps(2.5), 120_000)), 1080)
        assertEquals(hevc, p.mime); assertTrue("not more than the source (${p.bitrate})", p.bitrate <= mbps(2.5)); assertTrue(p.bitrate >= 2_500_000 * 0.9)
    }

    @Test fun h264SourcesAreNeverGivenMoreBitsThanTheyHad() {
        val p = ExportPlanner.plan(listOf(SourceInfo(avc, mbps(4.0), 60_000)), 1080)
        assertEquals(avc, p.mime); assertTrue(p.bitrate <= mbps(4.0)); assertTrue(p.bitrate >= 3_000_000)
    }

    @Test fun sourcesNeverRaiseItAboveTheStandardCeilingAndNeverBelowTheFloor() {
        assertEquals(8_000_000, ExportPlanner.plan(listOf(SourceInfo(avc, mbps(40.0), 10_000)), 1080).bitrate)
        assertEquals(3_500_000, ExportPlanner.plan(listOf(SourceInfo(avc, mbps(40.0), 10_000)), 720).bitrate)
        assertEquals(2_500_000, ExportPlanner.plan(listOf(SourceInfo(avc, mbps(0.4), 10_000)), 1080).bitrate)
        assertEquals(1_200_000, ExportPlanner.plan(listOf(SourceInfo(avc, mbps(0.4), 10_000)), 720).bitrate)
    }

    @Test fun mixedClipsAreWeightedByHowMuchOfEachIsUsedAndHevcInTheMixGetsH264Headroom() {
        val p = ExportPlanner.plan(listOf(SourceInfo(hevc, mbps(2.0), 90_000), SourceInfo(avc, mbps(6.0), 10_000)), 1080)
        assertEquals("one non-HEVC clip means H.264", avc, p.mime)
        assertTrue("weighted toward the long HEVC clip: ${p.bitrate}", p.bitrate in 2_500_000..4_500_000)
    }

    @Test fun unknownSourcesGetTheStandardQualityAndNothingBreaks() {
        assertEquals(avc, ExportPlanner.plan(listOf(SourceInfo(null, null, 5_000)), 1080).mime)
        assertEquals(8_000_000, ExportPlanner.plan(listOf(SourceInfo(null, null, 5_000)), 1080).bitrate)
        assertEquals(8_000_000, ExportPlanner.plan(emptyList(), 1080).bitrate)
    }
}
