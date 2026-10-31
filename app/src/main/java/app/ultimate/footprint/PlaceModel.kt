package app.ultimate.footprint

import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One photo that carries a position: when it was taken and where. */
data class Shot(val at: Long, val lat: Double, val lon: Double)

/** A place you were photographed at: where, how often, and when. */
data class Place(val lat: Double, val lon: Double, val count: Int, val first: Long, val last: Long, val nightShots: Int)

object PlaceMath {
    private const val EARTH_KM = 6371.0

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return EARTH_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun isNight(at: Long, zone: TimeZone): Boolean {
        val hour = (Math.floorMod(at + zone.getOffset(at), 86_400_000L) / 3_600_000L).toInt()
        return hour >= 22 || hour < 6
    }

    /** Groups shots into places: a shot within [radiusKm] of a place's centre joins it, and the centre follows the average. */
    fun cluster(shots: List<Shot>, radiusKm: Double = 1.0, zone: TimeZone = TimeZone.getDefault()): List<Place> {
        class Acc(var lat: Double, var lon: Double, var n: Int, var first: Long, var last: Long, var night: Int)
        val places = ArrayList<Acc>()
        for (s in shots.sortedBy { it.at }) {
            val near = places.filter { distanceKm(it.lat, it.lon, s.lat, s.lon) <= radiusKm }.minByOrNull { distanceKm(it.lat, it.lon, s.lat, s.lon) }
            val night = if (isNight(s.at, zone)) 1 else 0
            if (near == null) places += Acc(s.lat, s.lon, 1, s.at, s.at, night)
            else { near.lat = (near.lat * near.n + s.lat) / (near.n + 1); near.lon = (near.lon * near.n + s.lon) / (near.n + 1); near.n++; near.last = s.at; near.night += night }
        }
        return places.map { Place(it.lat, it.lon, it.n, it.first, it.last, it.night) }.sortedByDescending { it.count }
    }

    /** Where you most likely live: the place with the most night-time photos, or, with none at night, the place with the most photos. */
    fun home(places: List<Place>): Place? = places.maxWithOrNull(compareBy<Place> { it.nightShots }.thenBy { it.count })?.let { best -> if (best.nightShots > 0) best else places.maxByOrNull { it.count } }

    /** The number of different days you took a photo farther than [km] from [home]. */
    fun daysAway(shots: List<Shot>, home: Place, km: Double = 50.0, zone: TimeZone = TimeZone.getDefault()): Int =
        shots.filter { distanceKm(home.lat, home.lon, it.lat, it.lon) > km }.map { Math.floorDiv(it.at + zone.getOffset(it.at), 86_400_000L) }.toSet().size

    fun farthest(places: List<Place>, home: Place): Pair<Place, Double>? =
        places.map { it to distanceKm(home.lat, home.lon, it.lat, it.lon) }.maxByOrNull { it.second }?.takeIf { it.second > 1.0 }

    fun degrees(lat: Double, lon: Double) = "%.2f°%s %.2f°%s".format(Math.abs(lat), if (lat >= 0) "N" else "S", Math.abs(lon), if (lon >= 0) "E" else "W")

    fun sentences(shots: List<Shot>, places: List<Place>, zone: TimeZone = TimeZone.getDefault()): List<String> = buildList {
        if (places.isEmpty()) return@buildList
        add("Your photos put you in ${places.size} different place${if (places.size == 1) "" else "s"}.")
        val home = home(places) ?: return@buildList
        add("Your phone's photos put your home near ${degrees(home.lat, home.lon)}, from where most are taken at night.".let { if (home.nightShots > 0) it else "Most of your photos were taken near ${degrees(home.lat, home.lon)}." })
        farthest(places, home)?.let { (p, km) -> add("The farthest was ${"%.0f".format(km)} km from there, near ${degrees(p.lat, p.lon)}.") }
        daysAway(shots, home, zone = zone).takeIf { it > 0 }?.let { add("You were away from home on $it day${if (it == 1) "" else "s"}.") }
    }
}
