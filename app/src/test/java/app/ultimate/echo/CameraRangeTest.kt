package app.ultimate.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRangeTest {
    @Test fun aPalmsSizeGivesItsDistance() {
        // A 4.3 mm lens on a 5.6 mm wide sensor, 640 px image: f = 491 px. An 8 cm palm 196 px wide is 20 cm away.
        val f = CameraRange.focalPx(4.3f, 5.6f, 640)
        assertEquals(491.4, f, 0.5)
        assertEquals(20.0, CameraRange.distanceCm(196.6, f), 0.1)
        assertTrue(CameraRange.distanceCm(5.0, f).isNaN())
        assertEquals(25.0, CameraRange.distanceCm(196.6, f, palmCm = 10.0), 0.1)
    }

    @Test fun aTiltedPalmIsNotTrusted() {
        assertTrue(CameraRange.isFlat(100.0, 110.0))
        assertFalse(CameraRange.isFlat(40.0, 110.0))
    }

    @Test fun pairsBecomeOneRobustPointPerBandAndFitACalibration() {
        // Echo reads 1.5x the true distance + 2 cm, with a wild outlier now and then.
        val pairs = (0 until 300).map { i -> val cam = 8.0 + (i % 23); (cam * 1.5 + 2 + if (i % 37 == 0) 30.0 else 0.0) to cam }
        val (held, read) = CameraRange.binned(pairs)
        assertTrue(held.size >= 8)
        val cal = EchoCalibration.fit(held, read)!!
        assertEquals(1 / 1.5, cal.gain, 0.05)
        assertEquals(0.20, cal.apply((20 * 1.5 + 2) / 100.0), 0.01)
    }
}
