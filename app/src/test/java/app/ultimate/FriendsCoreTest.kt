package app.ultimate

import app.ultimate.social.Announce
import app.ultimate.social.AnnounceSettings
import app.ultimate.social.FriendCode
import app.ultimate.social.Share
import app.ultimate.social.WalkAnnouncer
import app.ultimate.social.WalkBeat
import app.ultimate.social.WalkPrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FriendsCoreTest {
    // ---- codes ----
    @Test fun codesRoundTripAndTolerateTyping() {
        val rnd = Random(5)
        repeat(200) {
            val c = FriendCode.generate(rnd)
            assertEquals(11, c.length) // nine characters shown as 4-4-1, two dashes
            val raw = FriendCode.parse(c)!!
            assertEquals(raw, FriendCode.parse(c.lowercase().replace("-", " ")))
        }
    }

    @Test fun aMistypedCodeIsRefused() {
        val rnd = Random(9); var caught = 0; var total = 0
        repeat(300) {
            val raw = FriendCode.parse(FriendCode.generate(rnd))!!
            val i = rnd.nextInt(8); val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
            val other = alphabet.first { it != raw[i] && it != 'O' }
            val typo = raw.substring(0, i) + other + raw.substring(i + 1)
            total++; if (FriendCode.parse(typo) == null) caught++
        }
        assertEquals("a single wrong character is always caught", total, caught)
        assertNull(FriendCode.parse("")); assertNull(FriendCode.parse("ABCD-EFGH")); assertNull(FriendCode.parse("UUUU-UUUU-U"))
    }

    @Test fun lookAlikesAreForgiven() {
        val c = FriendCode.parse("0000-0000-0")!!
        assertEquals(c, FriendCode.parse("oooo-oooo-o")); assertEquals("1", FriendCode.normalize("l").first().toString())
    }

    // ---- privacy ----
    private val beat = WalkBeat(1L, "walking", steps = 1234, distanceM = 1234.0, paceSecPerKm = 641.0, heartBpm = 118, lat = 51.500712, lon = -0.124601)

    @Test fun nothingLeavesUnlessChosen() {
        val none = WalkPrivacy.redact(beat, Share())
        assertNull(none.steps); assertNull(none.distanceM); assertNull(none.paceSecPerKm); assertNull(none.heartBpm); assertNull(none.lat); assertNull(none.lon)
        assertEquals("walking", none.state)
    }

    @Test fun whatIsChosenIsBlurredToo() {
        val all = WalkPrivacy.redact(beat, Share(steps = true, distance = true, pace = true, heart = true, where = true))
        assertEquals(1230.0, all.distanceM!!, 0.0); assertEquals(640.0, all.paceSecPerKm!!, 0.0); assertEquals(118, all.heartBpm)
        assertEquals(51.501, all.lat!!, 1e-9); assertEquals(-0.125, all.lon!!, 1e-9) // about 100 m, not the doorstep
        assertNull(WalkPrivacy.redact(beat.copy(heartBpm = 400), Share(heart = true)).heartBpm)
    }

    @Test fun namesCannotCarryLinksOrJunk() {
        assertEquals("Maurice is going for a walk. Join them?", WalkPrivacy.announcement("Maurice", "walking"))
        assertEquals("Mo is going for a run. Join them?", WalkPrivacy.announcement("Mo\u0007", "running"))
        assertEquals("A friend is going for a walk. Join them?", WalkPrivacy.announcement("https://evil.example/x", "walking"))
        assertEquals(24, WalkPrivacy.cleanName("x".repeat(100)).length)
    }

    // ---- when to announce ----
    private val on = AnnounceSettings(enabled = true, notify = setOf("sam", "kim"), askFirst = false, steadyMinutes = 4)
    private val noon = 12 * 60
    private fun walk(a: WalkAnnouncer, fromMs: Long, minutes: Int, minute: Int = noon): Announce? {
        var out: Announce? = null
        var t = fromMs
        while (t <= fromMs + minutes * 60_000L) { a.hear(if (t == fromMs) "walking" else "walking", t, minute)?.let { out = it }; t += 30_000L }
        return out
    }

    @Test fun offUntilSwitchedOnAndNeverBeforeTheSteadyTime() {
        assertNull(walk(WalkAnnouncer(), 0, 10))
        val a = WalkAnnouncer(on)
        assertNull(walk(a, 0, 3))
        assertEquals(Announce.Now(setOf("sam", "kim"), "walking"), a.hear("walking", 4 * 60_000L + 30_000L, noon).let { walk(WalkAnnouncer(on), 0, 5) })
    }

    @Test fun oneAnswerPerWalkAndAKitchenTripTellsNobody() {
        val a = WalkAnnouncer(on)
        assertNotNull(walk(a, 0, 5)); assertNull(walk(a, 5 * 60_000L + 30_000L, 5)) // same walk, already decided
        val b = WalkAnnouncer(on)
        b.hear("walking", 0, noon); b.hear("still", 60_000L, noon)
        assertNull(walk(b, 120_000L, 3)) // a new bout that stops before four minutes
    }

    @Test fun aLongSilenceEndsTheWalkAndTheNextOneIsNew() {
        val a = WalkAnnouncer(on.copy(cooldownMinutes = 0))
        assertNotNull(walk(a, 0, 5))
        assertNotNull(walk(a, 60 * 60_000L, 5)) // an hour later: a new walk
    }

    @Test fun onlyChosenFriendsAreToldAndNotTwiceInTheCooldown() {
        val a = WalkAnnouncer(on.copy(notify = setOf("sam")))
        val first = walk(a, 0, 5) as Announce.Now
        assertEquals(setOf("sam"), first.friends)
        a.told("sam", 5 * 60_000L)
        assertNull(walk(a, 60 * 60_000L, 5)) // an hour later, within three hours
        assertNotNull(walk(a, 4 * 60 * 60_000L, 5)) // four hours later
    }

    @Test fun quietHoursPrivateModeAndAskFirst() {
        assertNull(walk(WalkAnnouncer(on), 0, 5, minute = 23 * 60)); assertNull(walk(WalkAnnouncer(on), 0, 5, minute = 3 * 60))
        assertNotNull(walk(WalkAnnouncer(on), 0, 5, minute = 8 * 60))
        assertNull(walk(WalkAnnouncer(on.copy(privateUntil = 10 * 60_000L)), 0, 5))
        assertTrue(walk(WalkAnnouncer(on.copy(askFirst = true)), 0, 5) is Announce.Ask)
        assertFalse(WalkAnnouncer.inQuiet(500, AnnounceSettings(quietFromMin = 0, quietToMin = 0)))
    }

    @Test fun runningIsAnnouncedAsARun() {
        val a = WalkAnnouncer(on); var out: Announce? = null
        var t = 0L; while (t <= 5 * 60_000L) { a.hear("running", t, noon)?.let { out = it }; t += 30_000L }
        assertEquals(Announce.Now(setOf("sam", "kim"), "running"), out)
    }
}
