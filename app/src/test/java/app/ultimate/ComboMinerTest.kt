package app.ultimate

import app.ultimate.context.Combo
import app.ultimate.context.ComboMiner
import app.ultimate.context.Ev
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

class ComboMinerTest {
    private val sig = listOf("watch:flick_right", "watch:flick_left", "watch:twist", "echo:approach", "echo:recede", "air:pinch", "air:swipe", "spatial:watch_near", "spatial:watch_gone",
        "body:heart_high", "body:mood_low", "spatial:place_home", "studio:wave", "echo:push")
    private val opens = listOf("open:player", "open:orb", "open:studio", "open:files", "open:terminal", "open:storage", "open:recorder", "open:squeeze", "open:echo", "open:watch", "open:rules", "open:timeline")
    private val day = 86_400_000L

    /** A made-up month: lots of unrelated events, plus (optionally) two planted interactions. */
    private fun month(seed: Long, planted: Boolean): List<Ev> {
        val r = Random(seed); val out = ArrayList<Ev>()
        for (d in 0 until 30) {
            // A deliberate gesture like a right flick is rare outside the planted use (one stray a day); everything else is everywhere.
            val common = sig.filter { it != "watch:flick_right" }
            repeat(220) { out += Ev(d * day + (r.nextDouble() * day).toLong(), if (r.nextInt(2) == 0) common[r.nextInt(common.size)] else opens[r.nextInt(opens.size)]) }
            out += Ev(d * day + (r.nextDouble() * day).toLong(), "watch:flick_right")
            if (!planted) continue
            repeat(3) { val t = d * day + (r.nextDouble() * day).toLong()
                out += Ev(t, "watch:flick_right"); if (r.nextInt(100) < 85) out += Ev(t + 2_000 + r.nextInt(18_000), "open:player") }
            repeat(4) { val t = d * day + (r.nextDouble() * day).toLong()
                out += Ev(t, "spatial:watch_near"); out += Ev(t + 5_000 + r.nextInt(25_000), "echo:approach"); if (r.nextInt(100) < 90) out += Ev(t + 35_000 + r.nextInt(15_000), "open:orb") }
        }
        return out
    }

    private fun List<Combo>.single(a: String, b: String) = firstOrNull { it.when_ == listOf(a) && it.then == b }
    private fun List<Combo>.pair(a: String, b: String, then: String) = firstOrNull { it.when_.toSet() == setOf(a, b) && it.then == then }

    @Test fun benchmarkFindsPlantedInteractionsAndInventsNone() {
        val miner = ComboMiner()
        var foundSingle = 0; var foundPair = 0; var pairEmergent = 0; var falseInPlanted = 0
        val noiseFalse = ArrayList<Int>()
        val seeds = 1L..12L
        for (s in seeds) {
            val r = miner.mine(month(s, true))
            if (r.single("watch:flick_right", "open:player") != null) foundSingle++
            val p = r.pair("spatial:watch_near", "echo:approach", "open:orb")
            if (p != null) { foundPair++; if (p.emergent) pairEmergent++ }
            falseInPlanted += r.count { c -> !(c.then == "open:player" && c.when_.contains("watch:flick_right")) && !(c.then == "open:orb" && (c.when_.contains("echo:approach") || c.when_.contains("spatial:watch_near"))) }
            noiseFalse += miner.mine(month(100 + s, false)).size
        }
        val n = seeds.count()
        val report = "Combo miner benchmark (synthetic months, ${n} seeds each; 220 unrelated events a day, 14 signals and 12 tools)\n" +
            "  planted single interaction (flick -> player, 85%):          found $foundSingle/$n\n" +
            "  planted emergent pair (watch near + approach -> orb, 90%):  found $foundPair/$n, flagged emergent $pairEmergent/$n\n" +
            "  unplanted discoveries in months with planted ones:          $falseInPlanted total (${"%.2f".format(falseInPlanted.toDouble() / n)} per month)\n" +
            "  discoveries in months of pure noise:                        ${noiseFalse.sum()} total (${"%.2f".format(noiseFalse.average())} per month, worst ${noiseFalse.max()})\n"
        File("build").mkdirs(); File("build/combo-benchmark.txt").writeText(report); println(report)
        assertTrue("finds the single interaction (found $foundSingle/$n)", foundSingle >= n - 1)
        assertTrue("finds the pair (found $foundPair/$n)", foundPair >= n - 1)
        assertTrue("calls the pair emergent ($pairEmergent/$n)", pairEmergent >= n - 1)
        assertTrue("nearly nothing invented from noise (${noiseFalse.average()} per month)", noiseFalse.average() < 0.5)
        assertTrue("little unplanted junk beside real findings (${falseInPlanted.toDouble() / n} per month)", falseInPlanted.toDouble() / n < 1.0)
    }

    @Test fun aCombinationThatOnlyWorksTogetherIsMarkedEmergentAndItsPartsAreNot() {
        val r = ComboMiner().mine(month(5, true))
        val pair = r.pair("spatial:watch_near", "echo:approach", "open:orb")!!
        assertTrue(pair.emergent); assertTrue(pair.confidence > pair.alone + 0.25)
        assertNull("neither part alone is the discovery", r.single("spatial:watch_near", "open:orb")); assertNull(r.single("echo:approach", "open:orb"))
    }

    @Test fun oneBurstOnOneDayIsNotAHabit() {
        val burst = ArrayList<Ev>(); repeat(200) { burst += Ev(it * 40_000L, sig[it % sig.size]) }
        repeat(8) { burst += Ev(1_000_000L + it * 7_000_000L, "watch:twist"); burst += Ev(1_005_000L + it * 7_000_000L, "open:studio") }
        assertTrue("all on day zero", ComboMiner().mine(burst).none { it.then == "open:studio" && it.when_ == listOf("watch:twist") })
    }

    @Test fun tooLittleDataSaysNothing() {
        assertTrue(ComboMiner().mine(emptyList()).isEmpty())
        assertTrue(ComboMiner().mine(listOf(Ev(0, "a"), Ev(1, "open:b"))).isEmpty())
    }

    @Test fun reportsTheTypicalDelayAndWhyItMatters() {
        val c = ComboMiner().mine(month(2, true)).single("watch:flick_right", "open:player")!!
        assertTrue("delay between 2 and 20 s (was ${c.medianLagMs})", c.medianLagMs in 2_000..20_000)
        assertTrue(c.lift > 4 && c.days >= 2 && c.support >= 5 && c.confidence >= 0.6)
        assertEquals(false, c.emergent)
        assertFalse(c.score.isNaN())
    }
}
