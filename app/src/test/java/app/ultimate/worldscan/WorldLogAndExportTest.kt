package app.ultimate.worldscan

import app.ultimate.room.RoomMap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

class WorldLogAndExportTest {
    private fun gz(lines: List<String>, cut: Boolean = false): ByteArray {
        val b = ByteArrayOutputStream(); GZIPOutputStream(b, true).use { z -> z.write((lines.joinToString("\n") + "\n").toByteArray()); z.flush() }
        val all = b.toByteArray()
        return if (cut) all.copyOf(all.size - 12) else all     // a log whose end is missing, as after a crash
    }

    @Test fun everyLineTheRecorderWritesIsValidJsonAndReadsBack() {
        val lines = listOf(
            LogLines.sensor(1, 123_456_789L, 3, floatArrayOf(0.1f, Float.NaN, 9.81f)),
            LogLines.location(2_000_000_000L, 34.2164, -119.0376, 4.5f, 30.0, 1.2f, 90f, "gps"),
            LogLines.location(3_000_000_000L, 34.2165, -119.0377, null, null, null, null, "network"),      // no accuracy: not a usable fix
            LogLines.pose(5_000_000_000L, 5_040_000_000L, 1f, 2f, 3f, 0f, 0f, 0f, 1f, TrackingQuality.TRACKING),
            LogLines.note(6_000_000_000L, "tracking-lost", "INSUFFICIENT \"LIGHT\"\nline two"))
        for (l in lines) JSONObject(l)       // throws if any is malformed
        val sensor = JSONObject(lines[0]); assertTrue(sensor.getJSONArray("v").isNull(1)); assertEquals(3, sensor.getInt("a"))
        val log = SessionLog.read(ByteArrayInputStream(gz(lines)))
        assertEquals(1, log.fixes.size); assertEquals(4.5f, log.fixes[0].horizontalAccuracyMeters, 1e-6f); assertEquals(2_000_000_000L, log.fixes[0].elapsedRealtimeNanos)
        assertEquals(listOf("tracking-lost: INSUFFICIENT 'LIGHT' line two"), log.notes.map { it.second })
        assertEquals(1, log.counts["pose"]); assertEquals(2, log.counts["location"]); assertEquals(1, log.counts["sensor"])
        assertEquals(5_040_000_000L - 5_000_000_000L, log.poseClockPairs[0][1] - log.poseClockPairs[0][0])
        assertTrue(log.complete)
    }

    @Test fun aLogCutShortByACrashIsReadAsFarAsItGoes() {
        val lines = (0 until 400).map { LogLines.location(it * 1_000_000_000L, 34.0, -119.0, 5f, null, null, null, "gps") }
        val log = SessionLog.read(ByteArrayInputStream(gz(lines, cut = true)))
        assertTrue("fixes before the cut are kept: ${log.fixes.size}", log.fixes.size in 100..400)
        assertFalse(log.complete)
    }

    @Test fun pathExportIsValidGeoJsonOnlyForWalksPlacedOnTheMap() {
        val anchor = GeoAnchor(34.2164, -119.0376)
        val placed = GeoRegistration(anchor, 0.0, 0.0, 0.0, 1.0, 2.0, 1.0, 3.0, 20, 20, 1.0, 1.0, GeoLevel.GOOD, "ok")
        val track = WalkTrack(); for (i in 0..100) track.add(PosePoint(i * 100_000_000L, i * 0.14f, 0f, 0f, 0f, 0f, 0f, 1f, TrackingQuality.TRACKING))
        fun meta(id: String, g: GeoRegistration?) = WalkMeta(id, id, 0L, 0L, 14.0, 10.0, 1.0, 0.0, 0, 1.4, 14.0, 0.0, 0, 0, "", g, 0, null, null, 0L, false, emptyMap(), emptyList(), null, null)
        val json = JSONObject(WorldExport.geoJson("P", listOf(meta("a", placed) to track, meta("b", null) to track)))
        assertEquals("FeatureCollection", json.getString("type")); assertEquals(1, json.getJSONArray("features").length())
        val coords = json.getJSONArray("features").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        assertTrue(coords.length() in 5..9)          // 14 m thinned to about every 2 m
        // x east: the walk goes east, so longitude grows and latitude stays.
        val first = coords.getJSONArray(0); val last = coords.getJSONArray(coords.length() - 1)
        assertTrue(last.getDouble(0) > first.getDouble(0)); assertEquals(first.getDouble(1), last.getDouble(1), 1e-6)
        assertEquals(34.2164, first.getDouble(1), 1e-6)
    }

    @Test fun pointCloudExportIsAWellFormedBinaryPly() {
        val map = RoomMap(0.10f); for (i in 0 until 30) for (v in 1..3) map.hit(i * 0.1f, 0.05f, 0.05f, v, 1f)
        val out = ByteArrayOutputStream(); val n = WorldExport.ply(map, out)
        val bytes = out.toByteArray(); val text = String(bytes, 0, 300, Charsets.US_ASCII)
        assertEquals(30, n); assertTrue(text.startsWith("ply\nformat binary_little_endian 1.0"))
        val end = text.indexOf("end_header\n") + "end_header\n".length
        assertEquals(end + 15 * n, bytes.size)
        val xs = (0 until n).map { i -> ByteBuffer.wrap(bytes, end + 15 * i, 15).order(ByteOrder.LITTLE_ENDIAN).getFloat() }.sorted()
        assertEquals(0.05f, xs.first(), 1e-4f); assertEquals(2.95f, xs.last(), 1e-4f)
    }

    @Test fun trackingLossEndsTheWalkOnlyWhenItCannotBeTrusted() {
        fun run(f: (TrackingWatch) -> String?): String? = f(TrackingWatch())
        val s = 1_000_000_000L
        // Normal walking: nothing.
        assertNull(run { w -> var r: String? = null; for (i in 0..100) r = r ?: w.update(i * s / 10, true, i * 0.14f, 0f); r })
        // A 2 s loss that comes back where it left off: carry on.
        assertNull(run { w -> w.update(0, true, 0f, 0f); w.update(s, false, 0f, 0f); w.update(2 * s, false, 0f, 0f); w.update(3 * s, true, 1.5f, 0f) })
        // A loss of more than 4 s: end.
        assertNotNull(run { w -> w.update(0, true, 0f, 0f); var r: String? = null; for (i in 1..6) r = r ?: w.update(i * s, false, 0f, 0f); r })
        // A 2 s loss that comes back 40 m away (a new frame): end.
        assertNotNull(run { w -> w.update(0, true, 5f, 5f); w.update(s, false, 0f, 0f); w.update(2 * s, false, 0f, 0f); w.update(3 * s, true, 45f, 5f) })
        // The loss is announced once.
        val w = TrackingWatch(); w.update(0, true, 0f, 0f); w.update(s, false, 0f, 0f); assertTrue(w.lostJustNow); w.update(2 * s, false, 0f, 0f); assertFalse(w.lostJustNow)
    }

    @Test fun pathsAreGeoreferencedThroughThePlaceOnlyForWalksInThePlace() {
        val anchor = GeoAnchor(34.2164, -119.0376)
        val place = GeoRegistration(anchor, 0.0, 0.0, 0.0, 1.0, 2.0, 1.0, 3.0, 20, 20, 1.0, 1.0, GeoLevel.GOOD, "ok")
        val track = WalkTrack(); for (i in 0..100) track.add(PosePoint(i * 100_000_000L, i * 0.14f, 0f, 0f, 0f, 0f, 0f, 1f, TrackingQuality.TRACKING))
        fun meta(id: String) = WalkMeta(id, id, 0L, 0L, 14.0, 10.0, 1.0, 0.0, 0, 1.4, 14.0, 0.0, 0, 0, "", null, 0, null, null, 0L, false, emptyMap(), emptyList(), null, null)
        // The second walk was added to the place with a shift of 100 m along x (east), so its start lies 100 m east of the first walk's start.
        val merged = meta("merged").copy(merge = MergeRecord(0L, app.ultimate.room.Alignment(0f, 100f, 0f, 0f), 0f, 0.05f, 0.8f, 0.5f, "", emptyList()))
        val json = JSONObject(WorldExport.geoJson("P", listOf(meta("base") to track, merged to track, meta("refused") to track), baseWalkId = "base", placeGeo = place))
        val f = json.getJSONArray("features")
        assertEquals("the refused walk has no placement of its own and is not put through the place's", 2, f.length())
        val lonBase = f.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0).getDouble(0)
        val lonMerged = f.getJSONObject(1).getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0).getDouble(0)
        val metresEast = (lonMerged - lonBase) * 111_320.0 * kotlin.math.cos(Math.toRadians(34.2164))
        assertEquals(100.0, metresEast, 1.0)
        assertEquals("place (all walks)", f.getJSONObject(1).getJSONObject("properties").getString("placedBy"))
    }
}
