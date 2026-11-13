package app.ultimate.worldscan

/**
 * Puts camera-frame timestamps on the same clock as the sensors and GPS.
 *
 * Android's sensors and location fixes are stamped with the elapsed-realtime clock (nanoseconds since boot). ARCore documents that the time
 * base of its frame timestamp "is specifically not defined": on some phones it is the same clock, on others it is the monotonic clock,
 * which stops while the phone sleeps and so differs by a large, constant amount. Assuming they match (as the first World Scan
 * foundation did) would put the camera seconds or days away from the GPS fixes it is meant to be compared with.
 *
 * So every frame is recorded with *two* times: the frame's own stamp and the elapsed-realtime clock read the moment the frame arrived.
 * Their difference is `offset + latency`, where the offset is what we want and the latency (the time the picture spent in the camera
 * pipeline before our code saw it) is never negative. The offset is therefore the *smallest* difference seen recently; this class keeps
 * the smallest difference per [bucketNs] of time and follows it as the two clocks drift apart. What it cannot know is how much of the
 * smallest latency is real, so the number it reports as [jitterNs] (typical difference above the minimum) is the honest error bar to
 * quote, and at walking speed (1.4 m/s) 30 ms of timing error is 4 cm: far below GPS noise.
 *
 * Pure, so it is tested against known offsets, drift and delays.
 */
class ClockSync(private val bucketNs: Long = 5_000_000_000L, private val keep: Int = 720) {
    private class Bucket(val index: Long) { var min = Long.MAX_VALUE; var n = 0; val diffs = LongArray(64); var d = 0 }
    private val buckets = ArrayDeque<Bucket>()
    var samples = 0L; private set
    private var jitterSum = 0.0; private var jitterN = 0L

    /** One frame: its own timestamp and the elapsed-realtime clock when it was received. */
    fun add(frameNanos: Long, receivedElapsedNanos: Long) {
        if (frameNanos < 0L || receivedElapsedNanos < 0L) return
        val diff = receivedElapsedNanos - frameNanos
        val index = frameNanos / bucketNs
        var b = buckets.lastOrNull()
        if (b == null || b.index != index) {
            if (b != null && index < b.index) return    // out of order: ignore rather than rewrite history
            b = Bucket(index); buckets.addLast(b); while (buckets.size > keep) buckets.removeFirst()
        }
        if (diff < b.min) b.min = diff
        b.n++; if (b.d < b.diffs.size) b.diffs[b.d++] = diff
        samples++
    }

    /** True once there is enough data to say anything. */
    val ready: Boolean get() = samples >= 30 && buckets.isNotEmpty()

    /** Elapsed-realtime minus frame time, following drift: the smallest difference around [frameNanos], steadied against one odd bucket. */
    fun offsetAt(frameNanos: Long): Long? {
        if (buckets.isEmpty()) return null
        val index = frameNanos / bucketNs
        // Median of the nearest three buckets' minima.
        val near = buckets.sortedBy { kotlin.math.abs(it.index - index) }.take(3).map { it.min }.sorted()
        return near[near.size / 2]
    }

    /** A frame timestamp on the elapsed-realtime clock (the sensors' and GPS's). Null until there is data. */
    fun toElapsed(frameNanos: Long): Long? = offsetAt(frameNanos)?.let { frameNanos + it }

    /** Typical latency above the minimum, in nanoseconds: the error bar of [toElapsed]. */
    val jitterNs: Long
        get() {
            var total = 0.0; var count = 0
            for (b in buckets) for (i in 0 until b.d) { total += (b.diffs[i] - b.min); count++ }
            return if (count == 0) 0L else (total / count).toLong()
        }

    /** Whether the frame stamps look like the elapsed-realtime clock itself (a small offset: pipeline latency only) or some other clock. */
    val sameClock: Boolean?
        get() {
            val last = buckets.lastOrNull() ?: return null
            return offsetAt(last.index * bucketNs)?.let { kotlin.math.abs(it) < 500_000_000L }
        }

    /** How far the offset moved between the first and last buckets, per hour: a sanity check that drift is small enough to follow. */
    val driftMsPerHour: Double?
        get() {
            val f = buckets.firstOrNull() ?: return null; val l = buckets.lastOrNull() ?: return null
            val dt = (l.index - f.index) * bucketNs / 1e9; if (dt < 60.0) return null
            return (l.min - f.min) / 1e6 / dt * 3600.0
        }

    fun summary(): String = if (!ready) "clock sync: collecting" else "clock sync: frame clock is ${if (sameClock == true) "the sensor clock" else "a different clock"}, jitter ${"%.1f".format(jitterNs / 1e6)} ms over $samples frames"
}
