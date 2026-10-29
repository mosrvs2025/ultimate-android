package app.ultimate.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorMathTest {
    @Test fun altitudeFromPressure() {
        assertEquals(0f, SensorMath.altitude(1013.25f), 0.5f)
        assertEquals(111f, SensorMath.altitude(1000f), 2f)
        assertEquals(1457f, SensorMath.altitude(850f), 10f)
    }
    @Test fun gForceAndTilt() {
        assertEquals(1f, SensorMath.gForce(floatArrayOf(0f, 0f, SensorMath.G)), 1e-4f)
        val (p, r) = SensorMath.tilt(floatArrayOf(0f, 0f, 9.8f)); assertEquals(0f, p, 0.01f); assertEquals(0f, r, 0.01f)
        assertEquals(90f, SensorMath.tilt(floatArrayOf(0f, 9.8f, 0f)).first, 0.01f)
        assertEquals(-90f, SensorMath.tilt(floatArrayOf(9.8f, 0f, 0f)).second, 0.01f)
    }
    @Test fun compassPointsAndLight() {
        assertEquals("N", SensorMath.compassPoint(359f)); assertEquals("NE", SensorMath.compassPoint(40f))
        assertEquals("S", SensorMath.compassPoint(180f)); assertEquals("W", SensorMath.compassPoint(-90f))
        assertEquals("Direct sunlight", SensorMath.lightDescription(30_000f)); assertEquals("Dark", SensorMath.lightDescription(1f))
    }
}
