package app.ultimate.worldscan

import app.ultimate.room.P3
import kotlin.math.max

/** One walk of a place for the combined GPS fit: its path, its GPS fixes, and how it was added to the place (null for the walk that defines the place's frame). */
class PlaceGeoInput(val track: WalkTrack, val fixes: List<GeoFix>, val merge: MergeRecord?)

/**
 * Placing a *place* on the Earth from every walk of it at once. A single walk's GPS fit is limited by that walk's own GPS errors, which are
 * largely a slow bias (buildings, satellites in view that day). The errors of walks made at different times are independent, so fitting
 * all of them together, each walk's path first carried into the place's frame by the alignment it was merged with, tightens the heading and
 * position with every walk added. A walk with no GPS at all adds nothing but loses nothing, and a place whose first walk had no GPS can be
 * placed by a later one. Pure, so it is tested.
 */
object PlaceGeo {
    /** Pairs for one walk, written in the place's frame: the position at each GPS fix is moved by the piece's placement (or the whole walk's) into the place's frame. */
    fun pairs(input: PlaceGeoInput, anchor: GeoAnchor, maxAccuracyM: Float = 25f): List<GeoPair> {
        val out = ArrayList<GeoPair>()
        for (f in input.fixes) {
            if (!f.isUsable(maxAccuracyM)) continue
            val p = input.track.at(f.elapsedRealtimeNanos) ?: continue
            val a = input.merge?.let { r -> r.pieces.firstOrNull { it.alignment != null && f.elapsedRealtimeNanos in it.t0Ns..it.t1Ns }?.alignment ?: r.global }
            val q = a?.apply(P3(p.x, p.y, p.z)) ?: P3(p.x, p.y, p.z)
            val enu = anchor.project(f)
            out += GeoPair(f.elapsedRealtimeNanos, q.x.toDouble(), -q.z.toDouble(), enu.eastMeters, enu.northMeters, max(GeoFit.SIGMA_FLOOR_M, f.horizontalAccuracyMeters / 1.5))
        }
        return out
    }

    /** The place's placement from all [inputs], or null when no fix could be matched to any walk. The anchor is the first usable fix of the first walk that has one. */
    fun fit(inputs: List<PlaceGeoInput>, anchor: GeoAnchor? = null): GeoRegistration? {
        val a = anchor ?: inputs.firstNotNullOfOrNull { i -> i.fixes.firstOrNull { it.isUsable() }?.let { GeoAnchor(it.latitudeDeg, it.longitudeDeg, it.altitudeMeters) } } ?: return null
        val all = inputs.flatMap { pairs(it, a) }
        if (all.isEmpty()) return null
        return GeoFit.fit(all, a, inputs.sumOf { it.fixes.size })
    }
}
