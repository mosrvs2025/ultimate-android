package app.ultimate.squeeze.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Video codecs Squeeze can encode with. [efficiency] is bits needed relative to H.264 for equal visual quality. */
enum class Codec(val mime: String, val label: String, val efficiency: Double) {
    H264("video/avc", "H.264", 1.0),
    HEVC("video/hevc", "HEVC", 0.70),
    AV1("video/av01", "AV1", 0.58),
}

enum class CodecChoice(val label: String) { Auto("Auto"), H264("H.264"), Hevc("HEVC"), Av1("AV1") }

enum class Mode { Preset, TargetSize, Exact }

/** Centre crops to a shape. [ratio] is width / height; null keeps the whole frame. */
enum class CropShape(val label: String, val ratio: Double?) {
    Original("Original", null), Square("1:1", 1.0), Portrait("9:16", 9.0 / 16), Tall("4:5", 4.0 / 5), Wide("16:9", 16.0 / 9), Classic("4:3", 4.0 / 3),
}

/**
 * The part of the frame to keep, as fractions of the width and height. [offset] slides it along the side that is cut,
 * from -1 (left or top edge) through 0 (centre) to 1 (right or bottom edge).
 */
data class CropBox(val widthFraction: Double, val heightFraction: Double, val offset: Double = 0.0) {
    /** Edges in normalised device coordinates (-1..1, y up), as Media3's Crop effect takes them. */
    fun ndc(): FloatArray {
        val cx = if (widthFraction < 1) offset * (1 - widthFraction) else 0.0
        val cy = if (heightFraction < 1) -offset * (1 - heightFraction) else 0.0
        return floatArrayOf((cx - widthFraction).toFloat(), (cx + widthFraction).toFloat(), (cy - heightFraction).toFloat(), (cy + heightFraction).toFloat())
    }
}

/**
 * Quality tiers. [bpp] is the H.264-equivalent bits per pixel per frame at 1080p; the planner scales it by
 * codec efficiency and resolution, and never asks for more than the source already spends.
 */
enum class Preset(
    val label: String,
    val blurb: String,
    val bpp: Double,
    val maxShortSide: Int,
    val fpsCap: Int,
) {
    Balanced("Balanced", "Looks like the original. Keeps resolution.", 0.100, 2160, 60),
    Small("Small", "Great for sharing. Up to 1080p, 30 fps.", 0.065, 1080, 30),
    Tiny("Tiny", "Smallest. Up to 720p, 30 fps.", 0.040, 720, 30),
}

data class Settings(
    val mode: Mode = Mode.Preset,
    val preset: Preset = Preset.Balanced,
    val targetMb: Int = 25,
    val codec: CodecChoice = CodecChoice.Auto,
    val maxShortSide: Int? = null,
    val maxFps: Int? = null,
    val muteAudio: Boolean = false,
    /** Playback speed of the result (0.25–4); pitch is kept. */
    val speed: Float = 1f,
    val crop: CropShape = CropShape.Original,
    /** Where the crop sits along the cut side: -1 start, 0 centre, 1 end. */
    val cropOffset: Float = 0f,
    /** Exact mode: video bitrate in kbit/s. */
    val exactKbps: Int = 2500,
    /** Exact mode: short side in pixels (e.g. 720 for 1280×720), or null to keep the source's. */
    val exactShortSide: Int? = null,
) {
    /** Anything beyond compression: the result is wanted even if it is not much smaller. */
    val edits: Boolean get() = speed != 1f || crop != CropShape.Original || mode == Mode.Exact
}

/** Which encoders this device offers in hardware. H.264 is assumed everywhere. */
data class Capabilities(val hevc: Boolean, val av1: Boolean)

/** Everything the planner needs to know about a source file, in display orientation. */
data class Source(
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val hasAudio: Boolean,
    val isHdr: Boolean,
    val mime: String,
) {
    val shortSide: Int get() = min(width, height)
}

data class Plan(
    val codec: Codec,
    val width: Int,
    val height: Int,
    val resize: Boolean,
    /** Target frame rate when frames must be dropped, otherwise null to keep the source rate. */
    val fps: Int?,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val estimatedBytes: Long,
    /** Non-null when re-encoding is not worthwhile; the original is kept. */
    val skipReason: String? = null,
    val warning: String? = null,
    val crop: CropBox? = null,
    val speed: Float = 1f,
) {
    val shortSide: Int get() = min(width, height)
}

/**
 * Chooses codec, resolution, frame rate and bitrate for one video.
 *
 * Strategy, in order of impact:
 *  1. Prefer the most efficient hardware codec (hardware HEVC ≈ 30% fewer bits than H.264 at equal quality).
 *  2. Budget bits per pixel instead of a fixed bitrate, so quality is consistent across resolutions and frame
 *     rates; larger frames need fewer bits per pixel, smaller ones more.
 *  3. Never spend more than the source does, and skip files that would not shrink meaningfully.
 *  4. In target-size mode, solve for the bitrate that hits the size, then trade frame rate and resolution
 *     (not blocky pixels) until the bits-per-pixel budget stays above a watchability floor.
 */
object Planner {
    const val MIN_SAVING = 0.10
    private const val SOURCE_BITRATE_CEILING = 0.90
    private const val TARGET_HEADROOM = 0.93
    private const val MIN_VIDEO_BPS = 120_000
    private const val DEFAULT_AUDIO_BPS = 128_000
    private const val TARGET_FLOOR_BPP = 0.045
    private val LADDER = intArrayOf(2160, 1440, 1080, 720, 540, 480, 360, 240)
    val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
    val EXACT_SIDES = listOf(2160, 1440, 1080, 720, 480, 360)
    const val MIN_EXACT_KBPS = 100
    const val MAX_EXACT_KBPS = 100_000

    /** Centre crop for [shape] on a frame of [width]×[height], or null when nothing is cut. */
    fun cropBox(width: Int, height: Int, shape: CropShape, offset: Float = 0f): CropBox? {
        val r = shape.ratio ?: return null
        val source = width.toDouble() / height
        val o = offset.coerceIn(-1f, 1f).toDouble()
        return when {
            source > r * 1.001 -> CropBox(r / source, 1.0, o)
            source < r / 1.001 -> CropBox(1.0, source / r, o)
            else -> null
        }
    }

    fun resolveCodec(choice: CodecChoice, caps: Capabilities): Codec = when (choice) {
        CodecChoice.H264 -> Codec.H264
        CodecChoice.Av1 -> if (caps.av1) Codec.AV1 else if (caps.hevc) Codec.HEVC else Codec.H264
        CodecChoice.Hevc, CodecChoice.Auto -> if (caps.hevc) Codec.HEVC else Codec.H264
    }

    fun plan(original: Source, s: Settings, caps: Capabilities): Plan {
        val crop = cropBox(original.width, original.height, s.crop, s.cropOffset)
        val speed = s.speed.coerceIn(0.25f, 4f)
        // Plan against what will be encoded: the cropped frame, lasting duration / speed.
        val src = original.copy(
            width = crop?.let { (original.width * it.widthFraction).toInt() } ?: original.width,
            height = crop?.let { (original.height * it.heightFraction).toInt() } ?: original.height,
            durationMs = (original.durationMs / speed).toLong(),
        )
        val base = planFor(src, s, caps)
        if (!s.edits) return base
        // Edits are wanted even when they barely shrink the file; and when sped up, frames are dropped back to the source rate.
        val fps = base.fps ?: if (speed > 1f) original.fps.coerceIn(1f, 60f).roundToInt() else null
        return base.copy(
            crop = crop, speed = speed, fps = fps, resize = true, skipReason = null,
            estimatedBytes = ((base.videoBitrate + base.audioBitrate) * (src.durationMs / 1000.0) / 8).toLong(),
        )
    }

    private fun planFor(src: Source, s: Settings, caps: Capabilities): Plan {
        val codec = resolveCodec(s.codec, caps)
        val audioBps = when {
            !src.hasAudio || s.muteAudio -> 0
            src.audioBitrate > 0 -> src.audioBitrate
            else -> DEFAULT_AUDIO_BPS
        }
        val seconds = max(src.durationMs / 1000.0, 0.001)
        return when (s.mode) {
            Mode.Preset -> byPreset(src, s, codec, audioBps, seconds)
            Mode.TargetSize -> byTarget(src, s, codec, audioBps, seconds)
            Mode.Exact -> byExact(src, s, codec, audioBps, seconds)
        }
    }

    /** Exactly the bitrate and resolution asked for, without second-guessing. */
    private fun byExact(src: Source, s: Settings, codec: Codec, audioBps: Int, seconds: Double): Plan {
        val (w, h) = s.exactShortSide?.let { side -> exactSize(src, side) } ?: (evenRound(src.width.toDouble()) to evenRound(src.height.toDouble()))
        val fps = s.maxFps?.let { effectiveFps(src, it) }
        val video = s.exactKbps.coerceIn(MIN_EXACT_KBPS, MAX_EXACT_KBPS) * 1000
        val warning = if (src.videoBitrate in 1 until video) "That is more than the original spends (${src.videoBitrate / 1000} kbps), so the file will grow without looking better" else null
        val plan = Plan(codec, w, h, true, fps, video, audioBps, ((video + audioBps) * seconds / 8).toLong(), null, warning)
        return plan
    }

    /** Scales to exactly [shortSide] on the short edge (up or down), keeping the shape. */
    fun exactSize(src: Source, shortSide: Int): Pair<Int, Int> {
        val k = shortSide.toDouble() / src.shortSide
        return evenRound(src.width * k) to evenRound(src.height * k)
    }

    private fun byPreset(src: Source, s: Settings, codec: Codec, audioBps: Int, seconds: Double): Plan {
        val p = s.preset
        val side = listOfNotNull(src.shortSide, p.maxShortSide, s.maxShortSide).min()
        val (w, h) = scaled(src, side)
        val fps = effectiveFps(src, min(p.fpsCap, s.maxFps ?: Int.MAX_VALUE))
        val rate = fps ?: src.fps.coerceIn(1f, 120f).toDouble().roundToInt()

        val wanted = p.bpp * codec.efficiency * resolutionFactor(w, h) * w * h * rate
        // Spend at most what the source spends, translated to the new codec's efficiency.
        val ceiling = if (src.videoBitrate > 0) src.videoBitrate * (codec.efficiency / sourceEfficiency(src.mime)) * SOURCE_BITRATE_CEILING else Double.MAX_VALUE
        val video = min(wanted, ceiling).roundToInt().coerceAtLeast(MIN_VIDEO_BPS)
        return finish(src, codec, w, h, fps, video, audioBps, seconds, warning = null)
    }

    private fun byTarget(src: Source, s: Settings, codec: Codec, audioBps: Int, seconds: Double): Plan {
        val targetBytes = s.targetMb * 1_000_000L
        if (targetBytes >= src.sizeBytes * (1 - MIN_SAVING)) {
            return keep(src, codec, audioBps, "Already smaller than the ${s.targetMb} MB target")
        }
        val budgetBps = targetBytes * 8 * TARGET_HEADROOM / seconds
        val video = ((budgetBps - audioBps).roundToInt()).coerceAtLeast(MIN_VIDEO_BPS)

        val sourceFps = src.fps.coerceIn(1f, 120f)
        val maxSide = listOfNotNull(src.shortSide, s.maxShortSide).min()
        val sides = (listOf(maxSide) + LADDER.filter { it < maxSide }).distinct()
        val rates = listOfNotNull(
            effectiveFps(src, s.maxFps ?: Int.MAX_VALUE) ?: sourceFps.roundToInt(),
            30.takeIf { sourceFps > 30.5f && (s.maxFps ?: 30) >= 30 },
            24.takeIf { sourceFps > 24.5f && (s.maxFps ?: 24) >= 24 },
        ).distinct()

        for (side in sides) {
            val (w, h) = scaled(src, side)
            for (rate in rates) {
                val bpp = video / (w.toDouble() * h * rate)
                if (bpp >= TARGET_FLOOR_BPP * codec.efficiency * resolutionFactor(w, h)) {
                    return finish(src, codec, w, h, fpsFor(src, rate), video, audioBps, seconds, warning = null)
                        .warnIfOver(targetBytes, s.targetMb)
                }
            }
        }
        val (w, h) = scaled(src, sides.last())
        val rate = rates.last()
        return finish(
            src, codec, w, h, fpsFor(src, rate), video, audioBps, seconds,
            warning = "${s.targetMb} MB is very small for this video; expect visible blockiness",
        ).warnIfOver(targetBytes, s.targetMb)
    }

    /** The bitrate floor can make the smallest watchable encode larger than the request; say so instead of missing silently. */
    private fun Plan.warnIfOver(targetBytes: Long, targetMb: Int): Plan =
        if (skipReason == null && estimatedBytes > targetBytes * 1.05) {
            copy(warning = "Can't reach $targetMb MB. Smallest practical size is about ${(estimatedBytes + 500_000) / 1_000_000} MB")
        } else {
            this
        }

    private fun sourceEfficiency(mime: String): Double = when (mime.lowercase()) {
        Codec.HEVC.mime -> Codec.HEVC.efficiency
        Codec.AV1.mime -> Codec.AV1.efficiency
        "video/x-vnd.on2.vp9" -> 0.65
        else -> Codec.H264.efficiency
    }

    private fun finish(
        src: Source, codec: Codec, w: Int, h: Int, fps: Int?, video: Int, audioBps: Int, seconds: Double, warning: String?,
    ): Plan {
        val estimate = ((video + audioBps) * seconds / 8).toLong()
        val resize = w != evenRound(src.width.toDouble()) || h != evenRound(src.height.toDouble())
        val plan = Plan(codec, w, h, resize, fps, video, audioBps, estimate, null, warning)
        val saving = 1 - estimate.toDouble() / max(src.sizeBytes, 1)
        return if (saving < MIN_SAVING) {
            plan.copy(skipReason = "Already efficient. It would save under ${(MIN_SAVING * 100).toInt()}%", estimatedBytes = src.sizeBytes)
        } else {
            plan
        }
    }

    private fun keep(src: Source, codec: Codec, audioBps: Int, reason: String) = Plan(
        codec, src.width, src.height, resize = false, fps = null,
        videoBitrate = src.videoBitrate, audioBitrate = audioBps,
        estimatedBytes = src.sizeBytes, skipReason = reason,
    )

    /** Frame-drop target if [cap] is meaningfully below the source rate; null means keep every frame. */
    private fun effectiveFps(src: Source, cap: Int): Int? = fpsFor(src, min(cap, 120))

    private fun fpsFor(src: Source, rate: Int): Int? = if (rate < src.fps - 0.5f) rate else null

    /** Bigger frames hide artifacts better, so they need fewer bits per pixel than 1080p; smaller ones need more. */
    private fun resolutionFactor(w: Int, h: Int): Double =
        (1920.0 * 1080.0 / (w.toDouble() * h)).pow(0.25).coerceIn(0.6, 1.5)

    private fun scaled(src: Source, shortSide: Int): Pair<Int, Int> {
        if (shortSide >= src.shortSide) return evenRound(src.width.toDouble()) to evenRound(src.height.toDouble())
        val k = shortSide.toDouble() / src.shortSide
        return evenRound(src.width * k) to evenRound(src.height * k)
    }

    private fun evenRound(v: Double): Int = max(2, (v / 2).roundToInt() * 2)
}
