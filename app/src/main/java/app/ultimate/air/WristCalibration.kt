package app.ultimate.air

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Calibrates the watch's wrist gestures from the wearer's own motion. Watches differ in which way their axes point, and
 * people flick differently, so instead of guessing which way is "up", the phone watches a few of each gesture and works
 * it out: which axis each one turns about, in which direction, and how hard this person flicks.
 *
 * A flick is a fast turn followed by a rebound as the wrist stops. The rebound can be as strong as the flick, which is
 * how a flick up can read as a flick down. So a gesture's direction is the direction of its first strong turn, not its
 * strongest one.
 */
object WristCalibration {
    /** One watch sensor reading: gyroscope (rad/s) and linear acceleration (m/s²), at [t] ms. */
    data class Sample(val t: Long, val gx: Float, val gy: Float, val gz: Float, val ax: Float, val ay: Float, val az: Float) {
        val turn get() = sqrt(gx * gx + gy * gy + gz * gz)
        val jolt get() = sqrt(ax * ax + ay * ay + az * az)
    }

    /** Which axis ('x', 'y' or 'z') a gesture turns about, which way (+1 or -1), and how fast its first strong turn is. */
    data class Signature(val axis: Char, val sign: Int, val peak: Float) { val key get() = "$axis${if (sign > 0) "+" else "-"}" }

    /** Turning slower than this (rad/s) is the wrist at rest, between gestures. */
    const val REST = 1.5f

    /** Splits a stream into bursts of turning, each a gesture, joined across pauses shorter than [gapMs]. */
    fun bursts(samples: List<Sample>, gapMs: Long = 150): List<List<Sample>> {
        val out = ArrayList<List<Sample>>(); var cur = ArrayList<Sample>(); var lastActive = Long.MIN_VALUE / 2
        for (s in samples) {
            if (s.turn >= REST) {
                if (cur.isNotEmpty() && s.t - lastActive > gapMs) { out += cur; cur = ArrayList() }
                cur += s; lastActive = s.t
            } else if (cur.isNotEmpty() && s.t - lastActive <= gapMs) cur += s
        }
        if (cur.isNotEmpty()) out += cur
        return out.filter { b -> b.size >= 2 && b.maxOf { it.turn } >= REST * 1.5f }
    }

    /** The first moment in [burst] that turns at least 60% as fast as its fastest, and the axis that leads then. */
    fun signature(burst: List<Sample>): Signature? {
        val top = burst.maxOfOrNull { it.turn } ?: return null
        val s = burst.firstOrNull { it.turn >= 0.6f * top } ?: return null
        val axes = listOf('x' to s.gx, 'y' to s.gy, 'z' to s.gz)
        val (axis, v) = axes.maxBy { abs(it.second) }
        // The peak on that axis within the first turn (before it reverses), so the strength is the flick, not the rebound.
        val sign = if (v > 0) 1 else -1
        val peak = burst.dropWhile { it !== s }.takeWhile { component(it, axis) * sign > 0 }.maxOfOrNull { abs(component(it, axis)) } ?: abs(v)
        return Signature(axis, sign, peak)
    }

    private fun component(s: Sample, axis: Char) = when (axis) { 'x' -> s.gx; 'y' -> s.gy; else -> s.gz }

    /** What the watch should use: which axis direction means which gesture, the twist's axis, and the flick speed to require. */
    data class Config(val directions: Map<String, String>, val twistAxis: Char?, val flickRate: Float)

    /**
     * Builds a config from taught examples (gesture to its signatures). Each gesture takes the axis and direction most of its
     * examples agree on; a direction claimed by two gestures goes to the one with more agreeing examples. The speed to
     * require is a little over half the gentlest gesture's typical speed, so every taught gesture clears it comfortably.
     */
    fun build(taught: Map<String, List<Signature>>): Config {
        val votes = taught.mapNotNull { (kind, sigs) ->
            if (sigs.isEmpty()) return@mapNotNull null
            val keyOf: (Signature) -> String = if (kind == "twist") { s -> s.axis.toString() } else { s -> s.key }
            val best = sigs.groupingBy(keyOf).eachCount().maxBy { it.value }
            Triple(kind, best.key, best.value)
        }.sortedByDescending { it.third }
        val directions = LinkedHashMap<String, String>(); var twist: Char? = null
        for ((kind, key, _) in votes) {
            if (kind == "twist") { if (twist == null && directions.keys.none { it[0] == key[0] }) twist = key[0] }
            else if (key !in directions && key[0] != twist) directions[key] = kind
        }
        val typical = taught.filterKeys { it != "twist" }.values.filter { it.isNotEmpty() }.map { s -> s.map { it.peak }.sorted()[s.size / 2] }
        val rate = ((typical.minOrNull() ?: 7.5f) * 0.6f).coerceIn(2.5f, 8f)
        return Config(directions, twist, rate)
    }

    /** "config:rate=4.20;twist=y;x+=flick_up;x-=flick_down;..." for the watch. */
    fun encode(c: Config): String = buildString {
        append("config:rate=").append("%.2f".format(java.util.Locale.ROOT, c.flickRate))
        c.twistAxis?.let { append(";twist=").append(it) }
        c.directions.forEach { (k, v) -> append(';').append(k).append('=').append(v) }
    }

    /** Parses the watch's "sensors:" message (frames "dtMs,gx,gy,gz,ax,ay,az" joined by '|'), timing them so the last is [now]. */
    fun parseSensors(text: String, now: Long): List<Sample> {
        val body = text.removePrefix("sensors:").takeIf { it != text } ?: return emptyList()
        val frames = body.split('|').mapNotNull { f -> f.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 7 } }
        val last = frames.lastOrNull()?.get(0) ?: return emptyList()
        return frames.map { v -> Sample(now - (last - v[0]).toLong(), v[1], v[2], v[3], v[4], v[5], v[6]) }
    }

    /** Plain words for a signature, for the screen. */
    fun describe(s: Signature) = "turning about ${s.axis.uppercaseChar()}, ${if (s.sign > 0) "positive" else "negative"}, %.1f rad/s".format(s.peak)
}
