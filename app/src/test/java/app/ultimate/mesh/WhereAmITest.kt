package app.ultimate.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class WhereAmITest {
    private val room = mapOf("Fold" to HouseLayout.Point(0.0, 0.0), "Flip" to HouseLayout.Point(4.0, 0.0), "S21" to HouseLayout.Point(0.0, 3.0), "S10" to HouseLayout.Point(4.0, 3.0))

    /** What a phone calibrated at touch = -45, arm = -70 would hear at [m] metres with the fitted law. */
    private fun dbmAt(m: Double): Double { val n = 25 / (10 * log10(6.0)); return -45 - 10 * n * log10(m / 0.1) }

    @Test fun calibrationPointsMapBack() {
        assertEquals(0.1, WhereAmI.metres(WhereAmI.Heard("a", -45.0)), 1e-6)
        assertEquals(0.6, WhereAmI.metres(WhereAmI.Heard("a", -70.0)), 1e-6)
        assertTrue(WhereAmI.metres(WhereAmI.Heard("a", -90.0)) > 2.0)
        assertEquals(15.0, WhereAmI.metres(WhereAmI.Heard("a", -200.0)), 0.0)
    }

    @Test fun findsYouWithCleanReadings() {
        val you = HouseLayout.Point(1.2, 1.0)
        val e = WhereAmI.estimate(room, room.map { (k, p) -> WhereAmI.Heard(k, dbmAt(p.to(you))) })
        assertEquals(you.x, e.at!!.x, 0.05); assertEquals(you.y, e.at!!.y, 0.05)
        assertEquals("Fold", e.nearest); assertEquals(4, e.heardBy)
    }

    @Test fun messyReadingsWidenTheCircleButKeepTheNearest() {
        val you = HouseLayout.Point(3.5, 2.6)
        val errs = mapOf("Fold" to 6.0, "Flip" to -5.0, "S21" to 4.0, "S10" to -3.0) // dB of body and wall
        val e = WhereAmI.estimate(room, room.map { (k, p) -> WhereAmI.Heard(k, dbmAt(p.to(you)) + errs.getValue(k)) })
        assertEquals("S10", e.nearest)
        assertTrue("radius ${e.radius}", e.radius >= 0.5)
        assertTrue("within its own circle", e.at!!.to(you) <= e.radius + 0.5)
    }

    @Test fun oneOrTwoPhonesGiveOnlyTheNearest() {
        val e = WhereAmI.estimate(room, listOf(WhereAmI.Heard("Flip", dbmAt(0.3)), WhereAmI.Heard("Fold", dbmAt(3.5))))
        assertEquals("Flip", e.nearest); assertTrue(e.sure); assertEquals(room["Flip"], e.at)
        val close = WhereAmI.estimate(room, listOf(WhereAmI.Heard("Flip", dbmAt(1.6)), WhereAmI.Heard("Fold", dbmAt(2.0))))
        assertFalse(close.sure)
    }

    @Test fun staleOrUnknownReadingsAreIgnored() {
        val e = WhereAmI.estimate(room, listOf(WhereAmI.Heard("Flip", -50.0, ageMs = 60_000), WhereAmI.Heard("Quest", -40.0)))
        assertNull(e.at); assertNull(e.nearest); assertEquals(0, e.heardBy)
    }

    @Test fun theHeadsetFeedCarriesDevicesLinksAndYou() {
        val ms = listOf(HouseLayout.Measure("Fold", "Flip", 4.0), HouseLayout.Measure("Fold", "S21", 3.0), HouseLayout.Measure("Flip", "S21", 5.0))
        val map = HouseLayout.solve(listOf("Fold", "Flip", "S21"), ms) as HouseLayout.Result.Map
        val you = WhereAmI.Estimate(HouseLayout.Point(1.0, 1.0), 0.7, "Fold", true, 3)
        val j = org.json.JSONObject(HouseFeed.json(map, mapOf("Fold" to "Z Fold6 \"mine\""), ms, you))
        assertEquals(3, j.getJSONArray("devices").length()); assertEquals(3, j.getJSONArray("links").length())
        assertEquals("Z Fold6 \"mine\"", j.getJSONArray("devices").getJSONObject(0).getString("name"))
        assertEquals(0.7, j.getJSONObject("you").getDouble("r"), 1e-9)
        assertTrue(org.json.JSONObject(HouseFeed.json(map, emptyMap(), ms, null)).isNull("you"))
    }
}
