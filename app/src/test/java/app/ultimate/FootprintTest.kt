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

class MediaTrackerTest {
    private val min = 60_000L
    private fun run(block: (app.ultimate.footprint.MediaTracker) -> Unit): List<app.ultimate.footprint.Played> {
        val out = ArrayList<app.ultimate.footprint.Played>(); app.ultimate.footprint.MediaTracker(out::add).also(block); return out
    }

    @Test fun playingAndPausingAddsUpOnlyThePlayingTime() {
        val r = run { t -> t.update(0, "yt", "Cat video", "Cats", true); t.update(5 * min, "yt", "Cat video", "Cats", false); t.update(6 * min, "yt", "Cat video", "Cats", true); t.update(9 * min, "yt", "Cat video", "Cats", false); t.end(30 * min, "yt") }
        assertEquals(1, r.size); assertEquals(8 * min, r[0].ms); assertEquals("Cats", r[0].artist); assertEquals(0L, r[0].startedAt)
    }

    @Test fun aNewTitleEndsTheOldOne() {
        val r = run { t -> t.update(0, "music", "Song A", "Band", true); t.update(4 * min, "music", "Song B", "Band", true); t.update(7 * min, "music", "Song B", "Band", false); t.endAll(8 * min) }
        assertEquals(listOf("Song A" to 4 * min, "Song B" to 3 * min), r.map { it.title to it.ms })
    }

    @Test fun aLongPauseIsANewSittingAndTinyPlaysAreDropped() {
        val r = run { t -> t.update(0, "yt", "Show", "S", true); t.update(20 * min, "yt", "Show", "S", false); t.update(60 * min, "yt", "Show", "S", true); t.update(65 * min, "yt", "Show", "S", false); t.endAll(66 * min)
            t.update(100 * min, "yt", "Blip", "B", true); t.update(100 * min + 3_000, "yt", "Blip", "B", false); t.endAll(101 * min) }
        assertEquals(listOf(20 * min, 5 * min), r.map { it.ms })
        assertTrue(r.none { it.title == "Blip" })
    }

    @Test fun twoAppsAtOnceAreTrackedSeparatelyAndNothingLoadedIsIgnored() {
        val r = run { t -> t.update(0, "a", "One", "", true); t.update(0, "b", "Two", "", true); t.update(10 * min, "a", "", "", false); t.endAll(20 * min) }
        assertEquals(setOf("a" to 10 * min, "b" to 20 * min), r.map { it.pkg to it.ms }.toSet())
    }

    @Test fun statsRankTimeByAppAndByArtist() {
        val list = listOf(app.ultimate.footprint.Played("yt", "x", "Chan", 0, 30 * min), app.ultimate.footprint.Played("sp", "y", "Band", 0, 50 * min), app.ultimate.footprint.Played("yt", "z", "Chan", 0, 40 * min), app.ultimate.footprint.Played("yt", "w", "", 0, 5 * min))
        assertEquals(listOf("yt" to 75 * min, "sp" to 50 * min), app.ultimate.footprint.MediaStats.byApp(list))
        assertEquals(listOf("Chan" to 70 * min, "Band" to 50 * min), app.ultimate.footprint.MediaStats.topArtists(list))
    }
}

class PlaceMathTest {
    private val utc = TimeZone.getTimeZone("UTC"); private val h = 3_600_000L; private val day = 24 * h
    private val d0 = 20_000L * day
    private fun shot(dayN: Int, hour: Int, lat: Double, lon: Double) = app.ultimate.footprint.Shot(d0 + dayN * day + hour * h, lat, lon)
    // Home near Brooklyn, work near midtown (about 10 km away), a trip to Boston (about 300 km away).
    private val shots = listOf(
        shot(0, 23, 40.6782, -73.9442), shot(1, 23, 40.6785, -73.9440), shot(2, 22, 40.6779, -73.9445), shot(3, 1, 40.6783, -73.9441),
        shot(1, 13, 40.7549, -73.9840), shot(2, 13, 40.7551, -73.9838),
        shot(5, 15, 42.3601, -71.0589), shot(5, 16, 42.3603, -71.0591), shot(6, 11, 42.3605, -71.0587),
    )

    @Test fun distancesAreRight() {
        assertEquals(3936.0, app.ultimate.footprint.PlaceMath.distanceKm(40.7128, -74.0060, 34.0522, -118.2437), 15.0) // New York to Los Angeles
        assertEquals(0.0, app.ultimate.footprint.PlaceMath.distanceKm(10.0, 20.0, 10.0, 20.0), 1e-9)
    }

    @Test fun nearbyShotsFormOnePlace() {
        val places = app.ultimate.footprint.PlaceMath.cluster(shots, zone = utc)
        assertEquals(3, places.size)
        assertEquals(listOf(4, 3, 2), places.map { it.count })
        assertEquals(40.678, places[0].lat, 0.001)
    }

    @Test fun homeIsWherePhotosAreTakenAtNight() {
        val places = app.ultimate.footprint.PlaceMath.cluster(shots, zone = utc)
        val home = app.ultimate.footprint.PlaceMath.home(places)!!
        assertEquals(40.678, home.lat, 0.001); assertEquals(4, home.nightShots)
        // With no night photos, the busiest place wins.
        val daytime = app.ultimate.footprint.PlaceMath.cluster(shots.filter { (it.at / h) % 24 in 8..20 }, zone = utc)
        assertEquals(42.36, app.ultimate.footprint.PlaceMath.home(daytime)!!.lat, 0.01)
    }

    @Test fun tripsAreCountedByDayAndFarthestIsFound() {
        val places = app.ultimate.footprint.PlaceMath.cluster(shots, zone = utc)
        val home = app.ultimate.footprint.PlaceMath.home(places)!!
        assertEquals(2, app.ultimate.footprint.PlaceMath.daysAway(shots, home, zone = utc)) // two days in Boston
        val (far, km) = app.ultimate.footprint.PlaceMath.farthest(places, home)!!
        assertEquals(42.36, far.lat, 0.01); assertEquals(300.0, km, 20.0)
        assertNull(app.ultimate.footprint.PlaceMath.farthest(listOf(home), home))
    }

    @Test fun sentencesNameTheNumbers() {
        val places = app.ultimate.footprint.PlaceMath.cluster(shots, zone = utc)
        val said = app.ultimate.footprint.PlaceMath.sentences(shots, places, utc)
        assertTrue(said.toString(), said[0].startsWith("Your photos put you in 3 different places"))
        assertTrue(said.any { it.contains("40.68°N 73.94°W") })
        assertTrue(said.any { it.contains("away from home on 2 days") })
        assertEquals(emptyList<String>(), app.ultimate.footprint.PlaceMath.sentences(emptyList(), emptyList()))
    }
}

class HealthMathTest {
    private val days = listOf(
        app.ultimate.footprint.HealthDay(1, 8000, 7 * 3_600_000L, 70, 52, 120),
        app.ultimate.footprint.HealthDay(2, 12000, 8 * 3_600_000L, 72, 50, 130),
        app.ultimate.footprint.HealthDay(3, 0, 0, null, null, null),
    )
    @Test fun averagesIgnoreEmptyDays() {
        assertEquals(10000L, app.ultimate.footprint.HealthMath.avgSteps(days))
        assertEquals(7L * 3_600_000 + 1_800_000, app.ultimate.footprint.HealthMath.avgSleepMs(days))
        assertEquals(52, app.ultimate.footprint.HealthMath.restingBpm(days))
        assertEquals(0L, app.ultimate.footprint.HealthMath.avgSteps(emptyList()))
    }
    @Test fun sentencesNameTheNumbers() {
        val said = app.ultimate.footprint.HealthMath.sentences(days)
        assertTrue(said.toString(), said.any { it.contains("10,000 steps a day") })
        assertTrue(said.any { it.contains("12,000 steps") })
        assertTrue(said.any { it.contains("7h 30m a night") })
        assertTrue(said.any { it.startsWith("That is 2 days") })
        assertEquals(emptyList<String>(), app.ultimate.footprint.HealthMath.sentences(emptyList()))
    }
}
