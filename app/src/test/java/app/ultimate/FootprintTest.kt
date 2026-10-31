package app.ultimate

import app.ultimate.footprint.Evt
import app.ultimate.footprint.Habits
import app.ultimate.footprint.UsageAccumulator
import app.ultimate.footprint.UsageEvt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class FootprintTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val h = 3_600_000L
    private val min = 60_000L
    private val day = 24 * h
    /** Noon on a fixed day, so "today" is easy to reason about. */
    private val now = 20_000L * day + 12 * h

    private fun at(daysAgo: Int, hour: Int, minute: Int = 0) = now - daysAgo * day - 12 * h + hour * h + minute * min
    private fun run(vararg events: UsageEvt) = UsageAccumulator(now, utc).also { a -> events.sortedBy { it.at }.forEach(a::add) }.finish()

    @Test fun anAppOpenedAfterAnotherCountsOnceAndTimesAreAdded() {
        val s = run(
            UsageEvt(at(0, 9), Evt.Resumed, "chat"), UsageEvt(at(0, 9, 10), Evt.Paused, "chat"),
            UsageEvt(at(0, 9, 20), Evt.Resumed, "chat"), UsageEvt(at(0, 9, 25), Evt.Paused, "chat"),
            UsageEvt(at(0, 10), Evt.Resumed, "maps"), UsageEvt(at(0, 10, 30), Evt.Paused, "maps"),
        )
        assertEquals(mapOf("chat" to 2, "maps" to 1), s.opens)
        assertEquals(15 * min, s.foregroundMs["chat"])
        assertEquals(30 * min, s.foregroundMs["maps"])
    }

    @Test fun switchingAppsWithoutAPauseStillClosesTheFirst() {
        val s = run(UsageEvt(at(0, 9), Evt.Resumed, "a"), UsageEvt(at(0, 9, 5), Evt.Resumed, "b"), UsageEvt(at(0, 9, 20), Evt.Paused, "b"))
        assertEquals(5 * min, s.foregroundMs["a"]); assertEquals(15 * min, s.foregroundMs["b"])
        assertEquals(1, s.opens["a"]); assertEquals(1, s.opens["b"])
    }

    @Test fun goingHomeStopsTheClock() {
        val s = run(UsageEvt(at(0, 9), Evt.Resumed, "a"), UsageEvt(at(0, 9, 10), Evt.Idle), UsageEvt(at(0, 11), Evt.Resumed, "b"), UsageEvt(at(0, 11, 5), Evt.Idle))
        assertEquals(10 * min, s.foregroundMs["a"]); assertEquals(5 * min, s.foregroundMs["b"])
    }

    @Test fun useIsSplitAcrossTheHoursItCovers() {
        val s = run(UsageEvt(at(0, 8, 45), Evt.Resumed, "a"), UsageEvt(at(0, 10, 15), Evt.Paused, "a"))
        assertEquals(15 * min, s.heat[0][8]); assertEquals(h, s.heat[0][9]); assertEquals(15 * min, s.heat[0][10])
        assertEquals(9, Habits.busiestHour(s))
    }

    @Test fun daysAgoLandInTheirOwnRowsAndOldOnesAreIgnored() {
        val s = run(UsageEvt(at(2, 20), Evt.Resumed, "a"), UsageEvt(at(2, 21), Evt.Paused, "a"), UsageEvt(at(9, 20), Evt.Resumed, "a"), UsageEvt(at(9, 21), Evt.Paused, "a"))
        assertEquals(h, s.heat[2][20])
        assertEquals(0L, s.heat.sumOf { row -> row.sum() } - h)
    }

    @Test fun anOpenAppIsClosedAtNowAndAnImpossibleStayIsCapped() {
        val s = run(UsageEvt(at(0, 11), Evt.Resumed, "a")) // still open at noon
        assertEquals(h, s.foregroundMs["a"])
        val capped = UsageAccumulator(now, utc).also { it.add(UsageEvt(at(1, 1), Evt.Resumed, "b")); it.add(UsageEvt(at(0, 11), Evt.Paused, "b")) }.finish()
        assertEquals(6 * h, capped.foregroundMs["b"])
    }

    @Test fun pickupsUnlocksScreenTimeAndLongestSession() {
        val s = run(
            UsageEvt(at(0, 8), Evt.ScreenOn), UsageEvt(at(0, 8), Evt.Unlocked), UsageEvt(at(0, 8, 10), Evt.ScreenOff),
            UsageEvt(at(0, 9), Evt.ScreenOn), UsageEvt(at(0, 9), Evt.Unlocked), UsageEvt(at(0, 9, 40), Evt.ScreenOff),
        )
        assertEquals(2, s.pickups); assertEquals(2, s.unlocks)
        assertEquals(50 * min, s.screenMs); assertEquals(40 * min, s.longestSessionMs)
        assertEquals(1, s.unlocksByHour[8]); assertEquals(1, s.unlocksByHour[9])
    }

    @Test fun aLongDarkNightIsGuessedAsSleepAndAveraged() {
        val s = run(
            UsageEvt(at(2, 23, 30), Evt.ScreenOn), UsageEvt(at(2, 23, 40), Evt.ScreenOff), UsageEvt(at(1, 7, 0), Evt.ScreenOn), UsageEvt(at(1, 7, 5), Evt.ScreenOff),
            UsageEvt(at(1, 23, 50), Evt.ScreenOn), UsageEvt(at(0, 0, 10), Evt.ScreenOff), UsageEvt(at(0, 7, 20), Evt.ScreenOn),
        )
        assertEquals(2, s.sleeps.size)
        val (bed, wake) = Habits.typicalSleep(s, utc)!!
        assertEquals("11:55pm", Habits.clock(bed)) // 11:40pm and 12:10am (after midnight counts as the same night) average to 11:55pm
        assertEquals("7:10am", Habits.clock(wake))
    }

    @Test fun anAfternoonGapIsNotSleep() {
        val s = run(UsageEvt(at(0, 9), Evt.ScreenOn), UsageEvt(at(0, 9, 5), Evt.ScreenOff), UsageEvt(at(0, 11, 55), Evt.ScreenOn))
        assertTrue(s.sleeps.isEmpty()); assertNull(Habits.typicalSleep(s, utc))
    }

    @Test fun sentencesSayWhatTheNumbersAre() {
        val s = run(
            UsageEvt(at(0, 21), Evt.Resumed, "chat"), UsageEvt(at(0, 22), Evt.Paused, "chat"), UsageEvt(at(0, 1), Evt.Resumed, "chat"), UsageEvt(at(0, 1, 30), Evt.Paused, "chat"),
            UsageEvt(at(0, 9), Evt.Unlocked), UsageEvt(at(0, 9, 1), Evt.Unlocked),
        )
        val text = Habits.sentences(s, { if (it == "chat") "Chat" else it }, utc)
        assertTrue(text.toString(), text.any { it.startsWith("You opened Chat 2 times") })
        assertTrue(text.any { it.contains("busiest hour is 9pm to 10pm") })
        assertTrue(text.any { it.contains("30 minutes between midnight and 5am") })
        assertNotNull(text.firstOrNull { it.startsWith("You unlocked") })
    }
}

class FootprintMathTest {
    private val g = 2; private val d = 0

    @Test fun onlyGrantedPermissionsCountAndBackgroundLocationWins() {
        val req = listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.RECORD_AUDIO", "android.permission.CAMERA", "android.permission.INTERNET", "com.vendor.CUSTOM")
        val set = app.ultimate.footprint.PermissionMap.granted(req, intArrayOf(g, g, d, g, g, g))
        assertEquals(setOf(app.ultimate.footprint.Access.BackgroundLocation, app.ultimate.footprint.Access.Camera, app.ultimate.footprint.Access.Internet), set)
        assertEquals(emptySet<app.ultimate.footprint.Access>(), app.ultimate.footprint.PermissionMap.granted(null, null))
        assertEquals(emptySet<app.ultimate.footprint.Access>(), app.ultimate.footprint.PermissionMap.granted(listOf("android.permission.CAMERA"), null))
    }

    @Test fun dataUseAddsUpPerAppAndSkipsUnknownUids() {
        val rows = app.ultimate.footprint.NetMath.byApp(
            listOf(app.ultimate.footprint.NetMath.Bucket(1, 100, 10), app.ultimate.footprint.NetMath.Bucket(1, 50, 5), app.ultimate.footprint.NetMath.Bucket(2, 1000, 0), app.ultimate.footprint.NetMath.Bucket(3, 9, 9), app.ultimate.footprint.NetMath.Bucket(2, 0, 0)),
        ) { mapOf(1 to "chat", 2 to "video")[it] }
        assertEquals(listOf("video", "chat"), rows.map { it.pkg })
        assertEquals(165L, rows[1].total)
    }

    @Test fun stepsPerDayAndARestartStartsOver() {
        val utc = TimeZone.getTimeZone("UTC"); val day = 86_400_000L; val h = 3_600_000L
        val base = 20_000L * day
        val samples = listOf(
            app.ultimate.footprint.StepSample(base + 8 * h, 1000), app.ultimate.footprint.StepSample(base + 12 * h, 3500), app.ultimate.footprint.StepSample(base + 20 * h, 6000),
            app.ultimate.footprint.StepSample(base + day + 9 * h, 6400), app.ultimate.footprint.StepSample(base + day + 10 * h, 120), // restarted
            app.ultimate.footprint.StepSample(base + day + 18 * h, 4120),
        )
        val perDay = app.ultimate.footprint.StepMath.daily(samples, utc)
        assertEquals(5000L, perDay[20_000L])               // 2500 + 2500 on the first day
        assertEquals(400L + 120L + 4000L, perDay[20_001L])    // 400 before the restart, 120 after it, then 4000
    }
}
