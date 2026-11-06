package app.ultimate.senselab

import app.ultimate.echo.GestureModel
import kotlin.math.abs

/**
 * Gesture Studio's arithmetic: every sense becomes channels on one timeline, a gesture is a short window across all of
 * them, and each sense's own share of the recognition is scored, so you can see which senses really notice a gesture.
 */
object StudioMath {
    /** The senses, and the channels each one contributes. */
    enum class Lane(val title: String, val channels: List<String>) {
        Proximity("Proximity", listOf("near")),
        Light("Light", listOf("light")),
        Phone("Phone motion", listOf("turn", "jolt")),
        Sound("Sound", listOf("loud")),
        Watch("Watch", listOf("wx", "wy", "wz", "wjolt")),
        Echo("Echo", listOf("range")),
    }
    val CHANNELS: List<String> = Lane.entries.flatMap { it.channels }
    /** Summary values per channel in a window. */
    const val PER_CHANNEL = 6
    /** How long one gesture window is. */
    const val WINDOW_MS = 1_400L

    /** One reading of one channel at a time. */
    data class Point(val t: Long, val channel: Int, val v: Float)

    /**
     * The window of [WINDOW_MS] ending at [end] as one vector: for each channel, its average, highest, lowest, range,
     * change from the first to the last quarter, and when in the window its biggest swing from the average happened
     * (0 start to 1 end). A channel with no readings is all zeros, so missing senses don't break the shape.
     */
    fun window(points: List<Point>, end: Long): FloatArray {
        val out = FloatArray(CHANNELS.size * PER_CHANNEL)
        val from = end - WINDOW_MS
        val by = points.filter { it.t in from..end }.groupBy { it.channel }
        for ((c, ps) in by) {
            if (c !in CHANNELS.indices || ps.isEmpty()) continue
            val v = ps.map { it.v }; val mean = v.average().toFloat()
            val q = maxOf(1, ps.size / 4)
            val peak = ps.maxBy { abs(it.v - mean) }
            val o = c * PER_CHANNEL
            out[o] = mean; out[o + 1] = v.max(); out[o + 2] = v.min(); out[o + 3] = v.max() - v.min()
            out[o + 4] = (v.takeLast(q).average() - v.take(q).average()).toFloat()
            out[o + 5] = (peak.t - from).toFloat() / WINDOW_MS
        }
        return out
    }

    /** Keeps only [lane]'s part of a window vector, zeroing the rest: what that sense alone sees. */
    fun only(x: FloatArray, lane: Lane): FloatArray {
        val keep = lane.channels.map { CHANNELS.indexOf(it) }.toSet()
        return FloatArray(x.size) { i -> if (i / PER_CHANNEL in keep) x[i] else 0f }
    }

    /** Which lanes have any reading in this set of examples (a lane that was off says nothing). */
    fun lanesSeen(samples: List<GestureModel.Sample>): Set<Lane> = Lane.entries.filter { lane ->
        val idx = lane.channels.map { CHANNELS.indexOf(it) }
        samples.any { s -> idx.any { c -> (0 until PER_CHANNEL).any { s.x[c * PER_CHANNEL + it] != 0f } } }
    }.toSet()

    /**
     * How well each sense alone recognises each gesture: leave-one-out on examples cut down to that sense. Tells you
     * which senses to trust for which gesture, and is how "teach once, every sense learns" shows its work.
     */
    fun reliability(model: GestureModel): Map<String, Map<Lane, Double>> {
        val lanes = lanesSeen(model.samples)
        return model.labels.filter { it != GestureModel.NONE }.associateWith { label ->
            lanes.associateWith { lane -> GestureModel(model.samples.map { GestureModel.Sample(it.label, only(it.x, lane)) }).accuracy()[label] ?: 0.0 }
        }
    }
}
