package app.ultimate.fusion

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Street-map tiles (the usual web-map squares, 256 pixels each, zoom 0 = the whole world in one) and the route's own
 * metres-east/metres-north frame, tied together through the first GPS fix. Pure arithmetic, tested.
 */
object WebMercator {
    const val R = 6_371_000.0
    const val TILE = 256

    data class Tile(val z: Int, val x: Int, val y: Int)

    fun lonOf(x: Double, z: Int) = x / (1 shl z) * 360.0 - 180.0
    fun latOf(y: Double, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))
    /** Fractional tile coordinates of a place at zoom [z]. */
    fun tileX(lon: Double, z: Int) = (lon + 180.0) / 360.0 * (1 shl z)
    fun tileY(lat: Double, z: Int): Double { val r = Math.toRadians(lat); return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z) }

    /** Metres east and north of [lat0], [lon0] (a small-area flat approximation, good to centimetres over a few kilometres). */
    fun toLocal(lat: Double, lon: Double, lat0: Double, lon0: Double) =
        doubleArrayOf(Math.toRadians(lon - lon0) * R * cos(Math.toRadians(lat0)), Math.toRadians(lat - lat0) * R)

    fun toGeo(e: Double, n: Double, lat0: Double, lon0: Double) =
        doubleArrayOf(lat0 + Math.toDegrees(n / R), lon0 + Math.toDegrees(e / (R * cos(Math.toRadians(lat0)))))

    /** The zoom whose tile pixels are about the size of screen pixels at [pxPerMetre]. */
    fun zoomFor(pxPerMetre: Double, lat: Double): Int {
        val metresPerTilePxAtZ0 = 2 * PI * R * cos(Math.toRadians(lat)) / TILE
        return (ln(metresPerTilePxAtZ0 * pxPerMetre) / ln(2.0)).let { if (it.isFinite()) floor(it + 0.3).toInt() else 3 }.coerceIn(3, 19)
    }

    /** The tiles at zoom [z] covering a box of local metres (west, south, east, north) around [lat0], [lon0], at most [cap]. */
    fun tilesFor(w: Double, s: Double, e: Double, n: Double, lat0: Double, lon0: Double, z: Int, cap: Int = 80): List<Tile> {
        val sw = toGeo(w, s, lat0, lon0); val ne = toGeo(e, n, lat0, lon0)
        val x0 = floor(tileX(sw[1], z)).toInt(); val x1 = floor(tileX(ne[1], z)).toInt()
        val y0 = floor(tileY(ne[0], z)).toInt(); val y1 = floor(tileY(sw[0], z)).toInt()
        val maxI = (1 shl z) - 1
        val out = ArrayList<Tile>()
        for (y in max(0, y0)..min(maxI, y1)) for (x in x0..x1) { out += Tile(z, ((x % (maxI + 1)) + maxI + 1) % (maxI + 1), y); if (out.size >= cap) return out }
        return out
    }

    /** A tile's four corners in local metres: north-west, north-east, south-east, south-west. */
    fun corners(t: Tile, lat0: Double, lon0: Double): List<DoubleArray> = listOf(
        toLocal(latOf(t.y.toDouble(), t.z), lonOf(t.x.toDouble(), t.z), lat0, lon0),
        toLocal(latOf(t.y.toDouble(), t.z), lonOf(t.x + 1.0, t.z), lat0, lon0),
        toLocal(latOf(t.y + 1.0, t.z), lonOf(t.x + 1.0, t.z), lat0, lon0),
        toLocal(latOf(t.y + 1.0, t.z), lonOf(t.x.toDouble(), t.z), lat0, lon0),
    )
}

/**
 * A camera over the route: looking at [centreE], [centreN] (metres), [scale] screen pixels per metre, turned by [bearing]
 * (radians, the direction at the top of the screen), and tilted back by [tilt] (0 = straight down, up to about 1 radian)
 * so the ground recedes in perspective. Heights ([up], metres, exaggerated by [lift]) rise towards the viewer.
 */
data class MapCamera(val centreE: Double, val centreN: Double, val scale: Double, val bearing: Double = 0.0, val tilt: Double = 0.0, val lift: Double = 1.0) {
    /** Screen position of a point, given the screen centre; null when it falls behind the camera. */
    fun project(e: Double, n: Double, up: Double, cx: Float, cy: Float, depthPx: Double): FloatArray? {
        val dx = e - centreE; val dy = n - centreN
        // Turn so that the direction [bearing] (clockwise from north) points up the screen.
        val c = cos(bearing); val s = sin(bearing)
        val u = (dx * c - dy * s) * scale; val v = (dx * s + dy * c) * scale; val w = up * lift * scale
        val yUp = v * cos(tilt) + w * sin(tilt)
        val depth = v * sin(tilt) - w * cos(tilt)
        if (depthPx + depth <= depthPx * 0.15) return null
        val k = depthPx / (depthPx + depth)
        return floatArrayOf((cx + u * k).toFloat(), (cy - yUp * k).toFloat(), k.toFloat())
    }

    /** Where on the ground (height 0) a screen point looks, in metres; null above the horizon. */
    fun ground(sx: Float, sy: Float, cx: Float, cy: Float, depthPx: Double): DoubleArray? {
        val kUp = (cy - sy).toDouble()
        val den = depthPx * cos(tilt) - kUp * sin(tilt)
        if (den <= depthPx * 0.05) return null
        val v = kUp * depthPx / den
        val u = (sx - cx) * (depthPx + v * sin(tilt)) / depthPx
        val c = cos(bearing); val s = sin(bearing)
        val dx = (u * c + v * s) / scale; val dy = (-u * s + v * c) / scale
        return doubleArrayOf(centreE + dx, centreN + dy)
    }
}
