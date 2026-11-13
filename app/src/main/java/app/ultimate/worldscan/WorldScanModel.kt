package app.ultimate.worldscan

import kotlin.math.*

/**
 * World Scan's device-independent data model.
 *
 * A geographic position is NOT a 3-D surface measurement. GPS coordinates are used only
 * as coarse anchors. Camera/depth samples must carry their own local coordinate frame
 * and tracking quality before they can be fused into a persistent world.
 */
data class GeoFix(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeMeters: Double? = null,
    val horizontalAccuracyMeters: Float,
    val elapsedRealtimeNanos: Long,
) {
    fun isUsable(maxAccuracyMeters: Float = 35f): Boolean =
        latitudeDeg.isFinite() && longitudeDeg.isFinite() &&
            latitudeDeg in -90.0..90.0 && longitudeDeg in -180.0..180.0 &&
            horizontalAccuracyMeters.isFinite() && horizontalAccuracyMeters > 0f &&
            horizontalAccuracyMeters <= maxAccuracyMeters &&
            elapsedRealtimeNanos >= 0L &&
            (altitudeMeters == null || altitudeMeters.isFinite())
}

data class EastNorthUp(val eastMeters: Double, val northMeters: Double, val upMeters: Double?) {
    fun horizontalDistanceTo(other: EastNorthUp): Double =
        hypot(eastMeters - other.eastMeters, northMeters - other.northMeters)
}

/** WGS84 local tangent approximation intended for neighborhood-scale indexing, not surveying. */
class GeoAnchor(val latitudeDeg: Double, val longitudeDeg: Double, val altitudeMeters: Double? = null) {
    init {
        require(latitudeDeg.isFinite() && latitudeDeg in -90.0..90.0)
        require(longitudeDeg.isFinite() && longitudeDeg in -180.0..180.0)
    }

    fun project(fix: GeoFix): EastNorthUp {
        require(fix.isUsable(Float.MAX_VALUE)) { "Invalid geographic fix" }
        val lat = Math.toRadians(latitudeDeg)
        val sinLat = sin(lat)
        val eccentricity2 = 6.69437999014e-3
        val w = sqrt(1.0 - eccentricity2 * sinLat * sinLat)
        val primeVertical = 6378137.0 / w
        val meridional = 6378137.0 * (1.0 - eccentricity2) / (w * w * w)
        val longitudeDifference = ((fix.longitudeDeg - longitudeDeg + 540.0) % 360.0) - 180.0
        val e = Math.toRadians(longitudeDifference) * primeVertical * cos(lat)
        val n = Math.toRadians(fix.latitudeDeg - latitudeDeg) * meridional
        val u = if (fix.altitudeMeters != null && altitudeMeters != null)
            fix.altitudeMeters - altitudeMeters else null
        return EastNorthUp(e, n, u)
    }

    /** The inverse of [project] (same local approximation): latitude and longitude, in degrees, of a point [eastMeters] east and [northMeters] north of the anchor. */
    fun unproject(eastMeters: Double, northMeters: Double): Pair<Double, Double> {
        val lat = Math.toRadians(latitudeDeg)
        val sinLat = sin(lat)
        val w = sqrt(1.0 - 6.69437999014e-3 * sinLat * sinLat)
        val primeVertical = 6378137.0 / w
        val meridional = 6378137.0 * (1.0 - 6.69437999014e-3) / (w * w * w)
        val dLat = Math.toDegrees(northMeters / meridional)
        val dLon = Math.toDegrees(eastMeters / (primeVertical * cos(lat)))
        return (latitudeDeg + dLat) to (((longitudeDeg + dLon + 540.0) % 360.0) - 180.0)
    }
}

/**
 * A tile of a fixed, anchor-independent geographic lattice: rows of [sizeMeters] of latitude, each row cut into columns that are
 * [sizeMeters] wide at that row's own latitude. Two walks made on different days (so with different first fixes, hence different
 * [GeoAnchor]s) can be asked which tiles they share. Still only an index: sharing a tile says "same neighbourhood", never "same surfaces".
 */
data class GlobalTile(val row: Long, val col: Long) {
    companion object {
        private const val METERS_PER_DEGREE_LAT = 111_132.0
        fun of(latitudeDeg: Double, longitudeDeg: Double, sizeMeters: Double = 20.0): GlobalTile {
            require(latitudeDeg.isFinite() && longitudeDeg.isFinite() && sizeMeters > 0.0)
            val dLat = sizeMeters / METERS_PER_DEGREE_LAT
            val row = floor(latitudeDeg / dLat).toLong()
            val centreLat = (row + 0.5) * dLat
            val dLon = dLat / max(0.01, cos(Math.toRadians(centreLat)))
            return GlobalTile(row, floor(((longitudeDeg + 180.0) % 360.0) / dLon).toLong())
        }
    }
}

/** Geographic indexing; an index match is NOT evidence that two visual reconstructions align. */
data class WorldTile(val eastIndex: Long, val northIndex: Long)

class WorldTileIndex(private val tileSizeMeters: Double = 20.0) {
    init { require(tileSizeMeters.isFinite() && tileSizeMeters > 0.0) }
    fun of(position: EastNorthUp): WorldTile {
        require(position.eastMeters.isFinite() && position.northMeters.isFinite())
        return WorldTile(
            floor(position.eastMeters / tileSizeMeters).toLong(),
            floor(position.northMeters / tileSizeMeters).toLong(),
        )
    }
}

enum class TrackingQuality { NOT_TRACKING, LIMITED, TRACKING }

data class CaptureSample(
    val elapsedRealtimeNanos: Long,
    val geoFix: GeoFix?,
    val trackingQuality: TrackingQuality,
    /** The ARCore local frame changes on session restart; never merge across IDs blindly. */
    val localFrameId: String?,
    val cameraFrameTimestampNanos: Long? = null,
    val depthAvailable: Boolean = false,
    val orientationQuaternionXyzw: List<Float>? = null,
) {
    fun isInternallyConsistent(): Boolean =
        elapsedRealtimeNanos >= 0L &&
            (geoFix == null || geoFix.isUsable(Float.MAX_VALUE)) &&
            (cameraFrameTimestampNanos == null || cameraFrameTimestampNanos >= 0L) &&
            (localFrameId == null || localFrameId.isNotBlank()) &&
            (orientationQuaternionXyzw == null ||
                (orientationQuaternionXyzw.size == 4 &&
                    orientationQuaternionXyzw.all { it.isFinite() }))
}

/**
 * Collection and indexing foundation only. Does not pretend GPS fixes provide camera poses,
 * depth, or centimeter-quality repeat-visit alignment.
 */
class WorldScanSession(
    val sessionId: String,
    val createdAtEpochMillis: Long,
    val anchor: GeoAnchor,
    private val tileIndex: WorldTileIndex = WorldTileIndex(),
) {
    init { require(sessionId.isNotBlank()); require(createdAtEpochMillis >= 0L) }

    private val observations = mutableListOf<CaptureSample>()
    private val tileCounts = mutableMapOf<WorldTile, Int>()
    private var lastTimeNanos = -1L

    val sampleCount: Int get() = observations.size
    val visitedTileCount: Int get() = tileCounts.size
    fun samples(): List<CaptureSample> = observations.toList()
    fun tileVisitCounts(): Map<WorldTile, Int> = tileCounts.toMap()

    /** Reject out-of-order clocks and invalid samples, while preserving low-quality tracking flags. */
    fun append(sample: CaptureSample): Boolean {
        if (!sample.isInternallyConsistent() || sample.elapsedRealtimeNanos < lastTimeNanos) return false
        observations.add(sample)
        lastTimeNanos = sample.elapsedRealtimeNanos
        val fix = sample.geoFix
        if (fix != null && fix.isUsable()) {
            val tile = tileIndex.of(anchor.project(fix))
            tileCounts[tile] = (tileCounts[tile] ?: 0) + 1
        }
        return true
    }

    /** Anchor-independent tiles visited with a usable fix: comparable between any two sessions, wherever each one started. */
    fun globalTiles(sizeMeters: Double = 20.0): Set<GlobalTile> =
        observations.mapNotNull { it.geoFix }.filter { it.isUsable() }.map { GlobalTile.of(it.latitudeDeg, it.longitudeDeg, sizeMeters) }.toSet()

    fun matchingTiles(other: WorldScanSession): Set<WorldTile> {
        // Tile numbers are comparable only for sessions using the same geographic anchor.
        require(anchor.latitudeDeg == other.anchor.latitudeDeg &&
            anchor.longitudeDeg == other.anchor.longitudeDeg)
        return tileCounts.keys.intersect(other.tileCounts.keys)
    }
}
