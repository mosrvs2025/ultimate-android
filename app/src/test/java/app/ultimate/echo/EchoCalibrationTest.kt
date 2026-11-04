package app.ultimate.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoCalibrationTest {
    @Test fun aPhoneThatReadsTwiceTheDistanceIsCorrectedExactly() {
        val cal = EchoCalibration.fit(listOf(10.0, 20.0, 30.0), listOf(25.0, 45.0, 65.0))!!
        assertEquals(0.5, cal.gain, 1e-9)
        assertEquals(0.20, cal.apply(0.45), 1e-9)
        assertEquals(0.0, cal.rmsErrorCm, 1e-9)
    }

    @Test fun benchFourFromTheFoldIsCorrectedAndItsMissIsReportedHonestly() {
        // Galaxy SM-F956U1, 1.57.0: held 10/20/30 cm, read 14.4/15.2/21.1 cm.
        val cal = EchoCalibration.fit(listOf(10.0, 20.0, 30.0), listOf(14.4, 15.2, 21.1))!!
        assertTrue(cal.gain > 1)
        assertEquals(0.30, cal.apply(0.211), 0.02)
        assertTrue("the fit still misses by a few cm, and says so: ${cal.rmsErrorCm}", cal.rmsErrorCm in 2.0..5.0)
    }

    @Test fun refusesFitsThatMeanNothing() {
        assertNull(EchoCalibration.fit(listOf(10.0, 20.0), listOf(15.0, 15.1)))
        assertNull(EchoCalibration.fit(listOf(10.0, 20.0, 30.0), listOf(30.0, 20.0, 10.0)))
        assertNull(EchoCalibration.fit(listOf(10.0), listOf(12.0)))
        assertTrue(EchoCalibration.fit(listOf(10.0, 20.0, 30.0), listOf(12.0, Double.NaN, 31.0)) != null)
    }
}
