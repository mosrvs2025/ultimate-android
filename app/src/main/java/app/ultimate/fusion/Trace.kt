package app.ultimate.fusion

import app.ultimate.worldscan.ClockSync
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * A recording, as the fusion layer sees it: every sensor on ONE clock (seconds since the recording began), each kept as
 * measured. Nothing here is smoothed or corrected; analysis reads a trace and never rewrites it, so a better algorithm can
 * be run over an old recording and give a better answer.
 *
 * The on-disk form is the World Scan sensor log (one JSON object per line; see worldscan/WorldExport.kt `LogLines`), so every walk
 * ever recorded can be analysed, and anything recorded for analysis can be read by World Scan's tools.
 */
class Trace(
    val imu: Imu,
    val rotation: List<RotationSample>,
    val poses: List<PoseSample>,
    val fixes: List<FixSample>,
    val pressure: List<Scalar>,
    val heart: List<Scalar>,
    val notes: List<Note>,
    val poseClock: String,
) {
    val durationS: Double get() = maxOf(imu.t.lastOrNull() ?: 0.0, poses.lastOrNull()?.t ?: 0.0, fixes.lastOrNull()?.t ?: 0.0)
    val hasImu get() = imu.size > 20
    val hasPoses get() = poses.size > 5
    val hasFixes get() = fixes.size > 2

    /** Which kinds of sensor this recording has, for explanations. */
    fun sources(): List<String> = buildList { if (hasImu) add("inertial"); if (hasPoses) add("camera tracking"); if (hasFixes) add("GPS"); if (heart.isNotEmpty()) add("heart rate"); if (pressure.size > 20) add("barometer") }

    class Imu(val t: DoubleArray, val ax: FloatArray, val ay: FloatArray, val az: FloatArray, val gx: FloatArray, val gy: FloatArray, val gz: FloatArray, val hasGyro: Boolean) {
        val size: Int get() = t.size
        /** Median sample rate, Hz. */
        val rate: Double get() {
            if (size < 3) return 0.0
            val d = DoubleArray(size - 1) { t[it + 1] - t[it] }.also { it.sort() }
            val m = d[d.size / 2]
            return if (m > 0) 1.0 / m else 0.0
        }
    }
    data class RotationSample(val t: Double, val x: Float, val y: Float, val z: Float, val w: Float)
    data class PoseSample(val t: Double, val x: Float, val y: Float, val z: Float, val tracking: Boolean, val limited: Boolean)
    data class FixSample(val t: Double, val lat: Double, val lon: Double, val accuracyM: Double, val speed: Double?, val bearing: Double?)
    data class Scalar(val t: Double, val v: Double)
    data class Note(val t: Double, val kind: String, val detail: String)

    companion object {
        const val ACCELEROMETER = 1; const val GYROSCOPE = 4; const val ROTATION_VECTOR = 11; const val PRESSURE = 6

        /** Builds a trace from IMU arrays alone (a phone recording that had no camera or GPS). */
        fun fromImu(imu: Imu, fixes: List<FixSample> = emptyList(), heart: List<Scalar> = emptyList()) =
            Trace(imu, emptyList(), emptyList(), fixes, emptyList(), heart, emptyList(), "none")

        /**
         * Reads a World Scan style log. Camera-frame timestamps are mapped to the sensors' clock with [ClockSync] (the same code World Scan
         * uses) when the log is long enough for it to be trusted; otherwise the time each frame was received is used, which is later than the
         * truth by the camera pipeline's latency, and the trace says so in [poseClock].
         */
        fun read(lines: Sequence<String>): Trace = TraceBuilder().also { b -> for (l in lines) b.add(l) }.build()
    }
}

/**
 * Builds a [Trace] one log line at a time, so a recording that is still being written can be read incrementally: each pass parses only
 * the lines that are new and [build] is cheap. (Re-parsing the whole log on every refresh made the live view cost grow with the square
 * of the recording's length.)
 */
class TraceBuilder {
    private class RawPose(val tf: Long, val tr: Long, val x: Float, val y: Float, val z: Float, val k: String)
    private val sync = ClockSync()
    private val ia = ArrayList<Long>(); private val ax = ArrayList<Float>(); private val ay = ArrayList<Float>(); private val az = ArrayList<Float>()
    private val ga = ArrayList<Long>(); private val gx = ArrayList<Float>(); private val gy = ArrayList<Float>(); private val gz = ArrayList<Float>()
    private val rot = ArrayList<Pair<Long, FloatArray>>(); private val prs = ArrayList<Pair<Long, Double>>(); private val hr = ArrayList<Pair<Long, Double>>()
    private val poses = ArrayList<RawPose>()
    private val fixes = ArrayList<Triple<Long, DoubleArray, Pair<Double?, Double?>>>(); private val notes = ArrayList<Triple<Long, String, String>>()

    /** Lines accepted so far. */
    var accepted = 0; private set

    /** Adds one line; false if it was blank, not JSON, or not a record this reader knows (such a line is skipped, never fatal). */
    fun add(line: String): Boolean {
        if (line.isBlank()) return false
        val o = try { JSONObject(line) } catch (_: Exception) { return false }
        when (o.optString("type")) {
            "sensor" -> {
                val v = o.optJSONArray("v") ?: return false
                val t = o.optLong("t", -1); if (t < 0) return false
                fun f(i: Int) = v.optDouble(i, Double.NaN).toFloat()
                when (o.optInt("s")) {
                    Trace.ACCELEROMETER -> if (v.length() >= 3) { ia += t; ax += f(0); ay += f(1); az += f(2) }
                    Trace.GYROSCOPE -> if (v.length() >= 3) { ga += t; gx += f(0); gy += f(1); gz += f(2) }
                    Trace.ROTATION_VECTOR -> if (v.length() >= 3) {
                        val x = f(0); val y = f(1); val z = f(2)
                        val w = if (v.length() >= 4 && !f(3).isNaN()) f(3) else sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f))
                        rot += t to floatArrayOf(x, y, z, w)
                    }
                    Trace.PRESSURE -> if (v.length() >= 1) prs += t to v.optDouble(0)
                }
            }
            "location" -> {
                val t = o.optLong("t", -1); if (t < 0) return false
                val acc = o.optDouble("acc", Double.NaN)
                fixes += Triple(t, doubleArrayOf(o.optDouble("lat"), o.optDouble("lon"), if (acc.isNaN()) 30.0 else acc),
                    (if (o.isNull("speed")) null else o.optDouble("speed")) to (if (o.isNull("bearing")) null else o.optDouble("bearing")))
            }
            "pose" -> {
                val p = o.optJSONArray("p") ?: return false
                val tf = o.optLong("tf", -1); val tr = o.optLong("tr", -1); if (tf < 0 || tr < 0) return false
                sync.add(tf, tr)
                poses += RawPose(tf, tr, p.optDouble(0).toFloat(), p.optDouble(1).toFloat(), p.optDouble(2).toFloat(), o.optString("k", "TRACKING"))
            }
            "hr" -> { val t = o.optLong("t", -1); if (t >= 0) hr += t to o.optDouble("bpm", Double.NaN) }
            "note" -> notes += Triple(o.optLong("t", 0), o.optString("kind"), o.optString("detail"))
        }
        accepted++
        return true
    }

    fun build(): Trace {
        val useSync = sync.ready
        fun poseNs(p: RawPose): Long = if (useSync) (sync.toElapsed(p.tf) ?: p.tr) else p.tr
        val starts = ArrayList<Long>()
        ia.firstOrNull()?.let { starts += it }; ga.firstOrNull()?.let { starts += it }; fixes.firstOrNull()?.let { starts += it.first }
        poses.firstOrNull()?.let { starts += poseNs(it) }; rot.firstOrNull()?.let { starts += it.first }
        val t0 = starts.minOrNull() ?: 0L
        fun s(ns: Long) = (ns - t0) / 1e9

        // The accelerometer sets the rhythm; the gyroscope (a separate stream) is resampled onto its timestamps.
        val n = ia.size
        val t = DoubleArray(n) { s(ia[it]) }
        val gxo = FloatArray(n); val gyo = FloatArray(n); val gzo = FloatArray(n)
        var j = 0
        for (i in 0 until n) {
            if (ga.isEmpty()) break
            while (j < ga.size - 2 && ga[j + 1] < ia[i]) j++
            val span = (ga[minOf(j + 1, ga.size - 1)] - ga[j]).toDouble()
            val f = if (span <= 0) 0.0 else ((ia[i] - ga[j]) / span).coerceIn(0.0, 1.0)
            val k = minOf(j + 1, ga.size - 1)
            gxo[i] = (gx[j] + (gx[k] - gx[j]) * f).toFloat(); gyo[i] = (gy[j] + (gy[k] - gy[j]) * f).toFloat(); gzo[i] = (gz[j] + (gz[k] - gz[j]) * f).toFloat()
        }
        val imu = Trace.Imu(t, ax.toFloatArray(), ay.toFloatArray(), az.toFloatArray(), gxo, gyo, gzo, ga.isNotEmpty())
        return Trace(
            imu,
            rot.map { Trace.RotationSample(s(it.first), it.second[0], it.second[1], it.second[2], it.second[3]) },
            poses.map { Trace.PoseSample(s(poseNs(it)), it.x, it.y, it.z, it.k == "TRACKING", it.k == "LIMITED") },
            fixes.map { Trace.FixSample(s(it.first), it.second[0], it.second[1], it.second[2], it.third.first, it.third.second) },
            prs.map { Trace.Scalar(s(it.first), it.second) }, hr.filter { !it.second.isNaN() }.map { Trace.Scalar(s(it.first), it.second) },
            notes.map { Trace.Note(s(it.first), it.second, it.third) },
            when { poses.isEmpty() -> "none"; useSync -> "frame clock mapped to the sensor clock (±%.0f ms)".format(sync.jitterNs / 1e6); else -> "frame arrival time (late by the camera's latency)" },
        )

    }
}
