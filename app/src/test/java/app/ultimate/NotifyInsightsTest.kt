package app.ultimate

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.ultimate.inbox.AppSignal
import app.ultimate.inbox.Fate
import app.ultimate.inbox.NotificationLog
import app.ultimate.inbox.NotifyMath
import app.ultimate.inbox.Rec
import app.ultimate.inbox.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

class NotifyMathTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val h = 3_600_000L; private val day = 24 * h
    private val d0 = 20_000L * day
    private fun rec(pkg: String, at: Long, title: String = "t", text: String = "x", who: String? = null, cat: String? = null, fate: Fate = Fate.Waiting, gone: Long = 0, back: Boolean = false) =
        Rec(pkg, pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }, at, title, text, who, cat, fate, gone, back)

    @Test fun androidRemovalReasonsMeanWhatTheySay() {
        assertEquals(Fate.Tapped, Fate.fromReason(1)); assertEquals(Fate.Swiped, Fate.fromReason(2))
        assertEquals(Fate.ClearedAll, Fate.fromReason(3)); assertEquals(Fate.Withdrawn, Fate.fromReason(8))
        assertEquals(Fate.Other, Fate.fromReason(18)); assertEquals(Fate.Waiting, Fate.of(99))
    }

    @Test fun verdictNeedsEnoughDecisions() {
        assertEquals(Verdict.Unknown, AppSignal("a", "A", 50, 1, 2, 3).verdict)
        assertEquals(Verdict.Noise, AppSignal("a", "A", 200, 1, 40, 41).verdict)
        assertEquals(Verdict.Worth, AppSignal("a", "A", 20, 8, 2, 10).verdict)
        assertEquals(Verdict.Mixed, AppSignal("a", "A", 20, 2, 8, 10).verdict)
        assertNull(AppSignal("a", "A", 3, 1, 1, 2).tapRate)
    }

    @Test fun advertsAreSpottedButChatsNeverAre() {
        assertTrue(NotifyMath.isPromo("Flash sale!", "Up to 50% off everything, today only"))
        assertTrue(NotifyMath.isPromo("Last chance", "Don't miss our exclusive deal"))
        assertTrue(NotifyMath.isPromo("Hi", "anything", category = "promo"))
        assertFalse(NotifyMath.isPromo("Mom", "Dinner at 7? 50% chance I'm late", category = "msg"))
        assertFalse(NotifyMath.isPromo("Your package", "Out for delivery"))
        assertFalse(NotifyMath.isPromo("Battery", "Charged to 80%"))
    }

    @Test fun signInCodesAreSpotted() {
        assertTrue(NotifyMath.hasCode("Google", "G-482913 is your Google verification code"))
        assertTrue(NotifyMath.hasCode("Bank", "Your one-time passcode is 093 117"))
        assertTrue(NotifyMath.hasCode("", "Use 5521 to sign in"))
        assertFalse(NotifyMath.hasCode("Uber", "Your driver is 3 min away"))
        assertFalse(NotifyMath.hasCode("Code review", "Alex commented on your pull request"))
    }

    @Test fun burstsAreRunsWithoutLongGaps() {
        val chat = (0 until 6).map { rec("com.whatsapp", d0 + it * 30_000L) } + listOf(rec("com.whatsapp", d0 + 3 * h))
        val storms = NotifyMath.storms(chat + rec("com.other", d0))
        assertEquals(1, storms.size); assertEquals(6, storms[0].count); assertEquals(150_000L, storms[0].end - storms[0].start)
        assertTrue(NotifyMath.storms(chat.take(4)).isEmpty())
    }

    @Test fun repeatsCountIdenticalWords() {
        val r = List(4) { rec("com.duolingo", d0 + it * day, "Hi it's Duo", "These reminders don't seem to be working") } + rec("com.duolingo", d0, "Hi it's Duo", "Different")
        val reps = NotifyMath.repeats(r)
        assertEquals(1, reps.size); assertEquals(4, reps[0].count)
    }

    @Test fun reportPullsItAllTogether() {
        val now = d0 + 6 * day + 12 * h
        val recs = buildList {
            // A noisy shop: 30 adverts, opened once, swiped the rest.
            repeat(30) { i -> val at = d0 + (i / 10) * day + (10 + i % 10) * h; add(rec("com.shop", at, "Flash sale", "50% off today", fate = if (i == 0) Fate.Tapped else Fate.Swiped, gone = at + 60_000)) }
            // Mom: messages you open within a minute.
            repeat(6) { i -> add(rec("com.whatsapp", d0 + i * day + 9 * h, "Mom", "Call me", who = "Mom", cat = "msg", fate = Fate.Tapped, gone = d0 + i * day + 9 * h + 40_000)) }
            // Two at 3am, one code, one pulled back.
            add(rec("com.slack", d0 + 2 * day + 3 * h, "Ops", "Server down")); add(rec("com.slack", d0 + 3 * day + 3 * h, "Ops", "Server up"))
            add(rec("com.google.android.gms", d0 + day + 15 * h, "Google", "123456 is your verification code"))
            add(rec("com.whatsapp", d0 + 4 * day, "Sam", "oops", who = "Sam", back = true))
        }
        val r = NotifyMath.report(recs, now, utc)
        assertEquals(40, r.total); assertEquals(7, r.days)
        assertEquals(Verdict.Noise, r.apps.first { it.pkg == "com.shop" }.verdict)
        assertEquals(Verdict.Worth, r.apps.first { it.pkg == "com.whatsapp" }.verdict)
        val mom = r.people.first { it.name == "Mom" }
        assertEquals(6, mom.count); assertEquals(40_000L, mom.medianOpenMs)
        assertEquals(30, r.promoTotal); assertEquals(1, r.codes); assertEquals(1, r.pulledBack)
        assertEquals(3, r.night); assertEquals("Slack" to 2, r.nightApps.first()) // two Slack alerts at 3am, and Sam at midnight
        assertEquals(40, r.hours.sum())
        assertEquals(40, r.heat.sumOf { it.sum() })
        assertTrue(r.sentences.toString(), r.sentences.any { it.startsWith("Shop sent 30 and you opened 1") })
        assertTrue(r.sentences.any { it.contains("You open Mom fastest: usually within 40 seconds") })
        assertTrue(r.sentences.any { it.startsWith("30 were adverts") })
        assertTrue(r.sentences.any { it.contains("sign-in codes") })
    }

    @Test fun emptyHistorySaysNothing() {
        val r = NotifyMath.report(emptyList(), d0, utc)
        assertEquals(0, r.total); assertTrue(r.sentences.isEmpty()); assertEquals(1, r.days)
    }
}

/** The history database keeps what you did with each notification, including in histories made before this existed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationLogInsightsTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun oldHistoryIsUpgradedAndKept() {
        // A version 1 history, as earlier versions of the app wrote it.
        val file = context.getDatabasePath("notifications.db").also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE n (id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT, app TEXT, at INTEGER, title TEXT, text TEXT, convo TEXT, nkey TEXT, fp INTEGER, removed INTEGER DEFAULT 0)")
            db.execSQL("INSERT INTO n (pkg, app, at, title, text, nkey, fp) VALUES ('com.old', 'Old', 1000, 'Hello', 'from before', 'k0', 1)")
            db.version = 1
        }
        val log = NotificationLog(context)
        val all = log.since(0)
        assertEquals(1, all.size); assertEquals("from before", all[0].text); assertEquals(Fate.Waiting, all[0].fate)
        log.close()
    }

    @Test fun fatesAndSendersAreRecorded() {
        context.getDatabasePath("notifications.db").delete()
        val log = NotificationLog(context)
        log.add("com.whatsapp", "WhatsApp", 10_000, "Mom", "hi", null, "key1", "msg", "Mom")
        log.add("com.whatsapp", "WhatsApp", 11_000, "Mom", "you there?", null, "key1", "msg", "Mom")
        log.add("com.shop", "Shop", 12_000, "Sale", "50% off", null, "key2", "promo", null)
        log.markFate("key1", Fate.Tapped, 15_000)
        log.markFate("key2", Fate.Swiped, 13_000)
        log.markFate("key1", Fate.Swiped, 99_000) // already decided rows keep their first fate
        val all = log.since(0)
        assertEquals(listOf(Fate.Tapped, Fate.Tapped, Fate.Swiped), all.map { it.fate })
        assertEquals(listOf(15_000L, 15_000L, 13_000L), all.map { it.gone })
        assertEquals("Mom", all[0].who); assertEquals("promo", all[2].category)
        log.add("com.x", "X", 20_000, "Unsent", "oops", null, "key3")
        log.markFate("key3", Fate.Withdrawn, 21_000)
        assertTrue(log.since(0).last().pulledBack)
        log.close()
    }
}
