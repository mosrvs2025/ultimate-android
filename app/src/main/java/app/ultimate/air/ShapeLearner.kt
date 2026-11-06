package app.ultimate.air

import app.ultimate.echo.GestureModel
import app.ultimate.echo.TinyNet
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Air mode learns your hand while you use it, with no extra steps. Every use is a lesson:
 * - a shortcut you held all the way and didn't undo: that shape, made your way, means it;
 * - a hold you started and then moved away from: it looked like the shape, but you didn't mean it ("none");
 * - now and then, your hand while you're simply pointing around: "none".
 * A small neural network ([TinyNet]) is retrained on these every so often. As it gets sure of a shape it has seen you
 * confirm many times, that shape's hold shrinks from 1 s toward 0.3 s; shapes it thinks you don't mean never start a
 * ring at all. Pure (apart from JSON), so it is tested. Nothing leaves the phone.
 */
class ShapeLearner(private val perLabel: Int = 120) {
    private val samples = LinkedHashMap<String, ArrayDeque<FloatArray>>()
    private var net: TinyNet? = null
    private var sinceTrain = 0
    /** How many times you confirmed each shape: trust grows with it. */
    val confirmed = HashMap<String, Int>()

    fun add(label: String, x: FloatArray) {
        val q = samples.getOrPut(label) { ArrayDeque() }
        q.addLast(x); while (q.size > perLabel) q.removeFirst()
        if (label != NONE) confirmed[label] = (confirmed[label] ?: 0) + 1
        sinceTrain++
    }
    /** A shortcut turned out to be a mistake: take back its confirmation and learn the shape as "none". */
    fun undo(label: String, x: FloatArray) {
        confirmed[label] = ((confirmed[label] ?: 0) - 2).coerceAtLeast(0)
        add(NONE, x)
    }

    /** Whether enough is new to be worth retraining (call [train] off the main thread). */
    fun due() = sinceTrain >= 8 && samples.size >= 2 && samples.values.sumOf { it.size } >= 12
    fun train() {
        val all = samples.flatMap { (l, q) -> q.map { GestureModel.Sample(l, it) } }
        if (all.map { it.label }.distinct().size < 2) return
        net = TinyNet.train(all, hidden = 16, epochs = 80); sinceTrain = 0
    }

    /** How sure the network is that this hand means [sign] (0..1), or null before it has learned anything. */
    fun belief(sign: String, x: FloatArray): Float? {
        val n = net ?: return null
        val p = n.probabilities(x) ?: return null
        val i = n.labels.indexOf(sign); return if (i < 0) null else p[i]
    }

    /**
     * How long [sign] must be held now: 1 s to begin with, down to 0.3 s once the network is sure and you've confirmed
     * it often; null when the network is sure you don't mean it (no ring starts).
     */
    fun holdMs(sign: String, x: FloatArray): Long? {
        val n = net ?: return FULL
        val p = n.probabilities(x) ?: return FULL
        val none = n.labels.indexOf(NONE).let { if (it < 0) 0f else p[it] }
        if (none > 0.8f) return null
        val b = belief(sign, x) ?: return FULL
        val sure = ((b - 0.6f) / 0.35f).coerceIn(0f, 1f)
        val experience = ((confirmed[sign] ?: 0) / 15f).coerceIn(0f, 1f)
        return (FULL - (FULL - FASTEST) * sure * experience).toLong()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("samples", JSONObject().apply { samples.forEach { (l, q) -> put(l, JSONArray().apply { q.forEach { put(JSONArray(it.map(Float::toDouble))) } }) } })
        put("confirmed", JSONObject(confirmed as Map<*, *>))
        net?.let { put("net", it.toJson()) }
    }

    companion object {
        const val NONE = "none"
        const val FULL = 1_000L
        const val FASTEST = 300L

        /** The hand's shape, not where it is: 21 points relative to the wrist, divided by the hand's size. */
        fun features(points: List<Pair<Float, Float>>): FloatArray {
            val (wx, wy) = points[0]
            val size = hypot(points[9].first - wx, points[9].second - wy).coerceAtLeast(1e-3f)
            return FloatArray(points.size * 2) { i -> val p = points[i / 2]; if (i % 2 == 0) (p.first - wx) / size else (p.second - wy) / size }
        }

        fun fromJson(o: JSONObject): ShapeLearner = ShapeLearner().apply {
            o.optJSONObject("samples")?.let { s -> s.keys().forEach { l -> val a = s.getJSONArray(l)
                samples[l] = ArrayDeque((0 until a.length()).map { i -> val v = a.getJSONArray(i); FloatArray(v.length()) { v.getDouble(it).toFloat() } }) } }
            o.optJSONObject("confirmed")?.let { c -> c.keys().forEach { confirmed[it] = c.getInt(it) } }
            net = o.optJSONObject("net")?.let { TinyNet.fromJson(it) }
        }
    }
}
