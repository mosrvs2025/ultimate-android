package app.ultimate.room

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** A point in the room, in metres. Y is up (gravity), as the phone's motion tracking reports it. */
data class P3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(o: P3) = P3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: P3) = P3(x + o.x, y + o.y, z + o.z)
    fun dist(o: P3): Float { val d = this - o; return sqrt(d.x * d.x + d.y * d.y + d.z * d.z) }
}

/** What a scan measured about the room. Every number comes from the points seen; nothing is assumed. */
data class RoomStats(val floorY: Float, val ceilingY: Float?, val width: Float, val length: Float, val floorArea: Float, val voxels: Int) {
    val height: Float? get() = ceilingY?.let { it - floorY }
}

/**
 * The room as a grid of small cubes ([size] metres, 5 cm by default). Each depth reading lands in a cube; a cube only counts as
 * solid once it has been seen [solidAt] times, which removes most of the noise that depth-from-motion produces. Cubes are kept in
 * a hash map keyed by their grid position, so memory follows what was scanned, not the size of the room. Pure, so it is tested.
 */
class RoomMap(val size: Float = 0.05f, val solidAt: Int = 3) {
    private val cells = HashMap<Long, Int>()
    val raw: Int get() = cells.size

    private fun key(ix: Int, iy: Int, iz: Int): Long = ((ix + OFF).toLong() shl 42) or ((iy + OFF).toLong() shl 21) or (iz + OFF).toLong()
    private fun unkey(k: Long) = Triple(((k shr 42) and MASK).toInt() - OFF, ((k shr 21) and MASK).toInt() - OFF, (k and MASK).toInt() - OFF)
    private fun idx(v: Float) = floor(v / size).toInt()

    fun add(x: Float, y: Float, z: Float) {
        val ix = idx(x); val iy = idx(y); val iz = idx(z)
        if (abs(ix) >= OFF || abs(iy) >= OFF || abs(iz) >= OFF) return
        val k = key(ix, iy, iz); val n = cells[k] ?: 0
        if (n < 255) cells[k] = n + 1
    }

    /** Puts a point straight in as solid: for merging a new scan into a saved room. */
    fun addSolid(x: Float, y: Float, z: Float) {
        val ix = idx(x); val iy = idx(y); val iz = idx(z)
        if (abs(ix) >= OFF || abs(iy) >= OFF || abs(iz) >= OFF) return
        val k = key(ix, iy, iz); cells[k] = maxOf(cells[k] ?: 0, solidAt)
    }

    /** The centres of every solid cube. */
    fun points(): List<P3> = cells.entries.filter { it.value >= solidAt }.map { (k, _) -> unkey(k).let { (x, y, z) -> P3((x + 0.5f) * size, (y + 0.5f) * size, (z + 0.5f) * size) } }
    val solid: Int get() = cells.values.count { it >= solidAt }
    fun clear() = cells.clear()

    /**
     * Floor, ceiling, footprint and area. The floor is the lowest height with a lot of points (a peak in the height histogram within
     * the bottom of the scan), the ceiling the same from the top if it was scanned. Width and length come from the 2nd to 98th
     * percentile of the points along the room's own long and short axes (found from the walls, so a room scanned at an angle is
     * still measured along its walls). Area counts the floor cells that were actually seen. Null until there is enough to measure.
     */
    fun stats(): RoomStats? {
        val pts = points(); if (pts.size < 200) return null
        val ys = pts.map { it.y }; val minY = ys.min(); val maxY = ys.max()
        if (maxY - minY < 0.3f) return null
        val bin = size
        fun peak(from: Float, to: Float): Pair<Float, Int>? {
            val h = HashMap<Int, Int>(); pts.forEach { if (it.y in from..to) h.merge(floor(it.y / bin).toInt(), 1, Int::plus) }
            return h.maxByOrNull { it.value }?.let { (it.key + 0.5f) * bin to it.value }
        }
        val floor = peak(minY, minY + 0.4f) ?: return null
        val ceil = peak(maxY - 0.4f, maxY)?.takeIf { it.first - floor.first > 1.6f && it.second > floor.second / 6 }
        // The room's direction: walls are the points well above the floor. Try angles and keep the one where the footprint is smallest.
        val walls = pts.filter { it.y > floor.first + 0.5f }.ifEmpty { pts }
        fun extent(deg: Int): Pair<Float, Float> {
            val a = Math.toRadians(deg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
            val u = walls.map { it.x * c + it.z * s }.sorted(); val v = walls.map { -it.x * s + it.z * c }.sorted()
            fun span(l: List<Float>) = l[(l.size * 0.98f).toInt().coerceAtMost(l.size - 1)] - l[(l.size * 0.02f).toInt()]
            return span(u) to span(v)
        }
        val best = (0 until 90 step 3).map { extent(it) }.minBy { it.first * it.second }
        val floorCells = pts.filter { abs(it.y - floor.first) <= bin * 1.5f }.map { idx(it.x) to idx(it.z) }.toSet().size
        return RoomStats(floor.first, ceil?.first, minOf(best.first, best.second) + size, maxOf(best.first, best.second) + size, floorCells * size * size, pts.size)
    }

    /** Saves the solid cubes compactly (about 13 bytes each before compression). */
    fun write(out: OutputStream) {
        val d = DataOutputStream(out); d.writeInt(MAGIC); d.writeFloat(size)
        val keep = cells.entries.filter { it.value >= solidAt }
        d.writeInt(keep.size); keep.forEach { (k, v) -> d.writeLong(k); d.writeByte(v) }; d.flush()
    }

    companion object {
        private const val OFF = 1 shl 20
        private const val MASK = (1L shl 21) - 1
        private const val MAGIC = 0x524D4150   // "RMAP"

        fun read(input: InputStream): RoomMap {
            val d = DataInputStream(input); require(d.readInt() == MAGIC) { "Not a room map" }
            val m = RoomMap(d.readFloat()); val n = d.readInt(); require(n in 0..5_000_000) { "Room map is damaged" }
            repeat(n) { val k = d.readLong(); m.cells[k] = maxOf(d.readUnsignedByte(), m.solidAt) }
            return m
        }
    }
}

/**
 * Turning a depth picture into points in the room. [fx], [fy], [cx], [cy] are the camera's focal lengths and centre in the depth
 * picture's own pixels; [pose] is the camera's position and rotation as a 4x4 column-major matrix (camera to world), with the
 * camera looking down its own -Z and +Y up, which is how ARCore reports it. Pure, so it is tested.
 */
object Depth {
    fun toWorld(px: Float, py: Float, metres: Float, fx: Float, fy: Float, cx: Float, cy: Float, pose: FloatArray): P3 {
        val x = (px - cx) * metres / fx; val y = (cy - py) * metres / fy; val z = -metres
        return P3(pose[0] * x + pose[4] * y + pose[8] * z + pose[12], pose[1] * x + pose[5] * y + pose[9] * z + pose[13], pose[2] * x + pose[6] * y + pose[10] * z + pose[14])
    }

    /** Where a room point lands on screen (0..1 across and down) given the view and projection matrices, or null if it is behind the camera. */
    fun toScreen(p: P3, view: FloatArray, proj: FloatArray): Pair<Float, Float>? {
        fun mul(m: FloatArray, v: FloatArray) = FloatArray(4) { r -> m[r] * v[0] + m[4 + r] * v[1] + m[8 + r] * v[2] + m[12 + r] * v[3] }
        val c = mul(proj, mul(view, floatArrayOf(p.x, p.y, p.z, 1f)))
        if (c[3] <= 0.01f) return null
        return (c[0] / c[3] * 0.5f + 0.5f) to (0.5f - c[1] / c[3] * 0.5f)
    }
}
