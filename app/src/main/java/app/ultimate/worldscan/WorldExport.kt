package app.ultimate.worldscan

import app.ultimate.room.RoomMap
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.hypot

/**
 * Taking a walk off the phone, only when you choose to share it. GeoJSON holds the path on the Earth (where you walked: treat it as
 * personal); the point cloud is in the walk's own metric frame (Y up) and carries no location. Pure, so it is tested.
 */
object WorldExport {
    /** A walk's path as GeoJSON, one LineString per walk that could be placed on the map (coordinates are longitude, latitude), thinned to a point about every [stepM] metres. */
    fun geoJson(name: String, walks: List<Pair<WalkMeta, WalkTrack>>, stepM: Double = 2.0): String {
        val features = JSONArray()
        for ((meta, track) in walks) {
            val g = meta.geo?.takeIf { it.usable } ?: continue
            val coords = JSONArray(); var lx = Float.NaN; var lz = Float.NaN
            for (p in track.points) {
                if (p.tracking != TrackingQuality.TRACKING) continue
                if (!lx.isNaN() && hypot((p.x - lx).toDouble(), (p.z - lz).toDouble()) < stepM) continue
                lx = p.x; lz = p.z
                val (lat, lon) = g.toLatLon(p.x, p.z); coords.put(JSONArray().put(round7(lon)).put(round7(lat)))
            }
            if (coords.length() < 2) continue
            features.put(JSONObject().put("type", "Feature").put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
                .put("properties", JSONObject().put("walk", meta.name).put("lengthM", Math.round(meta.lengthM * 10) / 10.0).put("headingUncertaintyDeg", Math.round(g.yawSigmaDeg * 10) / 10.0)
                    .put("positionUncertaintyM", Math.round(g.posSigmaM * 10) / 10.0).put("level", g.level.name)))
        }
        return JSONObject().put("type", "FeatureCollection").put("name", name).put("features", features).toString()
    }
    private fun round7(v: Double) = Math.round(v * 1e7) / 1e7

    /** The solid cubes as a binary PLY point cloud in the walk's frame (metres, Y up), coloured by height above the lowest ground; returns the number of points. */
    fun ply(map: RoomMap, out: OutputStream, maxPoints: Int = 2_000_000): Int {
        val pts = ArrayList<FloatArray>(); var low = Float.MAX_VALUE
        map.forEachSolid { x, y, z -> if (y < low) low = y; pts += floatArrayOf(x, y, z) }
        val step = if (pts.size > maxPoints) pts.size / maxPoints + 1 else 1
        val keep = pts.filterIndexed { i, _ -> i % step == 0 }
        val head = "ply\nformat binary_little_endian 1.0\ncomment Ultimate World Scan, metres, Y up, walk's own frame\nelement vertex ${keep.size}\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        val buf = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
        for (p in keep) {
            buf.clear(); buf.putFloat(p[0]).putFloat(p[1]).putFloat(p[2])
            val argb = TopDown.colour(p[1] - low, 6)
            buf.put(((argb shr 16) and 0xFF).toByte()).put(((argb shr 8) and 0xFF).toByte()).put((argb and 0xFF).toByte())
            out.write(buf.array(), 0, 15)
        }
        out.flush(); return keep.size
    }
}

/** What a recorded sensor log (JSON lines, gzip) holds, read without loading it whole: the GPS fixes and notes, and how many lines of each kind. */
class LogSummary(val fixes: List<GeoFix>, val notes: List<Pair<Long, String>>, val counts: Map<String, Int>, val complete: Boolean, val poseClockPairs: List<LongArray> = emptyList())

object SessionLog {
    fun read(file: File): LogSummary = file.inputStream().use { read(it) }

    /** Reads until the end or until the stream stops (a log from a crash is cut short, and gzip says so with an error: what came before is kept). */
    fun read(input: InputStream): LogSummary {
        val fixes = ArrayList<GeoFix>(); val notes = ArrayList<Pair<Long, String>>(); val counts = HashMap<String, Int>(); val pairs = ArrayList<LongArray>(); var complete = true
        try {
            GZIPInputStream(input).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val o = try { JSONTokener(line).nextValue() as? JSONObject } catch (e: Exception) { null } ?: continue
                    val type = o.optString("type"); counts[type] = (counts[type] ?: 0) + 1
                    when (type) {
                        "location" -> if (!o.isNull("acc")) fixes += GeoFix(o.getDouble("lat"), o.getDouble("lon"), if (o.isNull("alt")) null else o.getDouble("alt"), o.getDouble("acc").toFloat(), o.getLong("t"))
                        "note" -> notes += o.getLong("t") to (o.optString("kind") + ": " + o.optString("detail"))
                        "pose" -> pairs += longArrayOf(o.getLong("tf"), o.getLong("tr"))
                    }
                }
            }
        } catch (e: java.io.IOException) { complete = false }
        return LogSummary(fixes, notes, counts, complete, pairs)
    }
}

/**
 * The exact lines the recorder writes (JSON, one per line), built in one place so that [SessionLog] and the tests read what is really
 * written. Times are Android's elapsed-realtime nanoseconds, except `startedEpochMillis`.
 */
object LogLines {
    fun sensor(type: Int, tNanos: Long, accuracy: Int, values: FloatArray): String {
        val sb = StringBuilder(96).append("{\"type\":\"sensor\",\"s\":").append(type).append(",\"t\":").append(tNanos).append(",\"a\":").append(accuracy).append(",\"v\":[")
        for (i in values.indices) { if (i > 0) sb.append(','); val v = values[i]; if (v.isFinite()) sb.append(v) else sb.append("null") }
        return sb.append("]}").toString()
    }
    fun location(tNanos: Long, lat: Double, lon: Double, acc: Float?, alt: Double?, speed: Float?, bearing: Float?, provider: String?): String =
        StringBuilder(160).append("{\"type\":\"location\",\"t\":").append(tNanos).append(",\"lat\":").append(lat).append(",\"lon\":").append(lon)
            .append(",\"acc\":").append(acc ?: "null").append(",\"alt\":").append(alt ?: "null").append(",\"speed\":").append(speed ?: "null").append(",\"bearing\":").append(bearing ?: "null")
            .append(",\"provider\":\"").append((provider ?: "unknown").replace("\"", "'")).append("\"}").toString()
    fun pose(frameNanos: Long, receivedNanos: Long, x: Float, y: Float, z: Float, qx: Float, qy: Float, qz: Float, qw: Float, tracking: TrackingQuality): String =
        "{\"type\":\"pose\",\"tf\":$frameNanos,\"tr\":$receivedNanos,\"p\":[$x,$y,$z],\"q\":[$qx,$qy,$qz,$qw],\"k\":\"${tracking.name}\"}"
    fun note(tNanos: Long, kind: String, detail: String): String =
        "{\"type\":\"note\",\"t\":$tNanos,\"kind\":\"${kind.replace("\"", "'")}\",\"detail\":\"${detail.replace("\"", "'").replace("\n", " ").replace("\\", "/")}\"}"
}
