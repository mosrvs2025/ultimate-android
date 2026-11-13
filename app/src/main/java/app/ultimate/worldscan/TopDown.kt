package app.ultimate.worldscan

import app.ultimate.room.RoomMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A picture of a map seen from above. Pixel (ix, iz) covers x in [x0 + ix*cell, x0 + (ix+1)*cell) and z likewise; row 0 is the smallest z. [argb] is 0 where nothing was seen. */
class Raster(val x0: Float, val z0: Float, val cell: Float, val w: Int, val h: Int, val argb: IntArray, val groundY: Float, val cubes: Int) {
    fun px(x: Float) = (x - x0) / cell
    fun pz(z: Float) = (z - z0) / cell
}

/**
 * Draws solid cubes from above, coloured by how tall the thing in each column is above the ground: pavement dark, kerbs and low walls
 * teal, people and cars green, building faces warm. This is the same evidence the 3D view uses, just easier to read for a street-sized
 * map. The ground height is estimated from the data (a low percentile of column bottoms), not assumed. Pure, so it is tested.
 */
object TopDown {
    fun render(maps: List<RoomMap>, cell: Float = 0.25f, window: FloatArray? = null, maxCells: Int = 1_200_000, guard: Boolean = false): Raster? {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE; var n = 0
        // window = [centreX, centreZ, halfSize] limits the picture to a square around a point (the live minimap).
        fun inWin(x: Float, z: Float) = window == null || (kotlin.math.abs(x - window[0]) <= window[2] && kotlin.math.abs(z - window[1]) <= window[2])
        for (m in maps) each(m, guard) { x, _, z -> if (inWin(x, z)) { n++; if (x < minX) minX = x; if (x > maxX) maxX = x; if (z < minZ) minZ = z; if (z > maxZ) maxZ = z } }
        if (n == 0) return null
        var c = cell
        while ((ceil((maxX - minX) / c) + 1) * (ceil((maxZ - minZ) / c) + 1) > maxCells) c *= 1.5f
        val w = ceil((maxX - minX) / c).toInt() + 1; val h = ceil((maxZ - minZ) / c).toInt() + 1
        val lo = FloatArray(w * h) { Float.MAX_VALUE }; val hi = FloatArray(w * h) { -Float.MAX_VALUE }; val cnt = IntArray(w * h)
        for (m in maps) each(m, guard) { x, y, z ->
            if (inWin(x, z)) { val i = ((z - minZ) / c).toInt().coerceIn(0, h - 1) * w + ((x - minX) / c).toInt().coerceIn(0, w - 1); if (y < lo[i]) lo[i] = y; if (y > hi[i]) hi[i] = y; cnt[i]++ }
        }
        val lows = ArrayList<Float>(); for (i in lo.indices) if (cnt[i] > 0) lows += lo[i]
        lows.sort(); val ground = lows[(lows.size * 0.1f).toInt().coerceIn(0, lows.size - 1)]
        val out = IntArray(w * h)
        for (i in out.indices) if (cnt[i] > 0) out[i] = colour(hi[i] - ground, cnt[i])
        return Raster(minX, minZ, c, w, h, out, ground, n)
    }

    /** Visits the solid cubes of [m], holding the map's lock while doing so when [guard] (a map that a scan is still writing to). */
    private inline fun each(m: RoomMap, guard: Boolean, crossinline f: (Float, Float, Float) -> Unit) {
        if (guard) synchronized(m) { m.forEachSolid { x, y, z -> f(x, y, z) } } else m.forEachSolid { x, y, z -> f(x, y, z) }
    }

    /** Colour for a column [height] metres tall seen by [count] cubes: hue by height, opacity by how much evidence there is. */
    fun colour(height: Float, count: Int): Int {
        val a = (110 + min(count, 6) * 24).coerceIn(0, 255)
        val (r, g, b) = when {
            height < 0.25f -> Triple(70, 92, 120)
            height < 0.9f -> Triple(40, 190, 190)
            height < 2.2f -> Triple(70, 220, 130)
            height < 4.5f -> Triple(250, 205, 90)
            else -> Triple(255, 120, 150)
        }
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Bounding box of the solid cubes of [maps] on the ground plane: minX, minZ, maxX, maxZ, or null for empty maps. */
    fun bounds(maps: List<RoomMap>): FloatArray? {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE; var any = false
        for (m in maps) m.forEachSolid { x, _, z -> any = true; minX = min(minX, x); maxX = max(maxX, x); minZ = min(minZ, z); maxZ = max(maxZ, z) }
        return if (any) floatArrayOf(minX, minZ, maxX, maxZ) else null
    }

}
