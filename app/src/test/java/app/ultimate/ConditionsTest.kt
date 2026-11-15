package app.ultimate

import app.ultimate.fusion.Conditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionsTest {
    private val start = java.time.Instant.parse("2026-11-15T22:40:00Z").toEpochMilli()
    private fun weather(hour: String) = """{"hourly":{"time":["2026-11-15T21:00","$hour","2026-11-15T23:00"],"temperature_2m":[15.0,14.2,13.0],"apparent_temperature":[13.0,11.6,10.0],"relative_humidity_2m":[60,68,70],"wind_speed_10m":[9.0,11.4,12.0],"pressure_msl":[1015.0,1016.3,1016.0]}}"""
    private val air = """{"hourly":{"time":["2026-11-15T21:00","2026-11-15T22:00","2026-11-15T23:00"],"us_aqi":[40,42,null],"pm2_5":[5.0,6.5,null]}}"""

    @Test fun `the hour of the walk is picked out and described`() {
        val s = Conditions.parse(weather("2026-11-15T22:00"), air, start)!!
        assertEquals(14.2, s.tempC!!, 1e-9); assertEquals(68, s.humidity); assertEquals(42, s.aqi); assertEquals(6.5, s.pm25!!, 1e-9)
        assertEquals("14°C (feels 12°), humidity 68%, wind 11 km/h, pressure 1016 hPa · air quality 42 (good)", Conditions.words(s))
    }

    @Test fun `missing pieces are left out and nothing known is no snapshot`() {
        val s = Conditions.parse(null, air, start)!!; assertNull(s.tempC); assertEquals("air quality 42 (good)", Conditions.words(s))
        assertNull(Conditions.parse(null, null, start)); assertNull(Conditions.parse("garbage", "{}", start))
        assertNull(Conditions.parse(weather("2026-11-14T22:00"), air.replace("22:00", "21:30"), start))   // that hour isn't in either answer
        assertEquals("air quality 120 (poor for sensitive people)", Conditions.words(Conditions.Snapshot(null, null, null, null, null, 120, null)))
    }

    @Test fun `only a rounded place is ever sent`() {
        assertEquals("40.71", Conditions.roundForSending(40.71283)); assertEquals("-74.01", Conditions.roundForSending(-74.00597))
        val u = Conditions.weatherUrl(40.712834, -74.005971); assertTrue("40.71" in u && "-74.01" in u); assertFalse("40.712834" in u || "74.005971" in u)
        assertFalse("712834" in Conditions.airUrl(40.712834, -74.005971))
    }

    @Test fun `a snapshot survives saving`() {
        val s = Conditions.parse(weather("2026-11-15T22:00"), air, start)!!
        assertEquals(s, Conditions.decode(Conditions.encode(s))); assertNull(Conditions.decode("nope")); assertNotNull(Conditions.decode("{}"))
    }
}
