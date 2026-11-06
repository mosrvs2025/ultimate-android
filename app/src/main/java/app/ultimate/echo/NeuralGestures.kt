package app.ultimate.echo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * A small neural network that learns your gestures from sonar, trained on the phone from the examples the camera
 * labels. One hidden layer of [hidden] units (tanh), then one output per gesture (softmax). A few thousand numbers in
 * all: it trains in a second or two and runs in microseconds, with nothing downloaded and nothing sent anywhere.
 */
class TinyNet(
    override val labels: List<String>,
    private val inDim: Int,
    private val hidden: Int,
    private val mean: FloatArray,
    private val scale: FloatArray,
    private val w1: FloatArray, private val b1: FloatArray,
    private val w2: FloatArray, private val b2: FloatArray,
) : GestureClassifier {
    private val k = labels.size

    /** Probabilities for every label, in [labels] order. */
    fun probabilities(x: FloatArray): FloatArray? {
        if (x.size != inDim) return null
        return forward(x, FloatArray(hidden), FloatArray(k))
    }

    private fun forward(x: FloatArray, h: FloatArray, p: FloatArray): FloatArray {
        for (j in 0 until hidden) {
            var s = b1[j]; val o = j * inDim
            for (i in 0 until inDim) s += w1[o + i] * ((x[i] - mean[i]) / scale[i])
            h[j] = tanh(s)
        }
        var mx = Float.NEGATIVE_INFINITY
        for (c in 0 until k) { var s = b2[c]; val o = c * hidden; for (j in 0 until hidden) s += w2[o + j] * h[j]; p[c] = s; if (s > mx) mx = s }
        var sum = 0f
        for (c in 0 until k) { p[c] = exp(p[c] - mx); sum += p[c] }
        for (c in 0 until k) p[c] /= sum
        return p
    }

    override fun predict(x: FloatArray): GestureModel.Guess? {
        val p = probabilities(x) ?: return null
        var best = 0; for (c in 1 until k) if (p[c] > p[best]) best = c
        return GestureModel.Guess(labels[best], p[best])
    }

    fun toJson(): JSONObject = JSONObject().put("labels", JSONArray(labels)).put("in", inDim).put("hidden", hidden)
        .put("mean", arr(mean)).put("scale", arr(scale)).put("w1", arr(w1)).put("b1", arr(b1)).put("w2", arr(w2)).put("b2", arr(b2))

    companion object {
        private fun arr(a: FloatArray) = JSONArray().apply { a.forEach { put(it.toDouble()) } }
        private fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }

        fun fromJson(o: JSONObject): TinyNet? = runCatching {
            val l = o.getJSONArray("labels")
            TinyNet((0 until l.length()).map { l.getString(it) }, o.getInt("in"), o.getInt("hidden"),
                floats(o.getJSONArray("mean")), floats(o.getJSONArray("scale")), floats(o.getJSONArray("w1")), floats(o.getJSONArray("b1")),
                floats(o.getJSONArray("w2")), floats(o.getJSONArray("b2")))
        }.getOrNull()

        /**
         * Trains on [samples] with Adam, small batches, light weight decay and each gesture weighted equally (so the many
         * "nothing" examples don't drown out the rare gestures). [seed] makes a run repeatable.
         */
        fun train(samples: List<GestureModel.Sample>, hidden: Int = 32, epochs: Int = 120, lr: Float = 0.01f, l2: Float = 1e-4f, seed: Int = 7): TinyNet {
            val labels = samples.map { it.label }.distinct().sorted()
            val k = labels.size; val d = samples.first().x.size
            val mean = FloatArray(d); val scale = FloatArray(d)
            for (i in 0 until d) {
                val v = samples.map { it.x[i] }; val m = v.average().toFloat(); mean[i] = m
                scale[i] = sqrt(v.map { (it - m) * (it - m) }.average()).toFloat().coerceAtLeast(1e-3f)
            }
            val rnd = Random(seed)
            fun init(n: Int, fanIn: Int, fanOut: Int): FloatArray { val lim = sqrt(6f / (fanIn + fanOut)); return FloatArray(n) { (rnd.nextFloat() * 2 - 1) * lim } }
            val w1 = init(hidden * d, d, hidden); val b1 = FloatArray(hidden); val w2 = init(k * hidden, hidden, k); val b2 = FloatArray(k)
            val params = listOf(w1, b1, w2, b2)
            val m1 = params.map { FloatArray(it.size) }; val v1 = params.map { FloatArray(it.size) }
            val grads = params.map { FloatArray(it.size) }
            val counts = labels.associateWith { l -> samples.count { it.label == l } }
            val weight = labels.map { l -> samples.size.toFloat() / (k * counts.getValue(l)) }
            val y = samples.map { labels.indexOf(it.label) }
            val z = samples.map { s -> FloatArray(d) { (s.x[it] - mean[it]) / scale[it] } }
            val h = FloatArray(hidden); val p = FloatArray(k); val dh = FloatArray(hidden)
            var step = 0
            val order = samples.indices.toMutableList()
            repeat(epochs) {
                order.shuffle(rnd)
                for (batch in order.chunked(16)) {
                    grads.forEach { it.fill(0f) }
                    for (n in batch) {
                        val x = z[n]
                        for (j in 0 until hidden) { var s = b1[j]; val o = j * d; for (i in 0 until d) s += w1[o + i] * x[i]; h[j] = tanh(s) }
                        var mx = Float.NEGATIVE_INFINITY
                        for (c in 0 until k) { var s = b2[c]; val o = c * hidden; for (j in 0 until hidden) s += w2[o + j] * h[j]; p[c] = s; if (s > mx) mx = s }
                        var sum = 0f; for (c in 0 until k) { p[c] = exp(p[c] - mx); sum += p[c] }
                        val wgt = weight[y[n]] / batch.size
                        dh.fill(0f)
                        for (c in 0 until k) {
                            val g = (p[c] / sum - if (c == y[n]) 1f else 0f) * wgt
                            grads[3][c] += g; val o = c * hidden
                            for (j in 0 until hidden) { grads[2][o + j] += g * h[j]; dh[j] += g * w2[o + j] }
                        }
                        for (j in 0 until hidden) {
                            val g = dh[j] * (1 - h[j] * h[j]); grads[1][j] += g; val o = j * d
                            for (i in 0 until d) grads[0][o + i] += g * x[i]
                        }
                    }
                    step++
                    val c1 = 1 - Math.pow(0.9, step.toDouble()).toFloat(); val c2 = 1 - Math.pow(0.999, step.toDouble()).toFloat()
                    for (q in params.indices) {
                        val w = params[q]; val g = grads[q]; val m = m1[q]; val v = v1[q]
                        for (i in w.indices) {
                            val gi = g[i] + if (q % 2 == 0) l2 * w[i] else 0f
                            m[i] = 0.9f * m[i] + 0.1f * gi; v[i] = 0.999f * v[i] + 0.001f * gi * gi
                            w[i] -= lr * (m[i] / c1) / (sqrt(v[i] / c2) + 1e-8f)
                        }
                    }
                }
            }
            return TinyNet(labels, d, hidden, mean, scale, w1, b1, w2, b2)
        }
    }
}

/**
 * What Echo knows: your examples, and a network trained on them once there are enough. It uses the network only when it
 * did better than matching examples on examples held back from training; otherwise it matches examples. [report] says
 * which, and why, in numbers you can check.
 */
class GestureBrain(val examples: GestureModel, val net: TinyNet?, val report: Report) : GestureClassifier {
    /** Scores on held-back examples; [netAccuracy] is null while there are too few examples to train fairly. */
    data class Report(val examples: Int, val matchAccuracy: Double, val netAccuracy: Double?, val usesNet: Boolean, val note: String)

    override val labels get() = examples.labels
    val usesNet get() = net != null && report.usesNet
    override fun predict(x: FloatArray) = if (usesNet) net!!.predict(x) else examples.predict(x)

    companion object {
        const val MIN_EXAMPLES = 120
        const val MIN_PER_GESTURE = 12
        /** Most examples kept per gesture; the oldest go first, so it follows how you gesture now. */
        const val KEEP_PER_LABEL = 150

        /** Splits the examples (the same way every time), scores both learners on the part neither trained on, picks one. */
        fun build(examples: GestureModel, seed: Int = 11): GestureBrain {
            val s = examples.samples
            val short = examples.labels.filter { it != GestureModel.NONE }.filter { l -> examples.count(l) < MIN_PER_GESTURE }
            if (s.size < MIN_EXAMPLES || short.isNotEmpty()) {
                val loo = examples.accuracy().values.average().takeIf { !it.isNaN() } ?: 0.0
                val need = maxOf(MIN_EXAMPLES - s.size, 0)
                val note = when {
                    need > 0 -> "Matching your examples. The neural network starts after $need more examples."
                    else -> "Matching your examples. The neural network needs at least $MIN_PER_GESTURE of each gesture (${short.joinToString { LearnedNames.label(it) }})."
                }
                return GestureBrain(examples, null, Report(s.size, loo, null, false, note))
            }
            val rnd = Random(seed)
            val test = HashSet<Int>()
            examples.labels.forEach { l -> val idx = s.indices.filter { s[it].label == l }.shuffled(rnd); test += idx.take(maxOf(2, idx.size / 5)) }
            val train = s.indices.filter { it !in test }.map { s[it] }
            val held = test.map { s[it] }
            val trialNet = TinyNet.train(train)
            val trialMatch = GestureModel(train)
            fun acc(c: GestureClassifier) = held.count { c.predict(it.x)?.label == it.label }.toDouble() / held.size
            val netAcc = acc(trialNet); val matchAcc = acc(trialMatch)
            val useNet = netAcc >= matchAcc
            val net = if (useNet) TinyNet.train(s) else null
            val note = if (useNet) "Using the neural network: %.0f%% on held-back examples, against %.0f%% for matching examples.".format(netAcc * 100, matchAcc * 100)
                else "Matching examples (%.0f%%) still beats the neural network (%.0f%%); it will try again as you add examples.".format(matchAcc * 100, netAcc * 100)
            return GestureBrain(examples, net, Report(s.size, matchAcc, netAcc, useNet, note))
        }

        /** Adds [new] to [old], replacing [replace] labels entirely, and keeps at most [KEEP_PER_LABEL] of each. */
        fun merge(old: GestureModel?, new: List<GestureModel.Sample>, replace: Collection<String> = emptyList()): GestureModel {
            val all = old?.samples.orEmpty().filter { it.label !in replace } + new
            return GestureModel(all.groupBy { it.label }.flatMap { (_, v) -> v.takeLast(KEEP_PER_LABEL) })
        }

        private fun file(c: Context) = File(c.filesDir, "echo_learned_net.json")

        fun load(c: Context): GestureBrain? {
            val ex = GestureModel.load(c) ?: return null
            val saved = runCatching { JSONObject(file(c).readText()) }.getOrNull()
            val net = saved?.optJSONObject("net")?.let(TinyNet::fromJson)?.takeIf { it.labels.sorted() == ex.labels.sorted() }
            val r = saved?.optJSONObject("report")
            val report = r?.let { Report(it.getInt("examples"), it.getDouble("match"), if (it.has("net")) it.getDouble("net") else null, it.getBoolean("usesNet"), it.getString("note")) }
            // A report saved for a different set of examples is stale; score again.
            return if (report == null || report.examples != ex.samples.size || (report.usesNet && net == null)) build(ex) else GestureBrain(ex, net, report)
        }

        fun save(c: Context, b: GestureBrain) {
            GestureModel.save(c, b.examples)
            val r = b.report
            file(c).writeText(JSONObject().put("report", JSONObject().put("examples", r.examples).put("match", r.matchAccuracy)
                .apply { r.netAccuracy?.let { put("net", it) } }.put("usesNet", r.usesNet).put("note", r.note))
                .apply { b.net?.let { put("net", it.toJson()) } }.toString())
        }

        fun forget(c: Context) { GestureModel.forget(c); file(c).delete() }
    }
}
