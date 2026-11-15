package app.ultimate.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HouseLayoutTest {
    private fun measures(at: Map<String, HouseLayout.Point>, noise: Double = 0.0, rnd: Random = Random(1), skip: Set<Pair<String, String>> = emptySet()) =
        at.keys.toList().let { ids -> ids.flatMapIndexed { i, a -> ids.drop(i + 1).filter { b -> (a to b) !in skip && (b to a) !in skip }.map { b ->
            HouseLayout.Measure(a, b, at.getValue(a).to(at.getValue(b)) + noise * rnd.nextGaussian()) } } }

    private fun Random.nextGaussian(): Double { var u: Double; do { u = nextDouble() } while (u == 0.0); return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * nextDouble()) }

    /** The map is right if every pair's distance on it matches the true distance (turning or mirroring does not matter). */
    private fun worstPairError(truth: Map<String, HouseLayout.Point>, map: Map<String, HouseLayout.Point>): Double {
        val ids = truth.keys.toList(); var worst = 0.0
        for (i in ids.indices) for (j in i + 1 until ids.size) worst = maxOf(worst, kotlin.math.abs(truth.getValue(ids[i]).to(truth.getValue(ids[j])) - map.getValue(ids[i]).to(map.getValue(ids[j]))))
        return worst
    }

    private val room = mapOf(
        "Fold" to HouseLayout.Point(0.0, 0.0), "Flip" to HouseLayout.Point(3.2, 0.4), "S21" to HouseLayout.Point(1.1, 2.7),
        "S10" to HouseLayout.Point(4.0, 3.5), "Quest" to HouseLayout.Point(2.2, 1.3))

    @Test fun exactDistancesGiveTheExactShape() {
        val r = HouseLayout.solve(room.keys.toList(), measures(room)) as HouseLayout.Result.Map
        assertTrue(worstPairError(room, r.at) < 0.002)
        assertTrue(r.rms < 0.002); assertTrue(r.doubtful.isEmpty())
        assertEquals(0.0, r.at.getValue("Fold").x, 1e-9); assertEquals(0.0, r.at.getValue("Flip").y, 1e-9) // the tidy frame
    }

    @Test fun realisticNoiseGivesARoughlyRightMap() {
        repeat(20) { seed ->
            val r = HouseLayout.solve(room.keys.toList(), measures(room, noise = 0.05, rnd = Random(seed))) as HouseLayout.Result.Map
            assertTrue("seed $seed: ${worstPairError(room, r.at)}", worstPairError(room, r.at) < 0.2)
        }
    }

    @Test fun unmeasuredPairsAreFilledInWhenTheShapeIsStillPinned() {
        val r = HouseLayout.solve(room.keys.toList(), measures(room, skip = setOf("Fold" to "S10", "Flip" to "S21"))) as HouseLayout.Result.Map
        assertTrue(worstPairError(room, r.at) < 0.01)
    }

    @Test fun saysWhichDevicesNeedMoreMeasurements() {
        val ms = listOf(HouseLayout.Measure("A", "B", 2.0), HouseLayout.Measure("B", "C", 2.0), HouseLayout.Measure("A", "C", 2.0), HouseLayout.Measure("C", "D", 1.0))
        val r = HouseLayout.solve(listOf("A", "B", "C", "D"), ms) as HouseLayout.Result.NotEnough
        assertEquals(listOf("D"), r.missing)
        val apart = HouseLayout.solve(listOf("A", "B", "C", "E"), ms.take(3)) as HouseLayout.Result.NotEnough
        assertEquals(listOf("E"), apart.missing)
    }

    @Test fun anEchoIsSingledOut() {
        val ms = measures(room).map { if (it.a == "Flip" && it.b == "S10") it.copy(metres = it.metres + 1.6) else it }
        val r = HouseLayout.solve(room.keys.toList(), ms) as HouseLayout.Result.Map
        assertEquals(1, r.doubtful.size); assertEquals(setOf("Flip", "S10"), setOf(r.doubtful[0].a, r.doubtful[0].b))
    }

    @Test fun repeatsAreAveragedAndJunkIgnored() {
        val ms = listOf(HouseLayout.Measure("A", "B", 1.9), HouseLayout.Measure("B", "A", 2.1), HouseLayout.Measure("A", "B", Double.NaN), HouseLayout.Measure("A", "A", 5.0))
        val r = HouseLayout.solve(listOf("A", "B"), ms) as HouseLayout.Result.Map
        assertEquals(2.0, r.at.getValue("A").to(r.at.getValue("B")), 1e-6)
    }

    @Test fun devicesInARowStillWork() {
        val line = mapOf("A" to HouseLayout.Point(0.0, 0.0), "B" to HouseLayout.Point(1.0, 0.0), "C" to HouseLayout.Point(2.5, 0.0), "D" to HouseLayout.Point(4.0, 0.0))
        val r = HouseLayout.solve(line.keys.toList(), measures(line)) as HouseLayout.Result.Map
        assertTrue(worstPairError(line, r.at) < 0.01)
    }

    @Test fun locatesYouFromDistancesToThreeOrMore() {
        val you = HouseLayout.Point(2.5, 2.0)
        val d = room.mapValues { it.value.to(you) }
        val p = HouseLayout.locate(room, d)
        assertNotNull(p); assertEquals(you.x, p!!.x, 0.01); assertEquals(you.y, p.y, 0.01)
        assertNull(HouseLayout.locate(room, d.entries.take(2).associate { it.key to it.value }))
        val line = mapOf("A" to HouseLayout.Point(0.0, 0.0), "B" to HouseLayout.Point(1.0, 0.0), "C" to HouseLayout.Point(2.0, 0.0))
        assertNull(HouseLayout.locate(line, mapOf("A" to 1.0, "B" to 1.0, "C" to 1.5))) // mirror image fits equally
    }

    @Test fun theMapIsKeptAndComesBack() {
        val names = mapOf("a1" to "Galaxy Z Fold6 (this phone)", "b2" to "Flip\twith tab", "c3" to "S21")
        val ms = listOf(HouseLayout.Measure("a1", "b2", 2.25, 1.0), HouseLayout.Measure("b2", "c3", 1.5, 0.35))
        val (n2, m2) = HouseStore.decode(HouseStore.encode(names, ms))!!
        assertEquals(setOf("a1", "b2", "c3"), n2.keys); assertEquals("Flip with tab", n2["b2"]); assertEquals(ms, m2)
        assertNull(HouseStore.decode(""))
    }
}
