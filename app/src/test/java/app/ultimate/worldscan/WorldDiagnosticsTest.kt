package app.ultimate.worldscan

import app.ultimate.room.RoomMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

class WorldDiagnosticsTest {
    private val anchor = GeoAnchor(34.2164, -119.0376, 30.0)

    // ---------------------------------------------------------------- depth health
    @Test fun depthAdviceComesFromWhatWasRejectedNotFromOneFrame() {
        val h = MappingHealth()
        h.update(0, 0, 0, 0, 0, 0)                                  // first look only sets the baseline
        h.update(5_000, 50, 25, 50 * 14400L, 0, 0)                   // half the pictures thrown away for speed
        assertTrue(h.advice!!, h.advice!!.startsWith("Slow down"))
        h.update(10_000, 100, 25, 100 * 14400L, 0, 0)                // the next five seconds are fine
        assertNull(h.advice)
        h.update(15_000, 150, 25, 150 * 14400L, 40L * 14400 * 1, 10L * 14400)   // 50 pictures: 50*14400 pixels, 40+10 frames' worth unusable = 100%
        assertTrue(h.advice!!, h.advice!!.startsWith("Depth is weak"))
    }

    @Test fun standingStillIsNotNagged() {
        val h = MappingHealth()
        h.update(0, 0, 0, 0, 0, 0); h.update(5_000, 5, 0, 5 * 14400L, 0, 0)          // too few pictures to say anything
        assertNull(h.advice)
        h.update(10_000, 6, 0, 6 * 14400L, 0, 0); assertNull(h.advice)
    }

    // ---------------------------------------------------------------- walk vs GPS over time
    @Test fun driftSeriesShowsARiseWhenTheWalkDrifts() {
        val n = 320; val t = LongArray(n) { it * 100_000_000L }
        val x = FloatArray(n) { it * 0.14f }; val z = FloatArray(n)
        val tracked = BooleanArray(n) { true }
        // GPS says the phone went exactly straight; the walk's own x runs 1% long, so the gap grows by 1% of distance walked.
        val fixT = LongArray(30) { (it + 1) * 1_000_000_000L }; val fixX = FloatArray(30) { (it + 1) * 1.4f / 1.01f }; val fixZ = FloatArray(30)
        val series = DriftSeries.of(t, x, z, tracked, fixT, fixX, fixZ)
        assertEquals(30, series.size)
        assertTrue(series.last().second > series.first().second)
        assertEquals(0.01 * 42.0 / 1.01, series.last().second, 0.02)
        val (first, last) = DriftSeries.firstAndLastThird(series)!!
        assertTrue("first $first last $last", last > first * 3)
        assertNull(DriftSeries.firstAndLastThird(series.take(5)))
    }

    @Test fun driftSeriesSkipsFixesWhereTrackingWasLost() {
        val t = LongArray(100) { it * 100_000_000L }; val x = FloatArray(100) { it * 0.1f }; val z = FloatArray(100)
        val tracked = BooleanArray(100) { it < 40 || it > 60 }
        val s = DriftSeries.of(t, x, z, tracked, longArrayOf(2_000_000_000L, 5_000_000_000L, 8_000_000_000L), floatArrayOf(2f, 5f, 8f), floatArrayOf(0f, 0f, 0f))
        assertEquals(2, s.size)
    }

    // ---------------------------------------------------------------- phone state survives the record
    @Test fun thermalAndBatteryAreStored() {
        val g = WalkMeta("i", "n", 0L, 1L, 10.0, 5.0, 1.0, 0.0, 0, 1.0, 1.0, 1.0, 0, 0, "", null, 0, null, null, 0L, false, emptyMap(), emptyList(), null, null, 2, 83, 78)
        val back = WalkStore.walkFrom(WalkStore.walkJson(g))
        assertEquals(2, back.thermalMax); assertEquals(83, back.batteryStart); assertEquals(78, back.batteryEnd)
        val old = WalkStore.walkJson(g).also { it.remove("thermal"); it.remove("battery0"); it.remove("battery1") }      // a walk saved before these existed
        assertEquals(-1, WalkStore.walkFrom(old).thermalMax)
    }

    // ---------------------------------------------------------------- the whole glue: camera clock -> sensor clock -> GPS -> placement
    private fun lWalkInput(frameClockOffsetNs: Long, withSync: Boolean, theta: Double = Math.toRadians(37.0)): WalkInput {
        val rnd = Random(11); val track = WalkTrack(); val sync = ClockSync(); val fixes = ArrayList<GeoFix>()
        val start = 10_000_000_000_000L       // elapsed-realtime clock when the walk began: 10,000 s (2.8 hours) after boot
        var bx = 0.0; var by = 0.0
        for (s in 0..140) {
            for (k in 0 until 10) {
                val d = s + k / 10.0; val ax = if (d <= 80.0) d else 80.0; val ay = if (d <= 80.0) 0.0 else -(d - 80.0)
                val elapsed = start + (s * 10 + k) * 100_000_000L
                val frame = elapsed - frameClockOffsetNs            // the camera's own clock differs from the sensors' by this much
                track.add(PosePoint(frame, ax.toFloat(), 0f, (-ay).toFloat(), 0f, 0f, 0f, 1f, TrackingQuality.TRACKING))
                val latency = 25_000_000L + (-ln(1 - rnd.nextDouble()) * 10e6).toLong()
                sync.add(frame, elapsed + latency)
            }
            val d = s.toDouble(); val ax = if (d <= 80.0) d else 80.0; val ay = if (d <= 80.0) 0.0 else -(d - 80.0)
            bx = 0.9 * bx + app.ultimate.room.Sim.gauss(rnd) * 0.5; by = 0.9 * by + app.ultimate.room.Sim.gauss(rnd) * 0.5
            val c = cos(theta); val sn = sin(theta)
            val ll = anchor.unproject(c * ax - sn * ay + 120.0 + bx + app.ultimate.room.Sim.gauss(rnd) * 1.5, sn * ax + c * ay - 45.0 + by + app.ultimate.room.Sim.gauss(rnd) * 1.5)
            fixes += GeoFix(ll.first, ll.second, null, 5f, start + s * 1_000_000_000L)
        }
        val map = WalkSettings.newMap(); for (v in 1..3) map.hit(1f, 1f, 1f, v, 1f)
        val piece = WalkChunk(map, track.points.first().tNs, track.points.last().tNs)
        return WalkInput("w", "Walk", 0L, 1L, track, listOf(piece), if (withSync) sync else null, fixes, null, null, 10, "")
    }

    @Test fun aCameraClockHoursAwayFromTheSensorsIsBridgedAndTheWalkIsPlaced() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wsglue" + System.nanoTime()); val store = WalkStore(dir)
        val offset = 7_300_000_000_000L       // 2 hours: the monotonic clock stopped while the phone slept
        val r = store.save(lWalkInput(offset, withSync = true), null)
        val g = r.walk.geo!!
        assertEquals(g.summary(), GeoLevel.GOOD, g.level)
        assertTrue("heading error ${Math.toDegrees(abs(g.thetaRad - Math.toRadians(37.0)))}", Math.toDegrees(abs(g.thetaRad - Math.toRadians(37.0))) < 2.0)
        assertEquals(false, r.walk.clock!!.sameClock)
        assertTrue("jitter ${r.walk.clock!!.jitterMs} ms", r.walk.clock!!.jitterMs in 5.0..20.0)
        // The stored track is on the sensors' clock now.
        val stored = store.track(r.place.id, "w")!!
        assertTrue(stored.points.first().tNs in 9_999_000_000_000L..10_001_000_000_000L)
        dir.deleteRecursively()
    }

    @Test fun withoutClockSyncTheSameWalkCannotBePlaced() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wsglue" + System.nanoTime()); val store = WalkStore(dir)
        val r = store.save(lWalkInput(7_300_000_000_000L, withSync = false), null)
        assertEquals(GeoLevel.NONE, r.walk.geo!!.level)
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------- the place is placed from every walk
    private val theta = Math.toRadians(-58.0); private val alignB = app.ultimate.room.Alignment(40f, 12f, 0f, -5f)      // walk B's frame -> the place's frame

    /** An L-shaped walk written in the place's frame (when [frame] is null) or in another walk's frame, with its own GPS errors (a slow bias and white noise). */
    private fun lWalk(seed: Int, frame: app.ultimate.room.Alignment?, withGps: Boolean = true, merge: MergeRecord? = null): PlaceGeoInput {
        val rnd = Random(seed); val track = WalkTrack(); val fixes = ArrayList<GeoFix>(); var bx = 0.0; var by = 0.0
        val start = 20_000_000_000_000L
        for (s in 0..140) {
            for (k in 0 until 10) {
                val d = s + k / 10.0; val ax = if (d <= 80.0) d else 80.0; val ay = if (d <= 80.0) 0.0 else -(d - 80.0)
                val p = app.ultimate.room.P3(ax.toFloat(), 0f, (-ay).toFloat()).let { if (frame == null) it else frame.invert(it) }
                track.add(PosePoint(start + (s * 10 + k) * 100_000_000L, p.x, p.y, p.z, 0f, 0f, 0f, 1f, TrackingQuality.TRACKING))
            }
            val d = s.toDouble(); val ax = if (d <= 80.0) d else 80.0; val ay = if (d <= 80.0) 0.0 else -(d - 80.0)
            bx = 0.9 * bx + app.ultimate.room.Sim.gauss(rnd) * 0.8; by = 0.9 * by + app.ultimate.room.Sim.gauss(rnd) * 0.8
            val c = cos(theta); val sn = sin(theta)
            val ll = anchor.unproject(c * ax - sn * ay + 50.0 + bx + app.ultimate.room.Sim.gauss(rnd) * 1.5, sn * ax + c * ay - 20.0 + by + app.ultimate.room.Sim.gauss(rnd) * 1.5)
            if (withGps) fixes += GeoFix(ll.first, ll.second, null, 5f, start + s * 1_000_000_000L)
        }
        return PlaceGeoInput(track, fixes, merge)
    }

    @Test fun everyWalkWithGpsTightensWhereThePlaceIs() {
        val record = MergeRecord(0L, alignB, 0f, 0.05f, 0.8f, 0.5f, "", emptyList())
        val base = lWalk(1, null); val second = lWalk(2, alignB, merge = record); val third = lWalk(3, alignB, merge = record)
        val one = PlaceGeo.fit(listOf(base), anchor)!!
        val two = PlaceGeo.fit(listOf(base, second), anchor)!!
        val three = PlaceGeo.fit(listOf(base, second, third), anchor)!!
        fun err(g: GeoRegistration) = Math.toDegrees(abs(g.thetaRad - theta))
        println("SIM place geo: heading sigma ${"%.3f".format(one.yawSigmaDeg)} -> ${"%.3f".format(two.yawSigmaDeg)} -> ${"%.3f".format(three.yawSigmaDeg)} deg; error ${"%.2f".format(err(one))} -> ${"%.2f".format(err(two))} -> ${"%.2f".format(err(three))} deg; position sigma ${"%.2f".format(one.posSigmaM)} -> ${"%.2f".format(three.posSigmaM)} m")
        assertTrue(two.yawSigmaDeg < one.yawSigmaDeg * 0.85); assertTrue(three.yawSigmaDeg < two.yawSigmaDeg)
        assertTrue(three.posSigmaM <= one.posSigmaM + 1e-9)
        assertTrue("error ${err(three)} vs claimed ${three.yawSigmaDeg}", err(three) < 3 * three.yawSigmaDeg + 0.5)
        assertEquals(GeoLevel.GOOD, three.level)
    }

    @Test fun aPlaceWhoseFirstWalkHadNoGpsIsPlacedByALaterOne() {
        val record = MergeRecord(0L, alignB, 0f, 0.05f, 0.8f, 0.5f, "", emptyList())
        val base = lWalk(1, null, withGps = false); val second = lWalk(2, alignB, merge = record)
        val g = PlaceGeo.fit(listOf(base, second))!!
        assertTrue(g.summary(), g.usable)
        // The place's frame is the first walk's: the fit must describe that frame, not the second walk's.
        assertTrue("heading error ${Math.toDegrees(abs(g.thetaRad - theta))}", Math.toDegrees(abs(g.thetaRad - theta)) < 2.5)
        assertNull(PlaceGeo.fit(listOf(base)))
    }

    @Test fun theStoreKeepsThePlacesCombinedPlacementAcrossSavesAndUndo() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wsgeo" + System.nanoTime()); val store = WalkStore(dir)
        val a = lWalk(1, null)
        val map = WalkSettings.newMap(); for (v in 1..3) map.hit(1f, 1f, 1f, v, 1f)
        fun input(id: String, p: PlaceGeoInput) = WalkInput(id, id, 0L, 1L, p.track, listOf(WalkChunk(map, p.track.points.first().tNs, p.track.points.last().tNs)), null, p.fixes, null, null, 1, "")
        val r = store.save(input("a", a), null)
        assertNotNull(r.place.geo); assertEquals(1, r.place.geoWalks); assertEquals(r.place.geo!!.yawSigmaDeg, r.place.bestGeo!!.yawSigmaDeg, 1e-12)
        assertTrue(store.fixes(r.place.id, "a").size > 100)
        store.renamePlace(r.place.id, "Renamed")                                    // rewriting the place must not lose its placement
        assertNotNull(store.place(r.place.id)!!.geo)
        dir.deleteRecursively()
    }
}
