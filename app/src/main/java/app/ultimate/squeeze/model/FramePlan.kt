package app.ultimate.squeeze.model

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Pure maths for the squeeze "playthrough": which moments of a clip to sample, how big, and how to blend them. */
object FramePlan {
    /** How many frames are pulled from a clip. Enough to read as motion, few enough to stay small in memory. */
    const val COUNT = 12

    /** Longest side of a stored frame, in pixels. 12 frames at 320×180 are about 2.7 MB in total. */
    const val MAX_EDGE = 320

    /** Fraction of each frame step spent holding the frame before it blends into the next. */
    const val HOLD = 0.35f

    /**
     * [count] moments spread evenly over the whole clip, in microseconds. Each is the middle of its own slice, so the
     * first is never the (often black) opening frame and the last never lands on the end of the stream.
     */
    fun timestampsUs(durationMs: Long, count: Int = COUNT): List<Long> {
        if (durationMs <= 0 || count <= 0) return emptyList()
        val durationUs = durationMs * 1000
        return List(count) { i -> durationUs * (2L * i + 1) / (2L * count) }
    }

    /**
     * Size to scale a [width]×[height] video to so its longest side is [maxEdge], keeping the aspect ratio. The
     * retriever hands frames back already rotated, so a sideways [rotation] (90 or 270) swaps the sides first.
     * Never scales up. Returns null when the dimensions are unusable.
     */
    fun scaledSize(width: Int, height: Int, rotation: Int = 0, maxEdge: Int = MAX_EDGE): Pair<Int, Int>? {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return null
        val sideways = rotation % 180 != 0
        val w = if (sideways) height else width
        val h = if (sideways) width else height
        val scale = minOf(1f, maxEdge.toFloat() / max(w, h))
        return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
    }

    /** What to draw at a point of the loop: frame [from], blended [mix] (0..1) of the way into frame [to]. */
    data class Step(val from: Int, val to: Int, val mix: Float)

    /**
     * Where the flipbook is at [position], which counts frames and wraps after [count]: the whole part picks the frame,
     * the fraction first holds it for [hold], then eases into the next one so each flip is smooth rather than a cut.
     */
    fun step(position: Float, count: Int, hold: Float = HOLD): Step {
        if (count <= 1) return Step(0, 0, 0f)
        val p = ((position % count) + count) % count
        val i = floor(p).toInt().coerceIn(0, count - 1)
        val t = ((p - i - hold) / (1f - hold)).coerceIn(0f, 1f)
        return Step(i, (i + 1) % count, t * t * (3f - 2f * t))
    }

    /** How many of [count] frames lie within the first [progress] (0..1) of the clip. */
    fun framesDone(progress: Float, count: Int): Int = (progress.coerceIn(0f, 1f) * count).roundToInt().coerceIn(0, count)
}
