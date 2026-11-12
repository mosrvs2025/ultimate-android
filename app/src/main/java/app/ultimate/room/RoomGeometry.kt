package app.ultimate.room

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class Confidence { NONE, LOW, MEDIUM, HIGH }

/** The extent of the room along one wall direction, from the outermost wall seen on each side. [complete] only when walls were seen on both sides. */
data class Span(val lo: Float, val hi: Float, val complete: Boolean) { val size: Float get() = hi - lo }

/** A wall as a fitted flat surface: [axis] 0 runs along the room's u direction (so it sits at u = [pos]) or 1 along v, spanning [from] to [to] along the other axis. */
data class WallSeg(val axis: Int, val pos: Float, val from: Float, val to: Float)

/** A wall you pointed at: the spot ([x], [z]) and where the phone was ([fromX], [fromZ]), which says which way the wall faces. */
data class WallMark(val x: Float, val z: Float, val fromX: Float, val fromZ: Float)

/** A flat horizontal surface ARCore found by itself from the camera picture (a second opinion on where the floor is). [area] in square metres. */
data class PlaneHint(val y: Float, val area: Float, val upward: Boolean)

/**
 * What a scan measured about the space. Anything not actually established is null (never a guess), and [notes] say in plain words what
 * is missing and what to do about it. [headingDeg] is the direction of the walls; [u] and [v] are the extents along and across it.
 */
data class RoomStats(
    val floorY: Float?, val ceilingY: Float?, val headingDeg: Float, val u: Span?, val v: Span?,
    val floorSeen: Float, val voxels: Int, val confidence: Confidence, val notes: List<String> = emptyList(), val floorSource: String = "", val ceilingSource: String = "",
    /** The walls found, as flat surfaces over the stretch that was actually seen (or marked by you): for drawing, and for saying which are still missing. */
    val walls: List<WallSeg> = emptyList(),
) {
    /** The room height if it came from something trustworthy (depth, wall tops, or you), for keeping across scans; null for a guess. */
    val trustedHeight: Float? get() = height?.takeIf { ceilingSource in setOf("depth", "walls", "you") && floorSource != "plane" }
    val height: Float? get() = if (floorY != null && ceilingY != null) ceilingY - floorY else null
    val sizes: List<Float> get() = listOfNotNull(u?.takeIf { it.complete }?.size, v?.takeIf { it.complete }?.size).sortedDescending()
    val length: Float? get() = sizes.firstOrNull()
    val width: Float? get() = sizes.getOrNull(1)
    /** The measured footprint, only when both directions are bounded by walls on both sides. */
    val footprint: Float? get() = if (u?.complete == true && v?.complete == true) u.size * v.size else null
}

/**
 * Measuring a space from the solid cubes of its map. The old method took the lowest and highest cubes as floor and ceiling; a single
 * stray depth reading far below the floor therefore moved the "floor" there and reported a six-metre room with no floor. This method
 * asks for *support* instead:
 *
 *  - Floor and ceiling are heights at which a large *area* of the room is solid (counting each 10 cm patch once), not heights where
 *    the odd cube is. A handful of stray cubes never adds up to half a square metre.
 *  - The phone is always above the floor and below the ceiling, so a floor must lie below every camera position and a ceiling above.
 *  - A ceiling must make a plausible room height; anything beyond is reported as ignored, not believed.
 *  - Walls are found as tall vertical *columns*, whose evidence does not depend on knowing the floor, then fitted by a Hough transform
 *    for the room's direction, and measured wall to wall. One wall seen along a direction gives no size for that direction.
 *
 * Pure, so it is tested against simulated phones with noise, stray readings, a mirror and a hand in the way.
 */
object RoomGeometry {
    private const val CELL = 0.1f
    private const val BIN = 0.05f
    private fun cell(v: Float) = floor(v / CELL).toInt()
    private fun cellKey(x: Float, z: Float) = (cell(x).toLong() shl 32) or (cell(z).toLong() and 0xFFFFFFFFL)

    fun measure(pts: List<P3>, camMinY: Float? = null, camMaxY: Float? = null, planes: List<PlaneHint> = emptyList(), maxCeilingHeight: Float = 4.6f, minCeilingHeight: Float = 1.8f, userFloor: Float? = null, userCeiling: Float? = null, maxPitchDeg: Float? = null, userWalls: List<WallMark> = emptyList(), userCeilingHeight: Float? = null): RoomStats? {
        if (pts.size < 400) return null
        val notes = ArrayList<String>()
        val perBin = HashMap<Int, HashSet<Long>>()
        for (p in pts) perBin.getOrPut(floor(p.y / BIN).toInt()) { HashSet() }.add(cellKey(p.x, p.z))
        val b0 = perBin.keys.min(); val b1 = perBin.keys.max()
        // Area (m²) of the room that is solid in each 5 cm layer. Walls add about the same area to every layer, so that is the baseline;
        // a floor or ceiling is a layer that stands well *above* it.
        val area = FloatArray(b1 - b0 + 1) { i -> (perBin[b0 + i]?.size ?: 0) * CELL * CELL }
        val base = area.filter { it > 0f }.sorted().let { it[it.size / 2] }
        val smooth = FloatArray(area.size) { i -> 0.5f * area.getOrElse(i - 1) { 0f } + area[i] + 0.5f * area.getOrElse(i + 1) { 0f } - 2f * base }
        val peakExcess = smooth.max()
        fun yOf(i: Int) = (b0 + i + 0.5f) * BIN
        fun isPeak(i: Int) = (max(0, i - 2)..min(smooth.size - 1, i + 2)).all { j -> smooth[i] > smooth[j] || (smooth[i] == smooth[j] && j >= i) }
        /** The height of a peak, from the excess area of the layers around it (so wall cells, which are everywhere, do not pull it). */
        fun locate(i: Int): Float { var s = 0f; var w = 0f; for (j in max(0, i - 2)..min(smooth.size - 1, i + 2)) { val e = area[j] - base; if (e > 0f) { s += e * yOf(j); w += e } }; return if (w > 0f) s / w else yOf(i) }

        // ---- Floor: the lowest peak with real area behind it that is below every camera position.
        val floorNeed = max(0.5f, 0.15f * peakExcess)
        var floorI = -1
        for (i in smooth.indices) { if (camMinY != null && yOf(i) > camMinY - 0.2f) break; if (smooth[i] >= floorNeed && isPeak(i)) { floorI = i; break } }
        var floorY: Float? = null; var floorSource = ""
        if (floorI >= 0) { floorY = locate(floorI); floorSource = "depth" }
        val upPlane = planes.filter { it.upward && it.area >= 0.8f && (camMinY == null || it.y <= camMinY - 0.2f) }.minByOrNull { it.y }
        if (upPlane != null) {
            if (floorY == null) { floorY = upPlane.y; floorSource = "plane"; notes += "The floor comes from ARCore's own surface detection; depth hasn't seen enough of it." }
            else if (abs(floorY - upPlane.y) <= 0.12f) floorSource = "depth+plane"
            else if (upPlane.y < floorY - 0.15f && upPlane.area >= 2f) notes += "ARCore sees a larger surface ${((floorY - upPlane.y) * 100).toInt()} cm lower; the floor may be lower than measured."
        }
        if (userFloor != null) { floorY = userFloor; floorSource = "you"; notes.clear() }
        if (floorY == null) notes += "Floor not found yet: point the camera at the floor and move a little, or aim the dot at it and tap Mark floor."

        // ---- Ceiling: the highest peak with real area, above every camera position and at a plausible room height.
        val ceilNeed = max(0.4f, 0.10f * peakExcess)
        var ceilI = -1
        for (i in smooth.indices.reversed()) {
            val y = yOf(i)
            if (smooth[i] < ceilNeed || !isPeak(i)) continue
            if (camMaxY != null && y < camMaxY + 0.1f) break
            if (floorY != null && (y - floorY < minCeilingHeight || y - floorY > maxCeilingHeight)) continue
            ceilI = i; break
        }
        var ceilingY = if (ceilI >= 0 && floorY != null) locate(ceilI) else null
        var ceilingSource = if (ceilingY != null) "depth" else ""
        // Plain ceilings give depth-from-motion little to work with; ARCore's own surface detection sometimes still finds them.
        if (ceilingY == null && floorY != null) planes.filter { !it.upward && it.area >= 0.8f && (camMaxY == null || it.y >= camMaxY + 0.1f) && it.y - floorY in minCeilingHeight..maxCeilingHeight }.maxByOrNull { it.area }?.let { ceilingY = it.y; ceilingSource = "plane"; notes += "The ceiling comes from ARCore's surface detection, not from depth." }
        // The tops of the walls: a ceiling shows up as many wall columns all ending at the same height, even when plain paint gives depth
        // nothing to measure on the ceiling itself. Only believed when the columns are many, tightly level, and spread across the room.
        // Only when the phone was really tilted up at the ceiling: walls that merely end at the edge of a level picture are not a ceiling.
        if (ceilingY == null && floorY != null && maxPitchDeg != null && maxPitchDeg >= 25f) wallTops(pts, floorY, camMaxY, minCeilingHeight, maxCeilingHeight)?.let { ceilingY = it; ceilingSource = "walls"; notes += "The ceiling height is estimated from where the walls end, not measured on the ceiling." }
        // A height you gave stays a height: if the floor estimate moves a little, the ceiling moves with it rather than the room changing size.
        if (userCeilingHeight != null && floorY != null) { ceilingY = floorY + userCeilingHeight; ceilingSource = "you" }
        else if (userCeiling != null && floorY != null && userCeiling - floorY > 1f) { ceilingY = userCeiling; ceilingSource = "you" }
        if (ceilingY == null && floorY != null) notes += "Ceiling not confirmed: tilt up to it, or aim the dot at it and tap Mark ceiling."
        if (ceilingY != null && smooth.indices.any { yOf(it) > ceilingY!! + 0.25f && smooth[it] >= ceilNeed && isPeak(it) }) notes += "Readings above the ceiling (a window, mirror or glare?) were ignored."

        // ---- Walls: tall vertical columns, independent of the floor.
        val lowY = floorY?.plus(0.15f) ?: camMinY?.minus(1.9f) ?: Float.NEGATIVE_INFINITY
        val highY = ceilingY?.minus(0.1f) ?: camMaxY?.plus(1.2f) ?: Float.POSITIVE_INFINITY
        val cols = HashMap<Long, HashSet<Int>>()
        for (p in pts) if (p.y in lowY..highY) cols.getOrPut(cellKey(p.x, p.z)) { HashSet() }.add(floor(p.y / BIN).toInt())
        val wall = cols.filter { it.value.size >= 14 }.keys.map { P3(((it shr 32).toInt() + 0.5f) * CELL, 0f, ((it and 0xFFFFFFFFL).toInt() + 0.5f) * CELL) }
        var heading = 0f; var u: Span? = null; var v: Span? = null; var segs: List<WallSeg> = emptyList()
        if (wall.size >= 25 || userWalls.isNotEmpty()) {
            // The walls' direction from what was seen; with nothing seen yet, from the way the first wall you pointed at faces.
            heading = if (wall.size >= 25) heading(wall) else userWalls.first().let { m -> ((Math.toDegrees(atan2((m.z - m.fromZ).toDouble(), (m.x - m.fromX).toDouble())) % 90 + 90) % 90).toFloat() }
            val a = Math.toRadians(heading.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
            val us = ArrayList(wall.map { it.x * c + it.z * s }); val vs = ArrayList(wall.map { -it.x * s + it.z * c })
            // A wall you pointed at counts as a strong row on the side it faces: measuring needs both sides, and you can give both.
            val markAxis = userWalls.map { m ->
                val pu = m.x * c + m.z * s; val pv = -m.x * s + m.z * c
                val du = (m.x - m.fromX) * c + (m.z - m.fromZ) * s; val dv = -(m.x - m.fromX) * s + (m.z - m.fromZ) * c
                if (abs(du) >= abs(dv)) { repeat(60) { us += pu }; Triple(0, pu, pv) } else { repeat(60) { vs += pv }; Triple(1, pv, pu) }
            }
            u = if (us.size >= 12) span(us) else null; v = if (vs.size >= 12) span(vs) else null
            segs = fitWalls(wall, c, s, u, v, markAxis)
            if (u == null || !u.complete) notes += "Only one wall seen along one direction: walk to the other side of the room to measure it, or point at the far wall and tap Mark wall."
            if (v == null || !v.complete) notes += "Only one wall seen across the room: turn to the walls you haven't scanned, or point at one and tap Mark wall."
        } else notes += "Walls not found yet: slowly sweep the camera across the walls, or point at one and tap Mark wall."

        // ---- Floor actually seen.
        val floorSeen = if (floorY == null) 0f else pts.filter { abs(it.y - floorY) <= 0.075f }.map { cellKey(it.x, it.z) }.toSet().size * CELL * CELL

        val have = listOf(floorY != null, ceilingY != null, u?.complete == true, v?.complete == true).count { it }
        val conf = when { floorY == null && have == 0 -> Confidence.LOW; have >= 4 && pts.size >= 8000 && floorSource != "plane" && ceilingSource == "depth" -> Confidence.HIGH; have >= 3 -> Confidence.MEDIUM; else -> Confidence.LOW }
        return RoomStats(floorY, ceilingY, heading, u, v, floorSeen, pts.size, conf, notes, floorSource, ceilingSource, segs)
    }

    /** The height at which many tall wall columns end together, or null. */
    private fun wallTops(pts: List<P3>, floorY: Float, camMaxY: Float?, minH: Float, maxH: Float): Float? {
        val top = HashMap<Long, Float>(); val bins = HashMap<Long, HashSet<Int>>()
        for (p in pts) if (p.y > floorY + 0.15f) { val k = cellKey(p.x, p.z); if ((top[k] ?: Float.NEGATIVE_INFINITY) < p.y) top[k] = p.y; bins.getOrPut(k) { HashSet() }.add(floor(p.y / BIN).toInt()) }
        val tall = top.filter { (bins[it.key]?.size ?: 0) >= 14 }
        if (tall.size < 40) return null
        val byBin = HashMap<Int, MutableList<Long>>(); for ((k, y) in tall) byBin.getOrPut(floor(y / BIN).toInt()) { ArrayList() } += k
        val best = byBin.entries.maxByOrNull { e -> (-1..1).sumOf { byBin[e.key + it]?.size ?: 0 } } ?: return null
        val members = (-1..1).flatMap { byBin[best.key + it].orEmpty() }
        if (members.size < max(25, (0.3f * tall.size).toInt())) return null
        val xs = members.map { (it shr 32).toInt() }; val zs = members.map { it.toInt() }
        if ((xs.max() - xs.min()) < 25 && (zs.max() - zs.min()) < 25) return null
        val y = (best.key + 0.5f) * BIN
        if (y - floorY < minH || y - floorY > maxH || (camMaxY != null && y < camMaxY + 0.1f)) return null
        return y
    }

    /**
     * A size, once measured wall to wall, does not shrink because the next measurement happened to see fewer walls: a wall you did not look at
     * this time is still there. Per direction, an earlier complete span is kept when the new one is missing, incomplete or more than 15% smaller;
     * a larger or similar complete one replaces it. Only between measurements of the same walls (headings within 12°); otherwise the new one wins.
     */
    fun keepBest(new: RoomStats, old: RoomStats?): RoomStats {
        if (old == null || abs(new.headingDeg - old.headingDeg) > 12f) return new
        fun pick(n: Span?, o: Span?): Span? = if (o != null && o.complete && (n == null || !n.complete || n.size < 0.85f * o.size)) o else n
        val u = pick(new.u, old.u); val v = pick(new.v, old.v)
        if (u === new.u && v === new.v) return new
        return new.copy(u = u, v = v, headingDeg = old.headingDeg, walls = (new.walls.filter { w -> (w.axis == 0 && u === new.u) || (w.axis == 1 && v === new.v) } + old.walls.filter { w -> (w.axis == 0 && u !== new.u) || (w.axis == 1 && v !== new.v) }), notes = new.notes.filterNot { it.startsWith("Only one wall seen") } + "Size kept from an earlier measurement of the same walls.")
    }

    /** Puts back a room height measured earlier when this scan could not see the ceiling: the ceiling does not move between scans. */
    fun holdHeight(s: RoomStats, height: Float?): RoomStats =
        if (height != null && s.ceilingY == null && s.floorY != null) s.copy(ceilingY = s.floorY + height, ceilingSource = "kept", notes = s.notes.filterNot { it.startsWith("Ceiling not confirmed") } + "Ceiling height kept from an earlier scan.") else s

    /** Each wall line as a surface over the stretch of it that has wall cells (3rd to 97th percentile along it), plus the ones you pointed at. */
    private fun fitWalls(cells: List<P3>, c: Float, s: Float, u: Span?, v: Span?, marks: List<Triple<Int, Float, Float>>): List<WallSeg> {
        val out = ArrayList<WallSeg>()
        fun line(axis: Int, pos: Float) {
            if (out.any { it.axis == axis && abs(it.pos - pos) < 0.3f }) return
            val along = cells.mapNotNull { p -> val pu = p.x * c + p.z * s; val pv = -p.x * s + p.z * c; if (axis == 0 && abs(pu - pos) <= 0.25f) pv else if (axis == 1 && abs(pv - pos) <= 0.25f) pu else null }.sorted()
            if (along.size >= 8) { val a0 = along[(along.size * 0.03f).toInt()]; val a1 = along[(along.size * 0.97f).toInt().coerceAtMost(along.size - 1)]; if (a1 - a0 >= 0.6f) out += WallSeg(axis, pos, a0, a1) }
        }
        u?.let { line(0, it.lo); if (it.hi - it.lo > 0.3f) line(0, it.hi) }
        v?.let { line(1, it.lo); if (it.hi - it.lo > 0.3f) line(1, it.hi) }
        for ((axis, pos, mid) in marks) if (out.none { it.axis == axis && abs(it.pos - pos) < 0.3f }) out += WallSeg(axis, pos, mid - 1.0f, mid + 1.0f)
        return out
    }

    /** The wall direction (0..90°): the angle at which the most wall cells line up in straight rows, counting both the direction and the one across it. */
    fun heading(cells: List<P3>): Float {
        val score = DoubleArray(180)
        for (deg in 0 until 180) {
            val a = Math.toRadians(deg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
            val h = HashMap<Int, Int>(); for (p in cells) h.merge(floor((p.x * c + p.z * s) / CELL).toInt(), 1, Int::plus)
            score[deg] = h.values.sumOf { it.toDouble() * it }
        }
        var best = 0; var bs = -1.0
        for (d in 0 until 90) { val s = score[d] + score[d + 90]; if (s > bs) { bs = s; best = d } }
        return best.toFloat()
    }

    /**
     * Wall to wall: the outermost row of wall cells on each side that is really a wall (a row holding a good share of the cells of the
     * strongest row; the walls running the other way put only a few cells in each row), 1.5 m or more apart. Otherwise only one wall
     * was seen and there is no size.
     */
    private fun span(values: List<Float>): Span? {
        val h = HashMap<Int, Int>(); for (x in values) h.merge(floor(x / CELL).toInt(), 1, Int::plus)
        // Strength over three neighbouring rows, because noise spreads a wall over more than one.
        fun s3(k: Int) = (h[k - 1] ?: 0) + (h[k] ?: 0) + (h[k + 1] ?: 0)
        val need = max(12, (0.35f * h.keys.maxOf { s3(it) }).toInt())
        val strong = h.keys.filter { s3(it) >= need }.sorted()
        if (strong.isEmpty()) return null
        /** Noise smears a wall over a few rows: its position is the weighted centre of the outermost run of strong rows (up to 40 cm deep). */
        fun wall(k: Int, inward: Int): Float { var s = 0f; var w = 0f; var j = k; var n = 0; while (j in strong && n < 4) { val c = (h[j] ?: 0).toFloat(); s += (j + 0.5f) * c; w += c; j += inward; n++ }; return if (w > 0f) s / w * CELL else (k + 0.5f) * CELL }
        val lo = wall(strong.first(), 1); val hi = wall(strong.last(), -1)
        return Span(lo, hi, hi - lo >= 1.5f)
    }
}
