package app.ultimate.worldscan

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.hypot
import kotlin.math.sqrt

/** One camera pose from motion tracking: where the phone was and which way it faced, in the walk's own frame (metres, Y up), at [tNs]. */
class PosePoint(val tNs: Long, val x: Float, val y: Float, val z: Float, val qx: Float, val qy: Float, val qz: Float, val qw: Float, val tracking: TrackingQuality) {
    /** The unit vector the camera looks along (its -Z axis) in the walk's frame. */
    fun forward(): FloatArray = floatArrayOf(-2f * (qx * qz + qw * qy), -2f * (qy * qz - qw * qx), -(1f - 2f * (qx * qx + qy * qy)))
    /** Heading in the walk's own horizontal plane, degrees clockwise seen from above from the walk's -Z axis. */
    fun headingDeg(): Float { val f = forward(); return Math.toDegrees(kotlin.math.atan2(f[0].toDouble(), -f[2].toDouble())).toFloat() }
}

/** A pose between two recorded ones, with how long the gap around it was. */
class PoseAt(val x: Float, val y: Float, val z: Float, val gapNs: Long)

/**
 * The path of the phone through one walk: poses in time order, with measures that say how trustworthy the walk is. Poses at [tracking]
 * `NOT_TRACKING` are kept (so a loss is visible in a replay) but never used to place anything. Pure, so it is tested.
 */
class WalkTrack {
    private val list = ArrayList<PosePoint>()
    val points: List<PosePoint> get() = list
    /** Times a pose appeared to jump (further than a person can move between two poses): motion tracking lost its place and found it elsewhere. */
    var jumps = 0; private set

    /** Adds a pose. Returns false for a pose out of time order or with a non-finite number. */
    fun add(p: PosePoint): Boolean {
        if (!(p.x.isFinite() && p.y.isFinite() && p.z.isFinite() && p.qx.isFinite() && p.qy.isFinite() && p.qz.isFinite() && p.qw.isFinite())) return false
        val last = list.lastOrNull()
        if (last != null) {
            if (p.tNs < last.tNs) return false
            if (last.tracking == TrackingQuality.TRACKING && p.tracking == TrackingQuality.TRACKING) {
                val dt = (p.tNs - last.tNs) / 1e9
                if (dt > 0 && dist(last, p) / dt > MAX_PLAUSIBLE_SPEED) jumps++
            }
        }
        list.add(p); return true
    }

    private fun dist(a: PosePoint, b: PosePoint) = sqrt(((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)).toDouble())

    /** Where the phone was at [tNs], from the two tracked poses either side of it; null when it was not tracked there or the gap is longer than [maxGapNs]. */
    fun at(tNs: Long, maxGapNs: Long = 1_500_000_000L): PoseAt? {
        if (list.isEmpty() || tNs < list.first().tNs || tNs > list.last().tNs) return null
        var lo = 0; var hi = list.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (list[mid].tNs <= tNs) lo = mid else hi = mid }
        val a = list[lo]; val b = list[hi]
        if (a.tracking != TrackingQuality.TRACKING || b.tracking != TrackingQuality.TRACKING) return null
        val gap = b.tNs - a.tNs; if (gap > maxGapNs) return null
        val f = if (gap <= 0L) 0f else ((tNs - a.tNs).toDouble() / gap).toFloat()
        return PoseAt(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f, gap)
    }

    /** Metres walked, along the horizontal path, counting only steps between two tracked poses and ignoring jumps. */
    val lengthM: Double
        get() {
            var total = 0.0
            for (i in 1 until list.size) {
                val a = list[i - 1]; val b = list[i]
                if (a.tracking != TrackingQuality.TRACKING || b.tracking != TrackingQuality.TRACKING) continue
                val dt = (b.tNs - a.tNs) / 1e9; val d = hypot((b.x - a.x).toDouble(), (b.z - a.z).toDouble())
                if (dt > 0 && d / dt > MAX_PLAUSIBLE_SPEED) continue
                total += d
            }
            return total
        }
    val durationS: Double get() = if (list.size < 2) 0.0 else (list.last().tNs - list.first().tNs) / 1e9
    /** Share of the walk's time during which motion tracking was working (0..1). */
    val trackedShare: Double
        get() {
            var tracked = 0L; var all = 0L
            for (i in 1 until list.size) { val dt = list[i].tNs - list[i - 1].tNs; all += dt; if (list[i - 1].tracking == TrackingQuality.TRACKING) tracked += dt }
            return if (all <= 0L) 0.0 else tracked.toDouble() / all
        }
    /** The longest stretch with no tracking, in seconds. */
    val longestLossS: Double
        get() {
            var best = 0L; var start = -1L
            for (p in list) {
                if (p.tracking != TrackingQuality.TRACKING) { if (start < 0) start = p.tNs } else if (start >= 0) { best = maxOf(best, p.tNs - start); start = -1 }
            }
            if (start >= 0 && list.isNotEmpty()) best = maxOf(best, list.last().tNs - start)
            return best / 1e9
        }
    /** Speed at the 95th percentile of tracked steps (m/s): a sanity number, a walk that says 9 m/s was not a walk. */
    val speedP95: Double
        get() {
            val v = ArrayList<Double>()
            for (i in 1 until list.size) { val a = list[i - 1]; val b = list[i]; val dt = (b.tNs - a.tNs) / 1e9; if (dt > 0.02 && a.tracking == TrackingQuality.TRACKING && b.tracking == TrackingQuality.TRACKING) v += dist(a, b) / dt }
            if (v.isEmpty()) return 0.0; v.sort(); return v[((v.size - 1) * 0.95).toInt()]
        }
    /** Horizontal extent of the tracked path, metres: width and depth of its bounding box. */
    fun extent(): Pair<Double, Double> {
        val t = list.filter { it.tracking == TrackingQuality.TRACKING }; if (t.isEmpty()) return 0.0 to 0.0
        return (t.maxOf { it.x } - t.minOf { it.x }).toDouble() to (t.maxOf { it.z } - t.minOf { it.z }).toDouble()
    }

    /** A copy with the times written on another clock (see [ClockSync]); poses whose time cannot be converted are dropped. */
    fun withTimes(convert: (Long) -> Long?): WalkTrack {
        val out = WalkTrack()
        for (p in list) { val t = convert(p.tNs) ?: continue; out.add(PosePoint(t, p.x, p.y, p.z, p.qx, p.qy, p.qz, p.qw, p.tracking)) }
        return out
    }

    fun write(out: OutputStream) {
        val d = DataOutputStream(out); d.writeInt(MAGIC); d.writeInt(list.size)
        for (p in list) { d.writeLong(p.tNs); d.writeFloat(p.x); d.writeFloat(p.y); d.writeFloat(p.z); d.writeFloat(p.qx); d.writeFloat(p.qy); d.writeFloat(p.qz); d.writeFloat(p.qw); d.writeByte(p.tracking.ordinal) }
        d.flush()
    }

    companion object {
        const val MAX_PLAUSIBLE_SPEED = 6.0     // m/s: faster than a sprint between two poses means tracking jumped
        private const val MAGIC = 0x57545231      // "WTR1"
        fun read(input: InputStream): WalkTrack {
            val d = DataInputStream(input); require(d.readInt() == MAGIC) { "Not a walk track" }
            val n = d.readInt(); require(n in 0..5_000_000) { "Walk track is damaged" }
            val t = WalkTrack(); val q = TrackingQuality.values()
            repeat(n) { t.add(PosePoint(d.readLong(), d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat(), q[d.readUnsignedByte().coerceIn(0, 2)])) }
            return t
        }
    }
}
