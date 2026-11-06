package app.ultimate.air

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One place where every sense says what it thinks you're doing, and one decision. Each source gives evidence for an
 * intent ("pinch") with a confidence 0..1; evidence fades over [windowMs]. Sources are combined as independent
 * witnesses (1 - Π(1 - c)), so two half-sure senses that agree beat one that's guessing: the camera's "fingers nearly
 * together" plus the watch's "felt a tap" is a sure pinch, while either alone isn't. Pure, so it is tested.
 */
class IntentEngine(private val windowMs: Long = 250) {
    private val evidence = HashMap<String, HashMap<String, Pair<Float, Long>>>()

    /** [source] thinks [intent] is happening at [t] with [confidence]. A source's newer word replaces its older one. */
    fun offer(intent: String, source: String, confidence: Float, t: Long) {
        evidence.getOrPut(intent) { HashMap() }[source] = confidence.coerceIn(0f, 1f) to t
    }

    /** How sure all sources together are of [intent] at [now]; each source's word fades linearly over the window. */
    fun belief(intent: String, now: Long): Float {
        var none = 1f
        evidence[intent]?.values?.forEach { (c, t) ->
            val age = now - t
            if (age in 0..windowMs) none *= 1f - c * (1f - age.toFloat() / windowMs)
        }
        return 1f - none
    }

    /** Which sources currently back [intent], for showing why something happened. */
    fun witnesses(intent: String, now: Long) = evidence[intent].orEmpty().filter { (_, v) -> now - v.second in 0..windowMs && v.first > 0f }.keys
}

/**
 * Feels a finger tap through the watch: a thumb meeting a finger is a tiny, sharp jolt at the wrist while the wrist
 * itself barely turns. The threshold adapts to how noisy your wrist is right now, so walking or gesturing doesn't
 * count as taps, and there's a short pause after each one. Pure, so it is tested.
 */
class WristTap(private val sigmas: Float = 5f, private val minJolt: Float = 0.6f, private val maxTurn: Float = 1.5f, private val pauseMs: Long = 200) {
    private var base = Float.NaN
    private var noise = 0.3f
    private var last = Long.MIN_VALUE / 2

    /** Feeds one watch reading (jolt: acceleration size; turn: rotation speed); true when it was a tap. */
    fun feed(t: Long, jolt: Float, turn: Float): Boolean {
        if (base.isNaN()) { base = jolt; return false }
        val d = jolt - base
        val tap = d > maxOf(minJolt, sigmas * noise) && turn < maxTurn && t - last > pauseMs
        if (tap) { last = t; return true }
        // Learn the resting level and the noise only from ordinary readings, so taps don't raise the bar.
        base += 0.05f * d
        noise = sqrt(0.97f * noise * noise + 0.03f * d * d).coerceAtLeast(0.05f)
        return false
    }
}

/**
 * The pinch, decided from every sense: the camera's thumb-to-finger gap, and taps felt by the watch. With the camera
 * alone it behaves as before (closed below 0.25). A watch tap while the fingers are merely close (below [nearGap])
 * closes it at once, which catches pinches the camera can't quite see from across the room or at an angle. It stays
 * closed until the camera sees the fingers clearly apart. Returns the gap to give [AirPointer] (small when closed).
 */
class PinchFusion(private val nearGap: Float = 0.5f, private val openGap: Float = 0.45f) {
    val engine = IntentEngine()
    /** True while the watch is holding a pinch closed that the camera alone wouldn't have. */
    var confirmedByWatch = false; private set

    fun watchTap(t: Long) = engine.offer("pinch", "watch", 0.65f, t)

    fun feed(gap: Float, t: Long): Float {
        // The camera's own confidence: sure below 0.25, unsure by 0.5.
        engine.offer("pinch", "camera", ((nearGap - gap) / (nearGap - 0.25f)).coerceIn(0f, 1f) * 0.9f, t)
        if (confirmedByWatch && gap > openGap) confirmedByWatch = false
        if (!confirmedByWatch && gap in 0.25f..nearGap && engine.belief("pinch", t) >= 0.8f && "watch" in engine.witnesses("pinch", t)) confirmedByWatch = true
        return if (confirmedByWatch) minOf(gap, 0.2f) else gap
    }

    companion object { fun turn(gx: Float, gy: Float, gz: Float) = sqrt(gx * gx + gy * gy + gz * gz).let { if (it.isNaN()) 0f else abs(it) } }
}
