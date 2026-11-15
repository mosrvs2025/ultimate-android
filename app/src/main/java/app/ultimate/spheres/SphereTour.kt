package app.ultimate.spheres

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A place captured as 360° spheres you can step between, like your own Street View of a backyard or a home. Each stop is a
 * panorama taken standing still, with where it stood in the tracking's world (metres; +Y up) so neighbouring stops can be
 * shown in the right direction and at the right distance from each other.
 */
data class SphereTour(val id: String, val name: String, val createdAt: Long, val stops: List<Stop>) {
    data class Stop(val id: String, val x: Double, val y: Double, val z: Double, val coverage: Double, val at: Long, val lat: Double? = null, val lon: Double? = null)

    /** Where [to] appears from [from]: its direction as a panorama longitude (degrees, 0 = the panorama's centre), how far, and how far up/down. */
    data class Link(val to: String, val lonDeg: Double, val metres: Double, val pitchDeg: Double)

    /**
     * The stops worth showing as doorways from [from]: every stop within [reach] metres, and always the nearest few so a
     * spread-out tour never strands you.
     */
    fun links(from: String, reach: Double = 8.0, atLeast: Int = 2): List<Link> {
        val a = stops.firstOrNull { it.id == from } ?: return emptyList()
        val others = stops.filter { it.id != from }.map { b -> b to hypot(b.x - a.x, b.z - a.z) }.sortedBy { it.second }
        return others.filterIndexed { i, (_, d) -> d <= reach || i < atLeast }.map { (b, d) ->
            Link(b.id, Math.toDegrees(atan2(b.x - a.x, -(b.z - a.z))), d, Math.toDegrees(atan2(b.y - a.y, d.coerceAtLeast(0.01))))
        }
    }

    fun toJson(): String = JSONObject().put("id", id).put("name", name).put("createdAt", createdAt).put("stops", JSONArray().apply {
        stops.forEach { s -> put(JSONObject().put("id", s.id).put("x", s.x).put("y", s.y).put("z", s.z).put("coverage", s.coverage).put("at", s.at)
            .apply { s.lat?.let { put("lat", it) }; s.lon?.let { put("lon", it) } }) }
    }).toString()

    /** The tour plus each stop's doorways, as the viewer page reads it. */
    fun toViewerJson(): String = JSONObject(toJson()).put("links", JSONObject().apply { stops.forEach { s ->
        put(s.id, JSONArray().apply { links(s.id).forEach { l -> put(JSONObject().put("to", l.to).put("lon", l.lonDeg).put("m", l.metres).put("pitch", l.pitchDeg)) } })
    } }).toString()

    companion object {
        fun fromJson(text: String): SphereTour? = runCatching {
            val o = JSONObject(text); val a = o.getJSONArray("stops")
            SphereTour(o.getString("id"), o.optString("name", "Place"), o.optLong("createdAt"), (0 until a.length()).map { i ->
                val s = a.getJSONObject(i)
                Stop(s.getString("id"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), s.optDouble("coverage", 0.0), s.optLong("at"),
                    if (s.has("lat")) s.getDouble("lat") else null, if (s.has("lon")) s.getDouble("lon") else null)
            })
        }.getOrNull()

        /**
         * Where to stand next: about [step] metres ahead of where you're facing ([yaw], radians, 0 = the world's -Z), turned
         * in 45° steps until it is at least [spacing] metres from every stop already taken. Returns (x, z).
         */
        fun nextSpot(stops: List<Stop>, x: Double, z: Double, yaw: Double, step: Double = 2.0, spacing: Double = 1.3): DoubleArray {
            for (k in listOf(0, 1, -1, 2, -2, 3, -3, 4)) {
                val a = yaw + k * PI / 4
                val nx = x + step * sin(a); val nz = z - step * cos(a)
                if (stops.all { hypot(it.x - nx, it.z - nz) >= spacing }) return doubleArrayOf(nx, nz)
            }
            return doubleArrayOf(x + step * 1.5 * sin(yaw), z - step * 1.5 * cos(yaw))
        }
    }
}
