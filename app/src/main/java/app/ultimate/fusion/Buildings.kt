package app.ultimate.fusion

import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Building outlines from OpenStreetMap for the 3D map, and one use of them: a walk that starts inside the house because GPS
 * smears a few metres across a wall. Pure geometry and parsing, tested with made-up buildings; the phone's downloading is
 * elsewhere and only happens after the owner allows it.
 */
object Buildings {
    /** One building: its outline in metres east/north of the map's origin, and how tall it is. */
    class Building(val ring: List<DoubleArray>, val height: Double) {
        val minE = ring.minOf { it[0] }; val maxE = ring.maxOf { it[0] }
        val minN = ring.minOf { it[1] }; val maxN = ring.maxOf { it[1] }
    }

    /** The question to ask the Overpass service for every building in a box. */
    fun query(south: Double, west: Double, north: Double, east: Double): String =
        "[out:json][timeout:20];way[\"building\"](${"%.5f".format(java.util.Locale.US, south)},${"%.5f".format(java.util.Locale.US, west)},${"%.5f".format(java.util.Locale.US, north)},${"%.5f".format(java.util.Locale.US, east)});out geom 600;"

    /**
     * How tall a building is: its own height tag if it has one, else its number of levels (about 3 m each plus a roof), else a guess
     * from what kind of building it is (a shed is not a tower block). Never taller than 150 m or shorter than 2.
     */
    fun heightOf(tags: JSONObject): Double {
        tags.optString("height").let { h -> Regex("""^\s*([0-9]+(?:\.[0-9]+)?)""").find(h)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return it.coerceIn(2.0, 150.0) } }
        tags.optString("building:levels").toDoubleOrNull()?.let { return (it * 3.0 + 0.8).coerceIn(2.0, 150.0) }
        return when (tags.optString("building")) {
            "shed", "garage", "garages", "carport", "roof", "hut", "cabin", "greenhouse", "kiosk" -> 3.0
            "house", "detached", "residential", "semidetached_house", "terrace", "bungalow", "yes", "" -> 5.5
            "apartments", "commercial", "retail", "office", "hotel", "hospital", "school", "university", "industrial", "warehouse", "church", "cathedral" -> 12.0
            else -> 6.0
        }
    }

    /** Buildings in an Overpass `out geom` answer, placed in metres around ([lat0], [lon0]). Damaged elements are skipped. */
    fun parse(json: String, lat0: Double, lon0: Double): List<Building> = runCatching {
        val els = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        (0 until els.length()).mapNotNull { i -> runCatching {
            val el = els.getJSONObject(i); if (el.optString("type") != "way") return@runCatching null
            val g = el.optJSONArray("geometry") ?: return@runCatching null
            if (g.length() < 4) return@runCatching null
            val ring = (0 until g.length()).map { k -> g.getJSONObject(k).let { WebMercator.toLocal(it.getDouble("lat"), it.getDouble("lon"), lat0, lon0) } }
            // Overpass repeats the first point at the end; the geometry here doesn't want it.
            val open = if (hypot(ring.first()[0] - ring.last()[0], ring.first()[1] - ring.last()[1]) < 0.01) ring.dropLast(1) else ring
            if (open.size < 3) null else Building(open, heightOf(el.optJSONObject("tags") ?: JSONObject()))
        }.getOrNull() }
    }.getOrDefault(emptyList())

    fun contains(b: Building, e: Double, n: Double): Boolean {
        if (e < b.minE || e > b.maxE || n < b.minN || n > b.maxN) return false
        var inside = false; var j = b.ring.size - 1
        for (i in b.ring.indices) {
            val a = b.ring[i]; val c = b.ring[j]
            if ((a[1] > n) != (c[1] > n) && e < (c[0] - a[0]) * (n - a[1]) / (c[1] - a[1]) + a[0]) inside = !inside
            j = i
        }
        return inside
    }

    /** The point on [b]'s edge closest to (e, n), and how far it is. */
    fun nearestEdge(b: Building, e: Double, n: Double): Pair<DoubleArray, Double> {
        var best = doubleArrayOf(b.ring[0][0], b.ring[0][1]); var bd = Double.MAX_VALUE
        for (i in b.ring.indices) {
            val a = b.ring[i]; val c = b.ring[(i + 1) % b.ring.size]
            val dx = c[0] - a[0]; val dy = c[1] - a[1]; val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-12) 0.0 else (((e - a[0]) * dx + (n - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
            val px = a[0] + t * dx; val py = a[1] + t * dy; val d = hypot(e - px, n - py)
            if (d < bd) { bd = d; best = doubleArrayOf(px, py) }
        }
        return best to bd
    }

    /**
     * A route drawn through a house is usually GPS smeared across a wall, not a person walking through it. When only a minority
     * of the route's points (up to [MOST]) fall inside buildings, those points are moved just outside the nearest wall, for the
     * drawing only (distance and speed are untouched). If most of it is inside, the person really was indoors and nothing moves.
     * Returns the same arrays when nothing needed moving.
     */
    fun tidy(e: DoubleArray, n: DoubleArray, buildings: List<Building>, margin: Double = 0.6): Pair<DoubleArray, DoubleArray> {
        if (buildings.isEmpty() || e.isEmpty()) return e to n
        val hit = IntArray(e.size) { -1 }; var count = 0
        for (i in e.indices) { val k = buildings.indexOfFirst { contains(it, e[i], n[i]) }; if (k >= 0) { hit[i] = k; count++ } }
        if (count == 0 || count > e.size * MOST) return e to n
        val oe = e.copyOf(); val on = n.copyOf()
        for (i in e.indices) if (hit[i] >= 0) {
            val (p, d) = nearestEdge(buildings[hit[i]], e[i], n[i])
            val len = max(d, 1e-6); val ux = (p[0] - e[i]) / len; val uy = (p[1] - n[i]) / len
            oe[i] = p[0] + ux * margin; on[i] = p[1] + uy * margin
        }
        return oe to on
    }

    /** The most of a route that may lie inside buildings for it to count as smeared GPS rather than someone indoors. */
    const val MOST = 0.35

    /** A grid-snapped box (about 220 m cells) around a route plus [pad] metres, so the same yard asks for the same box every time. Null if it would be huge. */
    fun box(lat0: Double, lon0: Double, minE: Double, maxE: Double, minN: Double, maxN: Double, pad: Double = 40.0): DoubleArray? {
        val mLat = 111_320.0; val mLon = 111_320.0 * Math.cos(Math.toRadians(lat0))
        val grid = 0.002
        fun down(v: Double) = Math.floor(v / grid) * grid
        fun up(v: Double) = Math.ceil(v / grid) * grid
        val s = down(lat0 + (minN - pad) / mLat); val n = up(lat0 + (maxN + pad) / mLat)
        val w = down(lon0 + (minE - pad) / mLon); val e = up(lon0 + (maxE + pad) / mLon)
        if (n - s > 0.012 || e - w > 0.016) return null
        return doubleArrayOf(s, w, n, e)
    }
}
