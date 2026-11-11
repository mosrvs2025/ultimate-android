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

/** A flat horizontal surface ARCore found by itself from the camera picture (a second opinion on where the floor is). [area] in square metres. */
data class PlaneHint(val y: Float, val area: Float, val upward: Boolean)

/**
 * What a scan measured about the space. Anything not actually established is null (never a guess), and [notes] say in plain words what
 * is missing and what to do about it. [headingDeg] is the direction of the walls; [u] and [v] are the extents along and across it.
 */
data class RoomStats(
    val floorY: Float?, val ceilingY: Float?, val headingDeg: Float, val u: Span?, val v: Span?,
    val floorSeen: Float, val voxels: Int, val confidence: Confidence, val notes: List<String> = emptyList(), val floorSource: String = "",
) {
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

    fun measure(pts: List<P3>, camMinY: Float? = null, camMaxY: Float? = null, planes: List<PlaneHint> = emptyList(), maxCeilingHeight: Float = 4.6f, minCeilingHeight: Float = 1.8f): RoomStats? {
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
        if (floorY == null) notes += "Floor not found yet: point the camera at the floor and move a little."

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
        val ceilingY = if (ceilI >= 0 && floorY != null) locate(ceilI) else null
        if (ceilingY == null && floorY != null) notes += "Ceiling not confirmed: tilt up to the ceiling."
        if (ceilingY != null && smooth.indices.any { yOf(it) > ceilingY + 0.25f && smooth[it] >= ceilNeed && isPeak(it) }) notes += "Readings above the ceiling (a window, mirror or glare?) were ignored."

        // ---- Walls: tall vertical columns, independent of the floor.
        val lowY = floorY?.plus(0.15f) ?: camMinY?.minus(1.9f) ?: Float.NEGATIVE_INFINITY
        val highY = ceilingY?.minus(0.1f) ?: camMaxY?.plus(1.2f) ?: Float.POSITIVE_INFINITY
        val cols = HashMap<Long, HashSet<Int>>()
        for (p in pts) if (p.y in lowY..highY) cols.getOrPut(cellKey(p.x, p.z)) { HashSet() }.add(floor(p.y / BIN).toInt())
        val wall = cols.filter { it.value.size >= 14 }.keys.map { P3(((it shr 32).toInt() + 0.5f) * CELL, 0f, ((it and 0xFFFFFFFFL).toInt() + 0.5f) * CELL) }
        var heading = 0f; var u: Span? = null; var v: Span? = null
        if (wall.size >= 25) {
            heading = heading(wall)
            val a = Math.toRadians(heading.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
            u = span(wall.map { it.x * c + it.z * s }); v = span(wall.map { -it.x * s + it.z * c })
            if (u == null || !u.complete) notes += "Only one wall seen along one direction: walk to the other side of the room to measure it."
            if (v == null || !v.complete) notes += "Only one wall seen across the room: turn to the walls you haven't scanned."
        } else notes += "Walls not found yet: slowly sweep the camera across the walls."

        // ---- Floor actually seen.
        val floorSeen = if (floorY == null) 0f else pts.filter { abs(it.y - floorY) <= 0.075f }.map { cellKey(it.x, it.z) }.toSet().size * CELL * CELL

        val have = listOf(floorY != null, ceilingY != null, u?.complete == true, v?.complete == true).count { it }
        val conf = when { floorY == null && have == 0 -> Confidence.LOW; have >= 4 && pts.size >= 8000 && floorSource != "plane" -> Confidence.HIGH; have >= 3 -> Confidence.MEDIUM; else -> Confidence.LOW }
        return RoomStats(floorY, ceilingY, heading, u, v, floorSeen, pts.size, conf, notes, floorSource)
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
        val peak = h.values.max(); val need = max(8, (0.35f * peak).toInt())
        val strong = h.keys.filter { (h[it] ?: 0) >= need }.sorted()
        if (strong.isEmpty()) return null
        /** A wall smeared over two rows by noise: its position is the weighted centre of the outermost strong row and its strong neighbour. */
        fun wall(k: Int, inward: Int): Float { val a = h[k] ?: 0; val b = if ((k + inward) in strong) h[k + inward] ?: 0 else 0; return ((k + 0.5f) * a + (k + inward + 0.5f) * b) / (a + b) * CELL }
        val lo = wall(strong.first(), 1); val hi = wall(strong.last(), -1)
        return Span(lo, hi, hi - lo >= 1.5f)
    }
}
