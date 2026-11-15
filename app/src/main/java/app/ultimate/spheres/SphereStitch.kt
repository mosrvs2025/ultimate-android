package app.ultimate.spheres

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds a 360° picture (equirectangular: across = all the way round, down = from straight up to straight down) from
 * ordinary camera frames, using where the camera was pointing for each one. ARCore's motion tracking knows that to a
 * fraction of a degree, so each camera pixel is put straight where it belongs on the sphere: no feature matching, which is
 * what makes phone panoramas fail on lawns, sky and blank walls. Where frames overlap they are blended with soft edges so
 * seams fade instead of showing.
 *
 * Directions use ARCore's world: +Y up, and the panorama's centre column looks along -Z (where the camera faced when
 * tracking began). Camera frames: +X right, +Y up, looking along -Z; image pixels: x right, y down.
 */
class SphereStitch(val width: Int = 1600, val height: Int = 800) {
    private val r = FloatArray(width * height); private val g = FloatArray(width * height); private val b = FloatArray(width * height)
    private val w = FloatArray(width * height)
    /** Coarse map of which directions are filled: 36 columns (10° each) by 18 rows. */
    val bins = BooleanArray(36 * 18)

    /** Pinhole camera intrinsics for the frame's own pixel size. */
    data class Lens(val fx: Double, val fy: Double, val cx: Double, val cy: Double, val width: Int, val height: Int)

    /** Rotation as a unit quaternion (x, y, z, w), camera → world. */
    data class Turn(val x: Double, val y: Double, val z: Double, val w: Double) {
        fun apply(v: DoubleArray): DoubleArray {
            // v' = q v q*, written out.
            val ix = w * v[0] + y * v[2] - z * v[1]; val iy = w * v[1] + z * v[0] - x * v[2]
            val iz = w * v[2] + x * v[1] - y * v[0]; val iw = -x * v[0] - y * v[1] - z * v[2]
            return doubleArrayOf(ix * w + iw * -x + iy * -z - iz * -y, iy * w + iw * -y + iz * -x - ix * -z, iz * w + iw * -z + ix * -y - iy * -x)
        }
        fun inverse() = Turn(-x, -y, -z, w)
        companion object {
            /** A turn of [rad] about a unit axis. */
            fun about(ax: Double, ay: Double, az: Double, rad: Double): Turn { val s = sin(rad / 2); return Turn(ax * s, ay * s, az * s, cos(rad / 2)) }
            fun of(a: Turn, b: Turn) = Turn( // a then b: b * a
                b.w * a.x + b.x * a.w + b.y * a.z - b.z * a.y, b.w * a.y - b.x * a.z + b.y * a.w + b.z * a.x,
                b.w * a.z + b.x * a.y - b.y * a.x + b.z * a.w, b.w * a.w - b.x * a.x - b.y * a.y - b.z * a.z)
        }
    }

    companion object {
        /** Panorama column/row (fractional) for a world direction. */
        fun toPano(d: DoubleArray, width: Int, height: Int): DoubleArray {
            val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
            val lon = atan2(d[0], -d[2]); val lat = asin((d[1] / len).coerceIn(-1.0, 1.0))
            return doubleArrayOf((lon + PI) / (2 * PI) * width, (PI / 2 - lat) / PI * height)
        }
        /** World direction (unit) for a panorama column/row. */
        fun fromPano(px: Double, py: Double, width: Int, height: Int): DoubleArray {
            val lon = px / width * 2 * PI - PI; val lat = PI / 2 - py / height * PI
            return doubleArrayOf(cos(lat) * sin(lon), sin(lat), -cos(lat) * cos(lon))
        }
    }

    /**
     * Adds one camera frame ([argb], [lens].width × height) taken with rotation [turn] (camera → world). Only the part of the
     * panorama the frame can see is visited. Returns how many panorama pixels it added to.
     */
    fun add(argb: IntArray, lens: Lens, turn: Turn): Int {
        val inv = turn.inverse()
        // The frame's footprint on the panorama, from its border (plus a check for a pole inside the frame).
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        val xs = ArrayList<Double>()
        val steps = 16
        for (k in 0..steps) for (edge in 0 until 4) {
            val f = k.toDouble() / steps
            val (u, v) = when (edge) { 0 -> f * lens.width to 0.0; 1 -> lens.width.toDouble() to f * lens.height; 2 -> f * lens.width to lens.height.toDouble(); else -> 0.0 to f * lens.height }
            val p = toPano(turn.apply(camDir(u, v, lens)), width, height)
            xs += p[0]; minY = min(minY, p[1]); maxY = max(maxY, p[1])
        }
        val up = project(inv.apply(doubleArrayOf(0.0, 1.0, 0.0)), lens); val down = project(inv.apply(doubleArrayOf(0.0, -1.0, 0.0)), lens)
        val fullTurn = up != null || down != null
        if (up != null) minY = 0.0
        if (down != null) maxY = height.toDouble()
        // Longitude: the smallest arc that holds every border point (it may wrap past the back seam).
        val sorted = xs.sorted(); var gap = 0.0; var gapAt = 0
        for (i in sorted.indices) { val nx = if (i + 1 < sorted.size) sorted[i + 1] else sorted[0] + width; if (nx - sorted[i] > gap) { gap = nx - sorted[i]; gapAt = i } }
        if (fullTurn || gap < width * 0.25) { minX = 0.0; maxX = width.toDouble() } else { minX = sorted[(gapAt + 1) % sorted.size]; maxX = sorted[gapAt] + if (gapAt + 1 >= sorted.size) 0 else width }
        val y0 = floor(minY).toInt().coerceIn(0, height - 1); val y1 = kotlin.math.ceil(maxY).toInt().coerceIn(0, height - 1)
        val x0 = floor(minX).toInt(); val x1 = kotlin.math.ceil(maxX).toInt()
        val feather = 0.18 * min(lens.width, lens.height)
        var touched = 0
        for (py in y0..y1) for (pxw in x0..x1) {
            val px = ((pxw % width) + width) % width
            val c = project(inv.apply(fromPano(px + 0.5, py + 0.5, width, height)), lens) ?: continue
            val u = c[0]; val v = c[1]
            if (u < 0 || v < 0 || u > lens.width - 1 || v > lens.height - 1) continue
            val edge = min(min(u, lens.width - 1 - u), min(v, lens.height - 1 - v))
            val wt = (edge / feather).coerceIn(0.02, 1.0).let { it * it * (3 - 2 * it) }.toFloat()
            val col = sample(argb, lens.width, lens.height, u, v)
            val i = py * width + px
            r[i] += wt * col[0]; g[i] += wt * col[1]; b[i] += wt * col[2]; w[i] += wt
            bins[(py * 18 / height) * 36 + (px * 36 / width)] = true
            touched++
        }
        return touched
    }

    /** How much of the sphere is filled, 0..1 (by the coarse bins, near the poles counting less as they are smaller). */
    fun coverage(): Double {
        var got = 0.0; var all = 0.0
        for (row in 0 until 18) { val area = cos(((row + 0.5) / 18 - 0.5) * PI); for (col in 0 until 36) { all += area; if (bins[row * 36 + col]) got += area } }
        return got / all
    }

    /** The largest unfilled direction, as (longitude, latitude) in degrees, to guide the person; null when it is all filled. */
    fun biggestGap(): DoubleArray? {
        var best = -1; var bestScore = 0.0
        for (row in 2 until 16) for (col in 0 until 36) {
            if (bins[row * 36 + col]) continue
            var n = 0.0
            for (dr in -2..2) for (dc in -3..3) { val rr = row + dr; val cc = ((col + dc) % 36 + 36) % 36; if (rr in 0..17 && !bins[rr * 36 + cc]) n += 1.0 }
            val score = n * cos(((row + 0.5) / 18 - 0.5) * PI)
            if (score > bestScore) { bestScore = score; best = row * 36 + col }
        }
        if (best < 0) return null
        return doubleArrayOf((best % 36 + 0.5) * 10 - 180, 90 - (best / 36 + 0.5) * 10)
    }

    /** The panorama as ARGB pixels; unfilled parts become a soft dark that brightens towards the horizon, so gaps read as mist. */
    fun pixels(): IntArray = IntArray(width * height) { i ->
        if (w[i] > 0f) {
            val k = 1f / w[i]
            (0xFF shl 24) or ((r[i] * k).toInt().coerceIn(0, 255) shl 16) or ((g[i] * k).toInt().coerceIn(0, 255) shl 8) or (b[i] * k).toInt().coerceIn(0, 255)
        } else {
            val y = i / width; val haze = (1 - abs(y.toDouble() / height - 0.5) * 2).coerceIn(0.0, 1.0)
            val v = (18 + 22 * haze).toInt(); (0xFF shl 24) or (v shl 16) or ((v + 6) shl 8) or (v + 12)
        }
    }

    private fun camDir(u: Double, v: Double, l: Lens) = doubleArrayOf((u - l.cx) / l.fx, -(v - l.cy) / l.fy, -1.0)

    /** Image position of a camera-frame direction, or null when it points behind the camera. */
    private fun project(d: DoubleArray, l: Lens): DoubleArray? {
        if (d[2] >= -1e-6) return null
        val z = -d[2]
        return doubleArrayOf(l.cx + l.fx * d[0] / z, l.cy - l.fy * d[1] / z)
    }

    private fun sample(px: IntArray, w: Int, h: Int, u: Double, v: Double): FloatArray {
        val x0 = u.toInt().coerceIn(0, w - 2); val y0 = v.toInt().coerceIn(0, h - 2); val fx = (u - x0).toFloat(); val fy = (v - y0).toFloat()
        val out = FloatArray(3)
        for (ch in 0 until 3) {
            val sh = 16 - 8 * ch
            fun c(x: Int, y: Int) = ((px[y * w + x] shr sh) and 0xFF).toFloat()
            out[ch] = (c(x0, y0) * (1 - fx) + c(x0 + 1, y0) * fx) * (1 - fy) + (c(x0, y0 + 1) * (1 - fx) + c(x0 + 1, y0 + 1) * fx) * fy
        }
        return out
    }
}
