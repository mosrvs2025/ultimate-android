package app.ultimate

import app.ultimate.context.HabitModel
import app.ultimate.context.Situation
import app.ultimate.home.HomeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.Random

class HabitModelTest {
    private val day = 86_400_000L
    private val tools = listOf("audio", "studio", "files", "terminal", "timeline", "photos", "cleanup", "storage", "recorder", "squeeze", "rules", "focus")

    private data class Ev(val at: Long, val s: Situation, val tool: String)

    private fun at(dayIndex: Int, hour: Int): Long = Calendar.getInstance().apply { clear(); set(2026, Calendar.JANUARY, 5 + dayIndex, hour, 10) }.timeInMillis
    private fun weekday(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_WEEK)

    /** A made-up person with real routines (25% of opens are just whatever came to mind) or none at all. */
    private fun life(days: Int, structured: Boolean, seed: Long): List<Ev> {
        val r = Random(seed); val out = ArrayList<Ev>(); var prev: String? = null; var prevAt = 0L
        for (d in 0 until days) for (h in listOf(6, 7, 8, 10, 13, 15, 18, 20, 22, 23)) {
            val t = at(d, h) + r.nextInt(20 * 60_000)
            val wd = weekday(t); val weekend = wd == 1 || wd == 7
            val headphones = structured && h in 6..8 && !weekend && r.nextInt(10) < 8 || r.nextInt(10) == 0
            val charging = h >= 20 && r.nextInt(10) < 9 || r.nextInt(8) == 0
            val wifi = r.nextInt(10) < 7
            val p = if (prev != null && t - prevAt < 30 * 60_000) prev else null
            val routine = when {
                p == "storage" -> "cleanup"
                headphones && h in 6..8 -> "audio"
                weekend && h in 10..15 -> "photos"
                charging && h >= 22 -> "timeline"
                charging && h >= 20 -> "terminal"
                h in 13..15 && !weekend -> "files"
                h in 10..10 && !weekend -> "studio"
                else -> tools[r.nextInt(tools.size)]
            }
            val tool = if (!structured || r.nextInt(4) == 0) tools[r.nextInt(tools.size)] else routine
            out += Ev(t, Situation.of(h, wd, charging, headphones, wifi, prev = p), tool)
            prev = tool; prevAt = t
        }
        return out
    }

    private class Score { var n = 0; var h1 = 0; var h3 = 0 }

    @Test fun benchmarkAgainstWhatHomeDoesToday() {
        val report = StringBuilder("Habit model benchmark (synthetic users; online: predict each open before learning from it; first 14 days are warm-up)\n")
        val results = HashMap<String, Map<String, Double>>()
        for ((name, structured) in listOf("routine user" to true, "random user" to false)) {
            val evs = life(70, structured, seed = 11)
            val model = HabitModel()
            val freq = HashMap<String, Int>()
            val recent = ArrayList<Pair<String, Long>>()
            val sFreq = Score(); val sHome = Score(); val sHabit = Score()
            var fired = 0; var firedRight = 0
            for (e in evs) {
                if (e.at >= at(14, 0)) {
                    fun score(s: Score, top: List<String>) { s.n++; if (top.firstOrNull() == e.tool) s.h1++; if (e.tool in top.take(3)) s.h3++ }
                    score(sFreq, freq.entries.sortedByDescending { it.value }.map { it.key })
                    score(sHome, HomeLayout.constellation(tools, recent.filter { it.second > e.at - 2 * day }, emptySet(), e.at, 3))
                    score(sHabit, model.rank(e.s, e.at, tools).map { it.tool })
                    model.suggest(e.s, e.at, tools)?.let { fired++; if (it.tool == e.tool) firedRight++ }
                }
                model.observe(e.tool, e.s, e.at); freq.merge(e.tool, 1, Int::plus); recent += e.tool to e.at
            }
            fun pct(a: Int, n: Int) = 100.0 * a / n
            report.append("\n$name (${sHabit.n} scored opens, ${tools.size} tools; chance: top-1 8.3%, top-3 25%)\n")
            report.append("  most-opened overall  top-1 %.1f%%  top-3 %.1f%%\n".format(pct(sFreq.h1, sFreq.n), pct(sFreq.h3, sFreq.n)))
            report.append("  Home today (48 h)    top-1 %.1f%%  top-3 %.1f%%\n".format(pct(sHome.h1, sHome.n), pct(sHome.h3, sHome.n)))
            report.append("  habit model          top-1 %.1f%%  top-3 %.1f%%\n".format(pct(sHabit.h1, sHabit.n), pct(sHabit.h3, sHabit.n)))
            report.append("  silent suggestion fired on %.1f%% of opens, right %.1f%% of the time it fired\n".format(pct(fired, sHabit.n), if (fired == 0) 0.0 else pct(firedRight, fired)))
            results[name] = mapOf("freq3" to pct(sFreq.h3, sFreq.n), "home3" to pct(sHome.h3, sHome.n), "habit1" to pct(sHabit.h1, sHabit.n), "habit3" to pct(sHabit.h3, sHabit.n),
                "home1" to pct(sHome.h1, sHome.n), "fired" to pct(fired, sHabit.n), "precision" to if (fired == 0) 0.0 else pct(firedRight, fired))
        }
        File("build").mkdirs(); File("build/habit-benchmark.txt").writeText(report.toString()); println(report)
        val routine = results.getValue("routine user"); val random = results.getValue("random user")
        assertTrue("beats Home's top-3 by 15 points on a routine user", routine.getValue("habit3") >= routine.getValue("home3") + 15)
        assertTrue("beats Home's top-1 on a routine user", routine.getValue("habit1") > routine.getValue("home1"))
        assertTrue("silent suggestions are mostly right when they fire", routine.getValue("precision") >= 70.0)
        assertTrue("stays quiet for a user with no routine (fired ${random.getValue("fired")}%)", random.getValue("fired") < 10.0)
        assertTrue("does not do worse than counting when there is nothing to learn", random.getValue("habit3") >= random.getValue("freq3") - 6)
    }

    @Test fun explainsItselfAndStaysSilentWhenUnsure() {
        val m = HabitModel(); val now = at(30, 21)
        val evening = Situation.of(21, 3, charging = true, headphones = false, wifi = true)
        repeat(8) { m.observe("terminal", evening, now - it * day) }
        repeat(8) { m.observe("audio", Situation.of(7, 3, charging = false, headphones = true, wifi = true), now - it * day) }
        val p = m.suggest(evening, now)
        assertNotNull(p); assertEquals("terminal", p!!.tool)
        assertTrue(p.because.isNotEmpty() && p.because.any { it == "charging" || it == "evening" })
        // A situation it has never seen splits its belief: it must not claim to know.
        assertNull(m.suggest(Situation.of(13, 7, charging = false, headphones = false, wifi = false, watch = "far", prev = "files"), now, minProbability = 0.9))
        // Too little evidence: one open is not a habit.
        val fresh = HabitModel(); fresh.observe("studio", evening, now)
        assertNull(fresh.suggest(evening, now))
    }

    @Test fun oldHabitsFadeAndNewOnesTakeOver() {
        val m = HabitModel(halfLifeDays = 14.0); val now = at(120, 21)
        val s = Situation.of(21, 3, charging = true, headphones = false, wifi = true)
        repeat(20) { m.observe("terminal", s, now - 90 * day - it * day) }
        repeat(6) { m.observe("focus", s, now - it * day) }
        assertEquals("focus", m.rank(s, now).first().tool)
    }

    @Test fun savesAndRestoresAndForgets() {
        val m = HabitModel(); val s = Situation.of(8, 2, charging = false, headphones = true, wifi = false, watch = "near", prev = "files")
        repeat(3) { m.observe("audio", s, at(it, 8)) }
        val back = HabitModel.fromJson(m.toJson())
        assertEquals(3, back.size); assertEquals("audio", back.rank(s, at(4, 8)).first().tool)
        assertEquals(0, HabitModel.fromJson("not json").size)
        back.clear(); assertEquals(0, back.size); assertTrue(back.rank(s, at(4, 8)).isEmpty())
    }

    @Test fun onlyCoarseWordsAreEverStored() {
        val s = Situation.of(23, 1, charging = true, headphones = true, wifi = true, watch = "near", prev = "storage")
        assertEquals(setOf("part", "days", "charging", "headphones", "wifi", "watch", "prev"), s.features.keys)
        assertFalse(s.features.values.any { it.length > 12 })
        assertEquals("night", Situation.part(23)); assertEquals("night", Situation.part(3)); assertEquals("early", Situation.part(6))
    }
}

class HabitVetoTest {
    private val day = 86_400_000L
    private fun evening(charging: Boolean = true, motion: String? = null) = Situation.of(21, 3, charging, headphones = false, wifi = null, motion = motion)

    @Test fun notNowSilencesThatMomentButNotOthersAndWearsOff() {
        val m = HabitModel(); val now = 100 * day
        repeat(10) { m.observe("terminal", evening(), now - it * day) }
        assertEquals("terminal", m.suggest(evening(), now)!!.tool)
        m.veto("terminal", evening(), now)
        assertNull("same part of day, same charging", m.suggest(evening(), now + 3_600_000))
        assertNull("still quiet a week later", m.suggest(evening(), now + 7 * day))
        repeat(10) { m.observe("terminal", evening(), now + 15 * day - it * 3_600_000L) }
        assertEquals("offered again once the veto has worn off", "terminal", m.suggest(evening(), now + 15 * day)!!.tool)
    }

    @Test fun threeRefusalsInAMonthSilenceTheToolEverywhere() {
        val m = HabitModel(); val now = 100 * day
        val morning = Situation.of(8, 3, charging = false, headphones = true, wifi = null)
        repeat(10) { m.observe("audio", morning, now - it * day) }
        m.veto("audio", evening(), now); m.veto("audio", evening(false), now); m.veto("audio", Situation.of(13, 3, false, false, null), now)
        assertNull(m.suggest(morning, now + day))
    }

    @Test fun vetoesAndMotionSurviveSavingAndForgetting() {
        val m = HabitModel(); val now = 100 * day
        repeat(10) { m.observe("terminal", evening(motion = "still"), now - it * day) }
        m.veto("terminal", evening(motion = "still"), now)
        val back = HabitModel.fromJson(m.toJson())
        assertEquals(10, back.size); assertNull(back.suggest(evening(motion = "still"), now + day))
        assertEquals("still", evening(motion = "still").features["motion"]); assertNull(evening().features["motion"])
        back.clear(); assertEquals(0, HabitModel.fromJson(back.toJson()).size)
    }
}
