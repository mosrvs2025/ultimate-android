package app.ultimate.squeeze.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    private val hevc = Capabilities(hevc = true, av1 = false)
    private val legacy = Capabilities(hevc = false, av1 = false)

    /** A typical phone clip: 1080p30 at ~17 Mbps, one minute, with AAC audio. */
    private fun phoneClip(
        width: Int = 1920, height: Int = 1080, fps: Float = 30f, seconds: Int = 60, videoBps: Int = 17_000_000,
        mime: String = "video/avc",
    ) = Source(
        sizeBytes = (videoBps + 128_000L) * seconds / 8,
        durationMs = seconds * 1000L, width = width, height = height, fps = fps,
        videoBitrate = videoBps, audioBitrate = 128_000, hasAudio = true, isHdr = false, mime = mime,
    )

    @Test fun cropToSquareKeepsTheShortSideAndIsAlwaysEncoded() {
        val plan = Planner.plan(phoneClip(), Settings(crop = CropShape.Square), hevc)
        assertNull(plan.skipReason)
        assertEquals(plan.width, plan.height)
        assertEquals(1080, plan.width)
        val box = plan.crop!!
        assertEquals(1080.0 / 1920, box.widthFraction, 1e-9)
        assertEquals(1.0, box.heightFraction, 1e-9)
    }

    @Test fun cropPositionSlidesTheBoxWithoutLeavingTheFrame() {
        val left = Planner.cropBox(1920, 1080, CropShape.Square, -1f)!!.ndc()
        assertEquals(-1f, left[0], 1e-6f)
        val right = Planner.cropBox(1920, 1080, CropShape.Square, 1f)!!.ndc()
        assertEquals(1f, right[1], 1e-6f)
        val top = Planner.cropBox(1080, 1920, CropShape.Square, -1f)!!.ndc()
        assertEquals("top is +1 in NDC", 1f, top[3], 1e-6f)
        assertNull(Planner.cropBox(1920, 1080, CropShape.Wide))
    }

    @Test fun speedShortensTheResultAndCapsTheFrameRate() {
        val src = phoneClip(fps = 30f)
        val normal = Planner.plan(src, Settings(mode = Mode.Exact, exactKbps = 4000), hevc)
        val fast = Planner.plan(src, Settings(mode = Mode.Exact, exactKbps = 4000, speed = 2f), hevc)
        assertEquals(2f, fast.speed)
        assertEquals(normal.estimatedBytes / 2.0, fast.estimatedBytes.toDouble(), normal.estimatedBytes / 100.0)
        assertEquals("frames dropped back to the source rate", 30, fast.fps)
    }

    @Test fun exactModeUsesTheNumbersGivenEvenUpscaling() {
        val small = phoneClip(width = 1280, height = 720, videoBps = 1_000_000)
        val plan = Planner.plan(small, Settings(mode = Mode.Exact, exactKbps = 6000, exactShortSide = 1080), hevc)
        assertEquals(6_000_000, plan.videoBitrate)
        assertEquals(1920 to 1080, plan.width to plan.height)
        assertNull(plan.skipReason)
        assertNotNull("warns that it will only grow", plan.warning)
    }

    @Test fun balancedUsesHevcAndShrinksPhoneFootageDramatically() {
        val src = phoneClip()
        val plan = Planner.plan(src, Settings(), hevc)
        assertEquals(Codec.HEVC, plan.codec)
        assertNull(plan.skipReason)
        assertTrue("bitrate ${plan.videoBitrate}", plan.videoBitrate in 3_000_000..4_500_000)
        assertTrue(plan.estimatedBytes < src.sizeBytes * 0.3)
    }

    @Test fun fallsBackToH264WithoutHardwareHevc() {
        val plan = Planner.plan(phoneClip(), Settings(), legacy)
        assertEquals(Codec.H264, plan.codec)
        assertTrue(plan.videoBitrate > Planner.plan(phoneClip(), Settings(), hevc).videoBitrate)
    }

    @Test fun presetsAreOrderedBySize() {
        val sizes = Preset.entries.map { Planner.plan(phoneClip(), Settings(preset = it), hevc).estimatedBytes }
        assertTrue(sizes.zipWithNext().all { (a, b) -> a > b })
    }

    @Test fun neverUpscalesOrExceedsSourceBitrate() {
        val low = phoneClip(width = 640, height = 360, videoBps = 900_000)
        val plan = Planner.plan(low, Settings(preset = Preset.Balanced), hevc)
        assertTrue(plan.width <= 640 && plan.height <= 360)
        assertTrue(plan.videoBitrate <= 900_000)
    }

    @Test fun alreadyEfficientFilesAreSkipped() {
        val efficient = phoneClip(videoBps = 2_200_000, mime = "video/hevc")
        val plan = Planner.plan(efficient, Settings(preset = Preset.Balanced), hevc)
        assertNotNull(plan.skipReason)
        assertEquals(efficient.sizeBytes, plan.estimatedBytes)
    }

    @Test fun leanH264IsStillWorthConvertingToHevc() {
        val plan = Planner.plan(phoneClip(videoBps = 2_200_000), Settings(preset = Preset.Balanced), hevc)
        assertNull(plan.skipReason)
        assertTrue(plan.estimatedBytes < phoneClip(videoBps = 2_200_000).sizeBytes * 0.70)
    }

    @Test fun tinyDownscalesAndCapsFrameRate() {
        val plan = Planner.plan(phoneClip(width = 3840, height = 2160, fps = 60f), Settings(preset = Preset.Tiny), hevc)
        assertEquals(720, plan.shortSide)
        assertEquals(30, plan.fps)
        assertTrue(plan.resize)
    }

    @Test fun portraitVideosScaleOnTheShortSide() {
        val plan = Planner.plan(phoneClip(width = 1080, height = 1920), Settings(preset = Preset.Tiny), hevc)
        assertEquals(720, plan.width)
        assertEquals(1280, plan.height)
    }

    @Test fun dimensionsAreAlwaysEven() {
        val plan = Planner.plan(phoneClip(width = 1918, height = 1078), Settings(preset = Preset.Tiny), hevc)
        assertEquals(0, plan.width % 2)
        assertEquals(0, plan.height % 2)
    }

    @Test fun targetSizeLandsUnderTheTarget() {
        val src = phoneClip(width = 3840, height = 2160, fps = 60f, seconds = 300, videoBps = 60_000_000)
        val plan = Planner.plan(src, Settings(mode = Mode.TargetSize, targetMb = 50), hevc)
        assertNull(plan.skipReason)
        assertTrue("est ${plan.estimatedBytes}", plan.estimatedBytes <= 50_000_000)
        assertTrue("should trade resolution/fps before blockiness", plan.shortSide < 2160 || plan.fps != null)
    }

    @Test fun targetSizeKeepsResolutionWhenBudgetIsAmple() {
        val src = phoneClip(seconds = 30)
        val plan = Planner.plan(src, Settings(mode = Mode.TargetSize, targetMb = 40), hevc)
        assertEquals(1080, plan.shortSide)
        assertNull(plan.fps)
        assertNull(plan.warning)
    }

    @Test fun targetSizeWarnsWhenImpossible() {
        val src = phoneClip(seconds = 600, videoBps = 12_000_000)
        val plan = Planner.plan(src, Settings(mode = Mode.TargetSize, targetMb = 2), hevc)
        assertNotNull(plan.warning)
        assertEquals(240, plan.shortSide)
    }

    @Test fun targetLargerThanSourceKeepsOriginal() {
        val plan = Planner.plan(phoneClip(seconds = 10), Settings(mode = Mode.TargetSize, targetMb = 500), hevc)
        assertNotNull(plan.skipReason)
    }

    @Test fun mutingRemovesAudioFromTheEstimate() {
        val a = Planner.plan(phoneClip(), Settings(), hevc)
        val b = Planner.plan(phoneClip(), Settings(muteAudio = true), hevc)
        assertEquals(0, b.audioBitrate)
        assertTrue(b.estimatedBytes < a.estimatedBytes)
    }

    @Test fun userCapsApplyOnTopOfPreset() {
        val plan = Planner.plan(phoneClip(fps = 60f), Settings(maxShortSide = 720, maxFps = 24), hevc)
        assertEquals(720, plan.shortSide)
        assertEquals(24, plan.fps)
    }
}
