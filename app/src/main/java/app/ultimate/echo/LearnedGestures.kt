package app.ultimate.echo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Gestures Echo learns from you, so they work with the camera off.
 *
 * While teaching, the camera recognises each gesture and labels the half second of sonar around it; the sonar alone
 * then learns what that gesture sounds like on this phone, in this room, from this hand. Recognition compares a live
 * window of sonar with the examples you gave (nearest neighbours), so there is no training step to wait for and adding
 * examples improves it at once.
 */
object SonarFeatures {
    /** Coarse distance bins of the motion profile used per frame. */
    const val BINS = 8
    /** Values kept per frame: has-range, range, velocity, confidence, lean, then [BINS] motion levels. */
    const val PER_FRAME = 5 + BINS
    /** How much sonar one gesture covers. */
    const val SPAN_MS = 600L

    /** One sonar frame as numbers; missing values become 0 so a window can always be built. */
    fun frame(rangeM: Double, velocity: Double, confidence: Double, lean: Int?, motion: FloatArray): FloatArray {
        val f = FloatArray(PER_FRAME)
        val has = !rangeM.isNaN()
        f[0] = if (has) 1f else 0f
        f[1] = if (has) rangeM.toFloat().coerceIn(0f, 1.5f) else 0f
        f[2] = if (velocity.isNaN()) 0f else velocity.toFloat().coerceIn(-2f, 2f)
        f[3] = if (confidence.isNaN() || confidence <= 0) 0f else ln(1 + confidence).toFloat()
        f[4] = (lean ?: 0).toFloat()
        if (motion.isNotEmpty()) for (b in 0 until BINS) {
            val lo = b * motion.size / BINS; val hi = ((b + 1) * motion.size / BINS).coerceAtLeast(lo + 1).coerceAtMost(motion.size)
            var m = 0f; for (i in lo until hi) m = maxOf(m, motion[i])
            f[5 + b] = ln(1 + m.coerceAtLeast(0f))
        }
        return f
    }

    /**
     * Summarises the frames of the [SPAN_MS] before [end] into one vector: averages, how the distance and speed changed
     * from the first half to the second, the extremes, and where along the distance bins the motion moved. Null when
     * there are too few frames to say anything.
     */
    fun window(frames: List<Pair<Long, FloatArray>>, end: Long): FloatArray? {
        val w = frames.filter { it.first in (end - SPAN_MS)..end }.map { it.second }
        if (w.size < 6) return null
        val half = w.size / 2
        val out = ArrayList<Float>(PER_FRAME * 3 + 4)
        for (d in 0 until PER_FRAME) {
            val all = w.map { it[d] }
            out += all.average().toFloat()
            out += (w.drop(half).map { it[d] }.average() - w.take(half).map { it[d] }.average()).toFloat()
            out += all.max() - all.min()
        }
        // The motion's centre along the distance bins, at the start and the end: a hand moving across or toward.
        fun centre(f: FloatArray): Float { var s = 0f; var t = 0f; for (b in 0 until BINS) { s += f[5 + b] * b; t += f[5 + b] }; return if (t > 1e-3f) s / t else 0f }
        out += centre(w.first()); out += centre(w.last()); out += centre(w.last()) - centre(w.first())
        out += w.count { it[0] > 0.5f }.toFloat() / w.size
        return out.toFloatArray()
    }
}

/** Your examples and what they mean. "none" is the room and your hand doing nothing in particular. */
class GestureModel(val samples: List<Sample>) {
    data class Sample(val label: String, val x: FloatArray)
    data class Guess(val label: String, val votes: Float)

    val labels: List<String> = samples.map { it.label }.distinct()
    private val dims = samples.firstOrNull()?.x?.size ?: 0
    private val mean = FloatArray(dims)
    private val scale = FloatArray(dims) { 1f }

    init {
        if (samples.isNotEmpty()) for (d in 0 until dims) {
            val v = samples.map { it.x[d] }
            val m = v.average().toFloat(); mean[d] = m
            scale[d] = sqrt(v.map { (it - m) * (it - m) }.average()).toFloat().coerceAtLeast(1e-3f)
        }
    }

    private fun dist(a: FloatArray, b: FloatArray): Float { var s = 0f; for (d in 0 until dims) { val t = (a[d] - b[d]) / scale[d]; s += t * t }; return s }

    /** The label most of the [k] closest examples agree on, and what share of them agree. */
    fun predict(x: FloatArray, k: Int = 5, skip: Int = -1): Guess? {
        if (x.size != dims || samples.size < 2) return null
        val near = samples.indices.filter { it != skip }.sortedBy { dist(x, samples[it].x) }.take(k)
        val top = near.groupingBy { samples[it].label }.eachCount().maxByOrNull { it.value } ?: return null
        return Guess(top.key, top.value.toFloat() / near.size)
    }

    /** How often each gesture is recognised when its own example is held out: an honest score from your data alone. */
    fun accuracy(): Map<String, Double> = labels.associateWith { l ->
        val idx = samples.indices.filter { samples[it].label == l }
        if (idx.isEmpty()) 0.0 else idx.count { predict(samples[it].x, skip = it)?.label == l }.toDouble() / idx.size
    }

    fun count(label: String) = samples.count { it.label == label }

    fun toJson(): String = JSONObject().put("version", 1).put("samples", JSONArray().apply {
        samples.forEach { s -> put(JSONObject().put("label", s.label).put("x", JSONArray().apply { s.x.forEach { put(it.toDouble()) } })) }
    }).toString()

    companion object {
        const val NONE = "none"
        fun fromJson(text: String): GestureModel? = runCatching {
            val a = JSONObject(text).getJSONArray("samples")
            GestureModel((0 until a.length()).map { i ->
                val o = a.getJSONObject(i); val x = o.getJSONArray("x")
                Sample(o.getString("label"), FloatArray(x.length()) { x.getDouble(it).toFloat() })
            })
        }.getOrNull()

        private fun file(c: Context) = File(c.filesDir, "echo_learned_gestures.json")
        fun load(c: Context): GestureModel? = file(c).takeIf { it.exists() }?.readText()?.let(::fromJson)?.takeIf { it.labels.any { l -> l != NONE } }
        fun save(c: Context, m: GestureModel) { file(c).writeText(m.toJson()) }
        fun forget(c: Context) { file(c).delete() }
    }
}

/**
 * Watches the sonar for learned gestures. A gesture counts when the same one wins on [agree] frames in a row with most
 * of its neighbours behind it, then rests for [refractoryMs], so one wave is one event and noise rarely gets through.
 */
class LearnedGestureDetector(private val model: GestureModel, private val agree: Int = 3, private val minVotes: Float = 0.6f, private val refractoryMs: Long = 900) {
    private val frames = ArrayDeque<Pair<Long, FloatArray>>()
    private var streak: String? = null
    private var streakLen = 0
    private var lastFired = Long.MIN_VALUE / 2

    fun feed(frame: FloatArray, now: Long): String? {
        frames.addLast(now to frame)
        while (frames.isNotEmpty() && now - frames.first().first > SonarFeatures.SPAN_MS + 200) frames.removeFirst()
        val x = SonarFeatures.window(frames, now) ?: return null
        val g = model.predict(x)
        val label = g?.takeIf { it.votes >= minVotes && it.label != GestureModel.NONE }?.label
        if (label == null || label != streak) { streak = label; streakLen = if (label == null) 0 else 1; return null }
        streakLen++
        if (streakLen == agree && now - lastFired >= refractoryMs) { lastFired = now; return label }
        return null
    }

    fun reset() { frames.clear(); streak = null; streakLen = 0 }
}

/** Keeps the last couple of seconds of sonar frames so a camera-labelled moment can be cut out of them. */
class SonarHistory(private val keepMs: Long = 2_500) {
    private val frames = ArrayDeque<Pair<Long, FloatArray>>()
    @Synchronized fun add(now: Long, f: FloatArray) { frames.addLast(now to f); while (frames.isNotEmpty() && now - frames.first().first > keepMs) frames.removeFirst() }
    @Synchronized fun window(end: Long): FloatArray? = SonarFeatures.window(frames.toList(), end)
    @Synchronized fun clear() = frames.clear()
    /** True when the sonar has heard something in the last [ms] (any range at all), to tell a quiet room from a deaf mic. */
    @Synchronized fun lively(now: Long, ms: Long = 1_000) = frames.any { now - it.first <= ms && it.second[0] > 0.5f }
}

/** Names for the gestures Echo can learn: the camera's hand gestures that make clear sound. */
object LearnedNames {
    val TEACHABLE = listOf("swipe_left" to "Swipe left", "swipe_right" to "Swipe right", "push" to "Push", "pull" to "Pull", "hold" to "Hold still", "swipe_up" to "Swipe up", "swipe_down" to "Swipe down")
    val DEFAULT = listOf("swipe_left", "swipe_right", "push", "pull")
    fun label(kind: String) = TEACHABLE.firstOrNull { it.first == kind }?.second ?: kind.replace('_', ' ')
}
