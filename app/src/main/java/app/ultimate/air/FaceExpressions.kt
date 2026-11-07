package app.ultimate.air

/**
 * Turns MediaPipe's face blendshape scores (0..1 per muscle movement, such as "browInnerUp" or "jawOpen") into
 * expressions: eyebrows up, eyebrows down, smile, mouth open, pucker, puffed cheeks, wink and blink.
 *
 * Faces differ at rest (some people's brows sit high, some mouths rest open), so each score is measured against your
 * own relaxed face: a slow average that only learns while that expression is not being made. An expression fires once
 * when it rises past its "on" level and can fire again only after it falls below its "off" level and a short pause, so
 * holding your eyebrows up is one event, not thirty.
 */
class FaceExpressions(private val refractoryMs: Long = 600) {
    data class Expression(val kind: String, val label: String)

    /** How strongly each expression is being made right now, after removing your resting face (0..1). */
    data class Reading(val levels: Map<String, Float>, val fired: Expression?)

    private val baseline = HashMap<String, Float>()
    private val active = HashSet<String>()
    private val lastFired = HashMap<String, Long>()
    private var frames = 0

    fun feed(scores: Map<String, Float>, now: Long): Reading {
        fun s(name: String) = scores[name] ?: 0f
        val raw = mapOf(
            "brows_up" to maxOf(s("browInnerUp"), (s("browOuterUpLeft") + s("browOuterUpRight")) / 2),
            "brows_down" to (s("browDownLeft") + s("browDownRight")) / 2,
            "smile" to (s("mouthSmileLeft") + s("mouthSmileRight")) / 2,
            "mouth_open" to s("jawOpen"),
            "pucker" to s("mouthPucker"),
            "cheek_puff" to s("cheekPuff"),
            // One eye closed and the other open; which side is which depends on the camera's mirroring, so it is one gesture.
            "wink" to maxOf(s("eyeBlinkLeft") - s("eyeBlinkRight"), s("eyeBlinkRight") - s("eyeBlinkLeft")).coerceAtLeast(0f),
            "blink" to minOf(s("eyeBlinkLeft"), s("eyeBlinkRight")),
            "tongue_out" to s("tongueOut"),
        )
        frames++
        val levels = HashMap<String, Float>()
        var fired: Expression? = null
        for ((kind, value) in raw) {
            // Blinks and winks are judged on the raw score: the eyes' resting state is reliably open.
            val rest = if (kind == "wink" || kind == "blink" || kind == "tongue_out") 0f else baseline[kind] ?: value
            val level = ((value - rest) / (1f - rest).coerceAtLeast(0.2f)).coerceIn(0f, 1f)
            levels[kind] = level
            val (on, off) = THRESHOLDS.getValue(kind)
            if (kind in active) {
                if (level < off) active -= kind
            } else {
                // Learn the resting face only while not making the expression, so holding it doesn't become "normal".
                if (level < off && kind != "wink" && kind != "blink") baseline[kind] = if (kind in baseline) baseline.getValue(kind) * 0.97f + value * 0.03f else value
                // Wait for a short warm-up so the first frames, before the resting face is known, don't fire.
                if (frames > WARMUP && level >= on && now - (lastFired[kind] ?: Long.MIN_VALUE / 2) >= refractoryMs) {
                    active += kind; lastFired[kind] = now
                    // A blink closes both eyes, so it must not also count as a wink; prefer the stronger, rarer signal.
                    if (fired == null || kind == "wink") fired = Expression(kind, LABELS.getValue(kind))
                }
            }
        }
        if (fired?.kind == "blink" && "wink" in active) fired = null
        return Reading(levels, fired)
    }

    fun reset() { baseline.clear(); active.clear(); lastFired.clear(); frames = 0 }

    companion object {
        const val WARMUP = 10
        /** On and off levels per expression; the gap between them stops flicker around a single threshold. */
        val THRESHOLDS = mapOf(
            "brows_up" to (0.45f to 0.25f), "brows_down" to (0.45f to 0.25f), "smile" to (0.55f to 0.3f),
            "mouth_open" to (0.5f to 0.25f), "pucker" to (0.55f to 0.3f), "cheek_puff" to (0.5f to 0.25f),
            "wink" to (0.5f to 0.25f), "blink" to (0.6f to 0.3f), "tongue_out" to (0.6f to 0.3f),
        )
        val LABELS = mapOf(
            "brows_up" to "Eyebrows up", "brows_down" to "Eyebrows down", "smile" to "Smile", "mouth_open" to "Mouth open",
            "pucker" to "Pucker", "cheek_puff" to "Puffed cheeks", "wink" to "Wink", "blink" to "Blink", "tongue_out" to "Tongue out",
        )
        /** The face gestures Air control can map to actions, in display order. */
        val ALL = LABELS.map { (k, v) -> "face_$k" to v }
    }
}
