package app.ultimate.worldscan

import org.junit.Assert.*
import org.junit.Test

class WorldScanModelTest {
    private val anchor = GeoAnchor(34.2164, -119.0376, 30.0)
    private fun fix(latitude: Double = 34.2164, longitude: Double = -119.0376,
                    accuracy: Float = 4f, time: Long = 10L) =
        GeoFix(latitude, longitude, 30.0, accuracy, time)

    @Test fun rejectsInvalidPositionAndPoorGpsAccuracy() {
        assertFalse(fix(latitude = 91.0).isUsable())
        assertFalse(fix(accuracy = Float.NaN).isUsable())
        assertFalse(fix(accuracy = 90f).isUsable())
        assertTrue(fix().isUsable())
    }

    @Test fun localProjectionIsApproximatelyMetric() {
        val origin = anchor.project(fix())
        val east = anchor.project(fix(longitude = -119.03749))
        val north = anchor.project(fix(latitude = 34.21649))
        assertEquals(0.0, origin.eastMeters, 0.01)
        assertTrue(east.eastMeters > 9 && east.eastMeters < 11)
        assertTrue(north.northMeters > 9 && north.northMeters < 11)
    }

    @Test fun negativeTileIndicesUseFloorNotTruncation() {
        val index = WorldTileIndex(20.0)
        assertEquals(WorldTile(-1, -1), index.of(EastNorthUp(-0.1, -19.9, null)))
        assertEquals(WorldTile(1, 0), index.of(EastNorthUp(20.0, 0.0, null)))
    }

    @Test fun sessionPreservesTrackingStateAndRejectsOutOfOrderSample() {
        val session = WorldScanSession("walk-1", 10L, anchor)
        assertTrue(session.append(CaptureSample(10L, fix(), TrackingQuality.TRACKING, "ar-1")))
        assertTrue(session.append(CaptureSample(11L, fix(time = 11L, accuracy = 50f),
            TrackingQuality.LIMITED, "ar-1")))
        assertFalse(session.append(CaptureSample(9L, fix(time = 9L),
            TrackingQuality.NOT_TRACKING, null)))
        assertEquals(2, session.sampleCount)
        assertEquals(1, session.visitedTileCount)
        assertEquals(TrackingQuality.LIMITED, session.samples().last().trackingQuality)
    }

    @Test fun repeatWalksShareTilesButDoNotClaimVisualAlignment() {
        val first = WorldScanSession("walk-a", 10L, anchor)
        val second = WorldScanSession("walk-b", 20L, anchor)
        first.append(CaptureSample(10L, fix(), TrackingQuality.TRACKING, "a"))
        second.append(CaptureSample(20L, fix(time = 20L), TrackingQuality.NOT_TRACKING, null))
        assertEquals(1, first.matchingTiles(second).size)
    }
}
