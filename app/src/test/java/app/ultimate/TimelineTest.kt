package app.ultimate

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.ultimate.core.UltimateTheme
import app.ultimate.timeline.Moment
import app.ultimate.timeline.Source
import app.ultimate.timeline.TimelineMath
import app.ultimate.timeline.TimelineScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/** A made-up month: focus sessions on some days, WhatsApp pinging during them, codes copied from Gmail, late nights on YouTube. */
internal object Month {
    val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private const val H = 3_600_000L; private const val D = 24 * H
    val now = 20_030L * D + 18 * H
    fun build(): List<Moment> = buildList {
        for (d in 0 until 20) {
            val day = now - (19 - d) * D - 18 * H
            val focusDay = d % 3 == 0
            // Fewer notifications on focus days.
            repeat(if (focusDay) 10 else 30) { i -> add(Moment(Source.Notification, day + 8 * H + i * 20 * 60_000L, "Message $i", "hi", if (i % 2 == 0) "WhatsApp" else "Slack")) }
            if (focusDay) {
                add(Moment(Source.Focus, day + 9 * H, "Essay", "45:00 focused", "Focus", end = day + 9 * H + 45 * 60_000L, focusDrift = 2))
                add(Moment(Source.Notification, day + 9 * H + 10 * 60_000L, "Mum", "call me", "WhatsApp"))
            }
            // Watching: more on busy days.
            add(Moment(Source.Watched, day + 20 * H, "Video", "Channel", "YouTube", end = day + 20 * H + (if (focusDay) 20 else 90) * 60_000L))
            // A sign-in code copied a minute after Gmail's notification.
            if (d % 4 == 0) { add(Moment(Source.Notification, day + 12 * H, "Your code", "123456", "Gmail")); add(Moment(Source.Clip, day + 12 * H + 60_000, "123456", "123456", "Ultimate")) }
            // Up late on YouTube some nights.
            if (d % 5 == 0) add(Moment(Source.Watched, day + D + 1 * H, "Late video", "", "YouTube", end = day + D + 2 * H))
        }
    }.sortedBy { it.at }
}

class TimelineMathTest {
    private val all = Month.build()

    @Test fun searchMatchesEveryWordAcrossKinds() {
        assertTrue(TimelineMath.search(all, "code gmail").all { it.app == "Gmail" })
        assertEquals(5, TimelineMath.search(all, "123456").count { it.source == Source.Clip })
        assertTrue(TimelineMath.search(all, "   ").isEmpty())
        val hits = TimelineMath.search(all, "video"); assertTrue(hits.zipWithNext().all { (a, b) -> a.at >= b.at }) // newest first
    }

    @Test fun daysAndHoursAddUp() {
        val days = TimelineMath.days(all, 30, Month.now, Month.utc)
        assertEquals(30, days.size); assertEquals(TimelineMath.dayOf(Month.now, Month.utc), days.last().day)
        assertEquals(all.count { it.source == Source.Notification }, days.sumOf { it.notifications })
        val today = TimelineMath.onDay(all, TimelineMath.dayOf(Month.now, Month.utc), Month.utc)
        assertEquals(today.size, TimelineMath.hours(today, Month.utc).sumOf { it.sum() })
    }

    @Test fun findingsOnlyVisibleAcrossRecords() {
        val said = TimelineMath.insights(all, Month.now, Month.utc)
        assertTrue(said.toString(), said.any { "pinged you" in it && "while you were focusing" in it && "28 interruptions across 7 sessions" in it })
        assertTrue(said.toString(), said.any { "On days you used Focus you got" in it && "fewer notifications" in it })
        assertTrue(said.toString(), said.any { "as much on your busiest notification days" in it })
        assertTrue(said.toString(), said.any { "things you copied came right after a notification, most often from Gmail" in it })
        assertTrue(said.toString(), said.any { "up past midnight" in it && "YouTube" in it })
    }

    @Test fun aNewPhoneSaysNothingRatherThanGuess() {
        assertTrue(TimelineMath.insights(all.take(5), Month.now, Month.utc).isEmpty())
        assertTrue(TimelineMath.insights(emptyList(), Month.now, Month.utc).isEmpty())
    }

    @Test fun dayStartsLineUpWithDays() {
        val d = TimelineMath.dayOf(Month.now, Month.utc)
        assertEquals(d, TimelineMath.dayOf(TimelineMath.startOf(d, Month.utc), Month.utc))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UltimateApp::class)
class TimelineScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsFindingsAndSearchesEverything() {
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        val now = System.currentTimeMillis()
        val moments = listOf(
            Moment(Source.Notification, now - 3_600_000, "Dinner at 7?", "From Mum", "WhatsApp"),
            Moment(Source.Clip, now - 1_800_000, "https://example.com/recipe", "https://example.com/recipe", "Ultimate"),
            Moment(Source.Watched, now - 900_000, "Pasta in 10 minutes", "Chef", "YouTube", end = now - 300_000),
        )
        compose.setContent { UltimateTheme { TimelineScreen(app, "", {}, preset = moments) } }
        compose.onNodeWithText("What your records say together").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("pasta")
        compose.onNodeWithText("1 found").assertExists()
        compose.onNodeWithText("Pasta in 10 minutes").assertExists()
    }
}

class NewRecordsTest {
    private val utc = TimeZone.getTimeZone("UTC"); private val H = 3_600_000L; private val D = 24 * H
    private val now = 20_030L * D + 18 * H

    @Test fun screenshotsPhotosInstallsAndChargingSaySomething() {
        val all = buildList {
            for (d in 0 until 14) {
                val day = now - (13 - d) * D - 18 * H
                repeat(2) { add(Moment(Source.Screenshot, day + 23 * H + it * 60_000L, "Screenshot")) }
                if (d % 7 == 5) repeat(8) { add(Moment(Source.Photo, day + 14 * H + it * 60_000L, "Camera")) } // Saturdays out with the camera
                if (d % 4 == 0) add(Moment(Source.Install, day + 10 * H, "App $d"))
                // On the charger 11pm to 7am most nights, plugged in at around 20%.
                if (d % 7 != 0) add(Moment(Source.Charge, day + 23 * H, "20% → 100%", end = day + D + 7 * H, level = 20 + d % 3, level2 = 100))
            }
        }.sortedBy { it.at }
        val said = TimelineMath.insights(all, now, utc)
        assertTrue(said.toString(), said.any { it.startsWith("You took 28 screenshots, most around 11pm") })
        assertTrue(said.toString(), said.any { "photos; your camera comes out most on" in it })
        assertTrue(said.toString(), said.any { it.startsWith("You installed 4 apps, most recently App 12") })
        assertTrue(said.toString(), said.any { "You charged overnight on" in it })
        assertTrue(said.toString(), said.any { it.startsWith("You usually plug in at about 2") })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargeLogTest {
    @Test fun pluggingInAndOutMakesASession() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        c.getSharedPreferences("charging", 0).edit().clear().commit()
        app.ultimate.timeline.ChargeLog.plugged(c, at = 1_000)
        assertEquals(1_000L, app.ultimate.timeline.ChargeLog.current(c, now = 5_000)!!.start)
        app.ultimate.timeline.ChargeLog.unplugged(c, at = 3_601_000)
        val s = app.ultimate.timeline.ChargeLog.sessions(c).single()
        assertEquals(1_000L to 3_601_000L, s.start to s.end)
        assertEquals(null, app.ultimate.timeline.ChargeLog.current(c))
        app.ultimate.timeline.ChargeLog.unplugged(c, at = 9_999_999) // unplugged twice: nothing new
        assertEquals(1, app.ultimate.timeline.ChargeLog.sessions(c).size)
    }
}

class AboutMeTest {
    @Test fun summarisesEachRecordAndSkipsWhatIsMissing() {
        val text = app.ultimate.timeline.AboutMe.build(Month.build(), listOf("You unlocked your phone about 80 times a day."), listOf("WhatsApp", "YouTube", "Chrome"), Month.now, zone = Month.utc)
        assertTrue(text.startsWith("# About me, from my phone (last 30 days)"))
        assertTrue(text, "## Daily rhythm\n- You unlocked your phone about 80 times a day." in text)
        assertTrue(text, "## Apps I use most\n- WhatsApp, YouTube, Chrome" in text)
        assertTrue(text, "## Notifications" in text && "Most from: WhatsApp (237), Slack (230)" in text)
        assertTrue(text, "## What I watch and listen to" in text && "mostly on YouTube" in text)
        assertTrue(text, "## Focus" in text && "\"Essay\"" in text)
        assertTrue(text, "## Patterns across all of it" in text)
        assertTrue(text, "## Photos" !in text) // none in this month, so no empty section
    }

    @Test fun anEmptyPhoneGivesJustTheHeading() {
        val text = app.ultimate.timeline.AboutMe.build(emptyList(), emptyList(), emptyList(), Month.now)
        assertTrue(text, "##" !in text)
    }
}

class LiveNotificationsTest {
    @Test fun keepsTapActionsOnlyWhileInTheShade() {
        app.ultimate.inbox.LiveNotifications.put("k", null)
        assertEquals(null, app.ultimate.inbox.LiveNotifications.intent("k"))
        app.ultimate.inbox.LiveNotifications.remove("k")
        assertEquals(null, app.ultimate.inbox.LiveNotifications.intent("k"))
    }
}

class ClipSmartTest {
    private fun k(s: String) = app.ultimate.clips.ClipSmart.kind(s)

    @Test fun recognisesWhatWasCopied() {
        assertEquals(app.ultimate.clips.ClipSmart.Kind.Json, k("""{"metadata": {"id": 4, "tags": ["a"]}}"""))
        assertEquals(app.ultimate.clips.ClipSmart.Kind.Json, k("""[1, 2, 3]"""))
        assertEquals(app.ultimate.clips.ClipSmart.Kind.Plain, k("{not json at all}"))
        assertEquals(app.ultimate.clips.ClipSmart.Kind.Url, k("https://example.com/a?b=1")); assertEquals(app.ultimate.clips.ClipSmart.Kind.Url, k("www.example.com"))
        assertEquals(app.ultimate.clips.ClipSmart.Kind.Color, k("#3FA66B")); assertEquals(app.ultimate.clips.ClipSmart.Kind.Color, k("#fff")); assertEquals(app.ultimate.clips.ClipSmart.Kind.Plain, k("123456")) // a number is not a colour
        assertEquals(app.ultimate.clips.ClipSmart.Kind.Path, k("/storage/emulated/0/Movies/clip.mp4")); assertEquals(app.ultimate.clips.ClipSmart.Kind.Path, k("content://media/external/images/1"))
        assertEquals(app.ultimate.clips.ClipSmart.Kind.Command, k("git status")); assertEquals(app.ultimate.clips.ClipSmart.Kind.Command, k("$ ls -la")); assertEquals(app.ultimate.clips.ClipSmart.Kind.Command, k("./gradlew build"))
        assertEquals(app.ultimate.clips.ClipSmart.Kind.Plain, k("Dinner at 7?")); assertEquals(app.ultimate.clips.ClipSmart.Kind.Plain, k(""))
    }

    @Test fun prettifiesColoursAndPrompts() {
        val pretty = app.ultimate.clips.ClipSmart.pretty("""{"a":1,"b":[2,3]}""")!!
        assertTrue(pretty, pretty.contains("\n") && pretty.contains("\"a\": 1"))
        assertEquals(0xFF3FA66BL, app.ultimate.clips.ClipSmart.color("#3FA66B")); assertEquals(0xFFFFFFFFL, app.ultimate.clips.ClipSmart.color("#fff"))
        assertEquals("ls -la", app.ultimate.clips.ClipSmart.command("$ ls -la"))
    }
}

class StabilityTest {
    @Test fun identicalNotificationsInTheSameMillisecondStillGetDifferentKeys() {
        val a = Moment(Source.Notification, 1000, "Uploading", "75 MB / 79 MB", "YT Studio", id = "41")
        val b = a.copy(id = "42")
        assertTrue(a.uid != b.uid)
        // Without an id (older data), the text still tells them apart; the same record keeps the same key.
        val c = Moment(Source.Notification, 1000, "Uploading", "75 MB / 79 MB"); val d = Moment(Source.Notification, 1000, "Uploading", "76 MB / 79 MB")
        assertTrue(c.uid != d.uid); assertEquals(c.uid, c.copy().uid)
    }

    @Test fun findingsCarryTheFilterThatShowsThem() {
        val f = TimelineMath.findings(Month.build(), Month.now, Month.utc)
        val interrupt = f.first { "while you were focusing" in it.text }
        assertEquals(Source.Notification, interrupt.source); assertTrue(interrupt.query!!.isNotBlank())
        assertTrue(f.first { "up past midnight" in it.text }.lateNight)
        assertEquals(Source.Clip, f.first { "things you copied" in it.text }.source)
    }

    @Test fun lateNightsNameWhatYouWereDoingNotAPhotoFolder() {
        val H = 3_600_000L; val D = 24 * H; val now = 20_030L * D + 12 * H
        val all = (0 until 5).flatMap { d -> val night = now - d * D - 12 * H + 2 * H
            listOf(Moment(Source.Photo, night, "res", "", "res"), Moment(Source.Photo, night + 60_000, "res", "", "res"), Moment(Source.Watched, night, "Video", "", "YouTube", end = night + H)) }
        val late = TimelineMath.insights(all, now, Month.utc).first { "up past midnight" in it }
        assertTrue(late, "YouTube" in late && "res" !in late)
    }

    @Test fun pinnedToolsComeFirstAndPinsToggle() {
        assertEquals(listOf("terminal", "timeline", "storage", "squeeze"), app.ultimate.home.HomeLayout.withPins(listOf("terminal", "timeline"), listOf("storage", "terminal", "squeeze", "files"), listOf("terminal", "timeline", "storage", "squeeze", "files")))
        assertEquals(listOf("storage", "squeeze", "files", "studio"), app.ultimate.home.HomeLayout.withPins(listOf("gone"), listOf("storage", "squeeze", "files", "studio"), listOf("storage", "squeeze", "files", "studio")))
        assertEquals(listOf("a", "b"), app.ultimate.home.HomeLayout.togglePin(listOf("a"), "b")); assertEquals(listOf("b"), app.ultimate.home.HomeLayout.togglePin(listOf("a", "b"), "a"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressNotificationTest {
    @Test fun anUploadIsOneEntryThatUpdatesInPlace() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        c.getDatabasePath("notifications.db").delete()
        val log = app.ultimate.inbox.NotificationLog(c)
        log.add("com.google.android.apps.youtube.creator", "YT Studio", 1_000, "Uploading", "75 MB / 79 MB", null, "up", progress = true)
        log.add("com.google.android.apps.youtube.creator", "YT Studio", 2_000, "Uploading", "76 MB / 79 MB", null, "up", progress = true)
        log.add("com.google.android.apps.youtube.creator", "YT Studio", 3_000, "Upload complete", "My video", null, "up", progress = false)
        val all = log.since(0)
        assertEquals(1, all.size)
        assertEquals("Upload complete", all[0].title); assertEquals(1_000L, all[0].at) // kept from when it started
        // A new upload later is a new entry.
        log.add("com.google.android.apps.youtube.creator", "YT Studio", 9_000, "Uploading", "1 MB / 50 MB", null, "up", progress = true)
        assertEquals(2, log.since(0).size)
        log.close()
    }
}
