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

/**
 * What the phone has seen of a space, as small cubes ([size] metres, 5 cm by default), each holding *evidence* rather than a yes/no:
 *
 *  - every depth reading that lands in a cube adds to its occupancy (log-odds), weighted by how trustworthy the reading was;
 *  - every depth ray that passes *through* a cube on its way to a surface takes some away (free-space carving), so a hand, a foot or a
 *    one-off bad reading that is later seen through fades instead of staying forever;
 *  - a cube only counts as solid when its evidence is high *and* it was seen from at least [MIN_VIEWS] (three) different camera positions
 *    (key frames), so the same depth picture counted over and over while the phone is held still can no longer make noise "solid".
 *
 * A second, coarse grid (20 cm) remembers where rays have passed freely, so a later scan can tell "this was solid before and is now seen
 * to be empty" (furniture that moved) from "not looked at this time". Cubes live in an open-addressing table of primitive arrays, so a
 * large scan costs a few megabytes and no allocation per reading. Pure, so it is tested.
 */
class RoomMap(val size: Float = 0.05f) {
    private var cap = 1 shl 14
    private var mask = cap - 1
    private var keys = LongArray(cap) { EMPTY }
    private var occ = FloatArray(cap)
    private var last = IntArray(cap) { -1 }
    private var views = ByteArray(cap)
    private var used = 0
    private val free = HashMap<Long, Int>()

    private fun idx(v: Float) = floor(v / size).toInt()
    private fun pack(ix: Int, iy: Int, iz: Int): Long = ((ix + OFF).toLong() shl 42) or ((iy + OFF).toLong() shl 21) or (iz + OFF).toLong()
    private fun inRange(ix: Int, iy: Int, iz: Int) = abs(ix) < OFF && abs(iy) < OFF && abs(iz) < OFF
    private fun slot(key: Long): Int { var h = key * -7046029254386353131L; h = h xor (h ushr 32); return h.toInt() and mask }
    private fun find(key: Long): Int { var i = slot(key); while (true) { val k = keys[i]; if (k == key) return i; if (k == EMPTY) return -1; i = (i + 1) and mask } }
    private fun findOrAdd(key: Long): Int {
        var i = slot(key)
        while (true) { val k = keys[i]; if (k == key) return i; if (k == EMPTY) break; i = (i + 1) and mask }
        if ((used + 1) * 10 > cap * 6) { grow(); return findOrAdd(key) }
        keys[i] = key; occ[i] = 0f; last[i] = -1; views[i] = 0; used++; return i
    }
    private fun grow() {
        val ok = keys; val oo = occ; val ol = last; val ov = views
        cap = cap shl 1; mask = cap - 1; keys = LongArray(cap) { EMPTY }; occ = FloatArray(cap); last = IntArray(cap) { -1 }; views = ByteArray(cap); used = 0
        for (i in ok.indices) if (ok[i] != EMPTY && oo[i] > DROP) { val j = findOrAdd(ok[i]); occ[j] = oo[i]; last[j] = ol[i]; views[j] = ov[i] }
    }

    /** One depth reading at ([x], [y], [z]) seen from key frame [view], with [w] its weight (1 = an ordinary good reading). */
    fun hit(x: Float, y: Float, z: Float, view: Int, w: Float = 1f) {
        val ix = idx(x); val iy = idx(y); val iz = idx(z); if (!inRange(ix, iy, iz)) return
        val i = findOrAdd(pack(ix, iy, iz))
        occ[i] = minOf(occ[i] + HIT * w, OCC_MAX)
        if (last[i] != view) { last[i] = view; if (views[i] < 100) views[i]++ }
    }

    /**
     * A ray from the camera at ([ox], [oy], [oz]) to a surface at ([x], [y], [z]): everything on the way, except the last [margin] metres
     * next to the surface itself, is seen to be empty. Only cubes that already exist are lowered (empty space costs nothing to store);
     * the coarse free grid is updated everywhere the ray goes.
     */
    fun carve(ox: Float, oy: Float, oz: Float, x: Float, y: Float, z: Float, margin: Float) {
        val dx = x - ox; val dy = y - oy; val dz = z - oz; val len = sqrt(dx * dx + dy * dy + dz * dz) - margin
        if (len <= size) return
        val n = (len / size).toInt(); val sx = dx / (len + margin) * size; val sy = dy / (len + margin) * size; val sz = dz / (len + margin) * size
        var px = ox; var py = oy; var pz = oz; var lastKey = Long.MIN_VALUE; var lastFree = Long.MIN_VALUE
        for (s in 0 until n) {
            px += sx; py += sy; pz += sz
            val ix = idx(px); val iy = idx(py); val iz = idx(pz); if (!inRange(ix, iy, iz)) return
            val k = pack(ix, iy, iz)
            if (k != lastKey) { lastKey = k; val i = find(k); if (i >= 0) occ[i] = maxOf(occ[i] - MISS, OCC_MIN) }
            val fk = pack(floorDiv(ix, 4), floorDiv(iy, 4), floorDiv(iz, 4))
            if (fk != lastFree) { lastFree = fk; free.merge(fk, 1) { a, _ -> if (a < 255) a + 1 else a } }
        }
    }

    private fun floorDiv(a: Int, b: Int) = Math.floorDiv(a, b)
    private fun isSolid(i: Int) = occ[i] >= SOLID && views[i] >= MIN_VIEWS

    /** How many rays have passed through the coarse (20 cm) cell around the point: 0 means it was never looked through. */
    fun freeAt(x: Float, y: Float, z: Float): Int = free[pack(floorDiv(idx(x), 4), floorDiv(idx(y), 4), floorDiv(idx(z), 4))] ?: 0
    fun isSolidAt(x: Float, y: Float, z: Float): Boolean { val ix = idx(x); val iy = idx(y); val iz = idx(z); if (!inRange(ix, iy, iz)) return false; val i = find(pack(ix, iy, iz)); return i >= 0 && isSolid(i) }

    /** Is anything solid within [r] metres of the point? */
    fun isSolidNear(p: P3, r: Float): Boolean { val n = (r / size).toInt(); val ix = idx(p.x); val iy = idx(p.y); val iz = idx(p.z); for (a in -n..n) for (b in -n..n) for (c in -n..n) { val i = find(pack(ix + a, iy + b, iz + c)); if (i >= 0 && isSolid(i)) return true }; return false }
    val solid: Int get() { var n = 0; for (i in keys.indices) if (keys[i] != EMPTY && isSolid(i)) n++; return n }
    val raw: Int get() = used

    private fun unpack(k: Long) = Triple(((k shr 42) and MASK).toInt() - OFF, ((k shr 21) and MASK).toInt() - OFF, (k and MASK).toInt() - OFF)

    /** The centres of every solid cube. */
    fun points(): List<P3> { val out = ArrayList<P3>(); forEachSolid { x, y, z -> out += P3(x, y, z) }; return out }
    inline fun forEachSolid(f: (Float, Float, Float) -> Unit) { forEachSolidIndex { x, y, z, _ -> f(x, y, z) } }
    inline fun forEachSolidIndex(f: (Float, Float, Float, Float) -> Unit) { for (i in 0 until capacity()) if (solidAtSlot(i)) { val c = centre(i); f(c[0], c[1], c[2], evidenceAt(i)) } }
    fun capacity() = cap
    fun solidAtSlot(i: Int) = keys[i] != EMPTY && isSolid(i)
    fun evidenceAt(i: Int) = occ[i]
    fun centre(i: Int): FloatArray { val (x, y, z) = unpack(keys[i]); return floatArrayOf((x + 0.5f) * size, (y + 0.5f) * size, (z + 0.5f) * size) }

    /** Cubes with some evidence that are not solid yet: shown faintly while scanning so the picture reacts at once, never measured or saved. */
    fun tentative(max: Int = 20000): List<P3> { val out = ArrayList<P3>(); var i = 0; while (i < cap && out.size < max) { if (keys[i] != EMPTY && !isSolid(i) && occ[i] >= 0.4f) { val c = centre(i); out += P3(c[0], c[1], c[2]) }; i++ }; return out }

    /** Solid cubes with fewer than [minNeighbours] solid cubes touching them are speckle and are left out (used for drawing and measuring). */
    fun cleaned(minNeighbours: Int = 3): List<P3> {
        val set = HashSet<Long>(); val all = ArrayList<Triple<Int, Int, Int>>()
        for (i in 0 until cap) if (solidAtSlot(i)) { all += unpack(keys[i]); set += keys[i] }
        val out = ArrayList<P3>(all.size)
        for ((x, y, z) in all) {
            var n = 0
            loop@ for (a in -1..1) for (b in -1..1) for (c in -1..1) { if ((a or b or c) == 0) continue; if (pack(x + a, y + b, z + c) in set && ++n >= minNeighbours) break@loop }
            if (n >= minNeighbours) out += P3((x + 0.5f) * size, (y + 0.5f) * size, (z + 0.5f) * size)
        }
        return out
    }

    fun clear() { cap = 1 shl 14; mask = cap - 1; keys = LongArray(cap) { EMPTY }; occ = FloatArray(cap); last = IntArray(cap) { -1 }; views = ByteArray(cap); used = 0; free.clear() }

    /** A copy of this map with every solid cube and every freely-seen cell moved by [f] (a rigid change of coordinates), for fusing sessions. */
    fun mapped(f: (P3) -> P3): RoomMap {
        val out = RoomMap(size)
        forEachSolidIndex { x, y, z, e -> val p = f(P3(x, y, z)); out.putSolid(p.x, p.y, p.z, e) }
        for ((k, n) in free) { val (a, b, c) = unpack(k); val p = f(P3((a * 4 + 2) * size, (b * 4 + 2) * size, (c * 4 + 2) * size)); out.free.merge(pack(floorDiv(out.idx(p.x), 4), floorDiv(out.idx(p.y), 4), floorDiv(out.idx(p.z), 4)), n) { u, v -> minOf(255, maxOf(u, v)) } }
        return out
    }
    internal fun putSolid(x: Float, y: Float, z: Float, evidence: Float) {
        val ix = idx(x); val iy = idx(y); val iz = idx(z); if (!inRange(ix, iy, iz)) return
        val i = findOrAdd(pack(ix, iy, iz)); occ[i] = maxOf(occ[i], evidence.coerceIn(SOLID, OCC_MAX)); views[i] = maxOf(views[i].toInt(), MIN_VIEWS).toByte()
    }

    /**
     * Folds another scan of the same space, already written in this map's coordinates (see [mapped]), into this one:
     *  - a cube both agree on gains evidence (so walls seen in many sessions become the most trusted);
     *  - a cube only the new scan has is added with the new scan's evidence;
     *  - a cube only this map has is *removed* when the new scan looked straight through that spot ([minFree] rays or more) and found
     *    nothing solid within 10 cm: that is furniture that moved or noise from an earlier scan. Where the new scan never looked,
     *    nothing changes, so a partial scan can only improve the map.
     */
    fun fuse(other: RoomMap, minFree: Int = 8): FuseReport {
        require(abs(other.size - size) < 1e-6f) { "Maps use different cube sizes" }
        val theirs = ArrayList<Long>(); val ev = ArrayList<Float>()
        for (i in 0 until other.cap) if (other.solidAtSlot(i)) { theirs += other.keys[i]; ev += other.occ[i] }
        // Decide removals first, against the map as it was.
        var removed = 0
        for (i in 0 until cap) if (solidAtSlot(i)) {
            val (x, y, z) = unpack(keys[i])
            if ((other.free[pack(floorDiv(x, 4), floorDiv(y, 4), floorDiv(z, 4))] ?: 0) < minFree) continue
            var near = false
            loop@ for (a in -2..2) for (b in -2..2) for (c in -2..2) { val j = other.find(pack(x + a, y + b, z + c)); if (j >= 0 && other.occ[j] > 0.5f) { near = true; break@loop } }
            if (!near) { occ[i] = 0f; views[i] = 0; removed++ }
        }
        // A new cube lands on an existing one, or next to one (alignment is good to a few centimetres, not to the cube): either way it is
        // the same surface and confirms it. Only cubes with nothing solid beside them are new; without this, every merge laid a second copy
        // of each wall a cube away from the first.
        var added = 0; var reinforced = 0
        for (n in theirs.indices) {
            val (x, y, z) = unpack(theirs[n]); var hit = -1
            loop@ for (a in intArrayOf(0, -1, 1)) for (b in intArrayOf(0, -1, 1)) for (c in intArrayOf(0, -1, 1)) { val j = find(pack(x + a, y + b, z + c)); if (j >= 0 && isSolid(j)) { hit = j; break@loop } }
            if (hit >= 0) { occ[hit] = minOf(occ[hit] + 0.5f * ev[n], OCC_MAX); reinforced++ }
            else { val k = findOrAdd(theirs[n]); occ[k] = maxOf(occ[k], ev[n]); views[k] = maxOf(views[k].toInt(), MIN_VIEWS).toByte(); added++ }
        }
        for ((k, v) in other.free) free.merge(k, v) { a, b -> minOf(255, a + b) }
        return FuseReport(added, reinforced, removed)
    }

    /** Saves the solid cubes and the freely-seen cells compactly (about 10 bytes each before compression). */
    fun write(out: OutputStream) {
        val d = DataOutputStream(out); d.writeInt(MAGIC); d.writeFloat(size)
        var n = 0; for (i in 0 until cap) if (solidAtSlot(i)) n++
        d.writeInt(n); for (i in 0 until cap) if (solidAtSlot(i)) { d.writeLong(keys[i]); d.writeByte((occ[i] * 20f).toInt().coerceIn(0, 127)) }
        val f = free.filter { it.value >= FREE_KEEP }
        d.writeInt(f.size); f.forEach { (k, v) -> d.writeLong(k); d.writeByte(minOf(v, 255)) }
        d.flush()
    }

    companion object {
        private const val EMPTY = Long.MIN_VALUE
        private const val OFF = 1 shl 20
        private const val MASK = (1L shl 21) - 1
        private const val MAGIC = 0x524D5032   // "RMP2"
        private const val MAGIC_V1 = 0x524D4150   // "RMAP", the first release: solid cubes only
        const val HIT = 0.85f; const val MISS = 0.3f; const val OCC_MAX = 4f; const val OCC_MIN = -1.5f; const val SOLID = 1.5f; const val MIN_VIEWS = 3
        private const val DROP = -1.2f
        private const val FREE_KEEP = 3

        fun read(input: InputStream): RoomMap {
            val d = DataInputStream(input); val magic = d.readInt(); require(magic == MAGIC || magic == MAGIC_V1) { "Not a room map" }
            val m = RoomMap(d.readFloat()); val n = d.readInt(); require(n in 0..6_000_000) { "Room map is damaged" }
            repeat(n) {
                val k = d.readLong(); val v = d.readUnsignedByte()
                val i = m.findOrAdd(k); m.occ[i] = if (magic == MAGIC) maxOf(v / 20f, SOLID) else SOLID + 0.5f; m.views[i] = MIN_VIEWS.toByte()
            }
            if (magic == MAGIC) { val f = d.readInt(); require(f in 0..6_000_000) { "Room map is damaged" }; repeat(f) { val k = d.readLong(); m.free[k] = d.readUnsignedByte() } }
            return m
        }
    }
}

/**
 * Turning a depth picture into points in the room. [fx], [fy], [cx], [cy] are the camera's focal lengths and centre in the depth
 * picture's own pixels; [pose] is the camera's position and rotation as a 4x4 column-major matrix (camera to world), with the
 * camera looking down its own -Z and +Y up, which is how ARCore reports it. The depth is the distance along the camera's viewing
 * direction (ARCore's "z depth"), not along each pixel's own ray. Pure, so it is tested.
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

/** What folding a new scan into a saved map changed, in cubes. */
data class FuseReport(val added: Int, val reinforced: Int, val removed: Int)

/** Turn about the vertical, then shift: how a point of one scan session is written in another's coordinates. [score] and the rest describe how sure the match was. */
data class Alignment(
    val yawDeg: Float, val dx: Float, val dy: Float, val dz: Float,
    val score: Float = 1f, val precision: Float = 1f, val recall: Float = 1f, val rmsCm: Float = 0f, val matched: Int = 0, val margin: Float = 1f, val ambiguous: Boolean = false,
) {
    fun apply(p: P3): P3 { val a = Math.toRadians(yawDeg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat(); return P3(p.x * c - p.z * s + dx, p.y + dy, p.x * s + p.z * c + dz) }
    fun invert(p: P3): P3 { val a = Math.toRadians(yawDeg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat(); val x = p.x - dx; val z = p.z - dz; return P3(x * c + z * s, p.y - dy, -x * s + z * c) }
    companion object { val IDENTITY = Alignment(0f, 0f, 0f, 0f) }
}
