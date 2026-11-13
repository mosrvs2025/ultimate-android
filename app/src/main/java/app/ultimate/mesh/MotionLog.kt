package app.ultimate.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * A recording of a phone's motion sensors: acceleration (m/s², gravity included) and rotation rate (rad/s), each row
 * stamped in milliseconds since the recording began. Kept compact so a long session fits in memory and can be pulled
 * across the mesh in small pieces; a recording stops growing at [MAX_ROWS] instead of filling the phone.
 *
 * Quantised for transfer: acceleration to 0.005 m/s², rotation to 0.001 rad/s, both clamped to what 16 bits hold
 * (+-163 m/s², +-32 rad/s: beyond any human movement). [encode] is 16 bytes a row.
 */
class MotionLog(val maxRows: Int = MAX_ROWS) {
    private var t = IntArray(1024)
    private var v = FloatArray(1024 * 6)
    var rows = 0; private set
    val full get() = rows >= maxRows

    @Synchronized fun add(ms: Int, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float): Boolean {
        if (rows >= maxRows) return false
        if (rows == t.size) { t = t.copyOf(rows * 2); v = v.copyOf(rows * 12) }
        t[rows] = ms
        val o = rows * 6
        v[o] = ax; v[o + 1] = ay; v[o + 2] = az; v[o + 3] = gx; v[o + 4] = gy; v[o + 5] = gz
        rows++
        return true
    }

    @Synchronized fun timeMs(i: Int) = t[i]
    @Synchronized fun accel(i: Int, axis: Int) = v[i * 6 + axis]
    @Synchronized fun gyro(i: Int, axis: Int) = v[i * 6 + 3 + axis]
    val durationMs: Long get() = if (rows == 0) 0 else (timeMs(rows - 1) - timeMs(0)).toLong()

    /** Time in seconds and the three acceleration axes of every row: the input to [RepCounter]. */
    @Synchronized fun axes(): Pair<DoubleArray, Array<DoubleArray>> =
        DoubleArray(rows) { t[it] / 1000.0 } to Array(3) { k -> DoubleArray(rows) { i -> v[i * 6 + k].toDouble() } }

    /** Rows [from, from+count) as [ROW_BYTES] each, URL-safe base64 (no padding) so it travels in a mesh frame. */
    @Synchronized fun encode(from: Int, count: Int): String {
        val n = (minOf(from + count, rows) - from).coerceAtLeast(0)
        val b = ByteBuffer.allocate(n * ROW_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (i in from until from + n) {
            val o = i * 6
            b.putInt(t[i])
            for (k in 0..2) b.putShort(q(v[o + k], 200f))
            for (k in 3..5) b.putShort(q(v[o + k], 1000f))
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b.array())
    }

    private fun q(x: Float, scale: Float) = Math.round(x * scale).coerceIn(-32768, 32767).toShort()

    @Synchronized fun csv(): String = buildString {
        append("ms,ax,ay,az,gx,gy,gz\n")
        for (i in 0 until rows) { val o = i * 6; append(t[i]); for (k in 0..5) append(',').append("%.4f".format(java.util.Locale.US, v[o + k])); append('\n') }
    }

    companion object {
        const val ROW_BYTES = 16
        const val MAX_ROWS = 360_000 // two hours at 50 Hz, about 8 MB in memory
        /** The most rows one mesh frame carries: 375 rows is 6,000 bytes, which stays under the 16 KB frame cap after both base64 layers. */
        const val CHUNK_ROWS = 375

        /** Reads rows produced by [encode]. Bad input throws; a peer's data is checked, never trusted blindly. */
        fun decodeInto(log: MotionLog, text: String): Int {
            val raw = Base64.getUrlDecoder().decode(text)
            require(raw.size % ROW_BYTES == 0) { "bad motion chunk" }
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            var n = 0
            while (b.remaining() >= ROW_BYTES) {
                val ms = b.int
                val a = FloatArray(3) { b.short / 200f }; val g = FloatArray(3) { b.short / 1000f }
                if (!log.add(ms, a[0], a[1], a[2], g[0], g[1], g[2])) break
                n++
            }
            return n
        }
    }
}
