package app.ultimate

import app.ultimate.social.ActivityLog
import app.ultimate.social.AnnounceSettings
import app.ultimate.social.Circle
import app.ultimate.social.FriendsApi
import app.ultimate.social.FriendsBridge
import app.ultimate.social.FriendsPort
import app.ultimate.social.LogEntry
import app.ultimate.social.SendResult
import app.ultimate.social.Target
import app.ultimate.social.Templates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class DashboardCoreTest {
    private val hook = "https://discord.com/api/webhooks/123456789012345678/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789SECRETTAIL"
    private val tgToken = "123456789:ABCdefGHIjklMNOpqrSTUvwxYZ_0123456789ab"
    private val ntfy = "https://ntfy.sh/ultimate-walks-private-topic"

    class Fake : FriendsPort {
        override fun device() = "Pixel Test"
        override var name = "Maurice"
        override var settings = AnnounceSettings()
        override var summary = true
        override var templateStart = ""
        override var templateFinish = ""
        val chats = mutableListOf<Circle>()
        val sent = mutableListOf<Triple<String, String, String>>()
        var fail: String? = null
        var logged = emptyList<LogEntry>()
        override fun circles() = chats.toList()
        override fun told() = emptyMap<String, Long>()
        override fun add(t: Target) = Circle("c${chats.size + 1}", t).also { chats += it }
        override fun remove(id: String) { chats.removeAll { it.id == id }; settings = settings.copy(notify = settings.notify - id) }
        override fun send(circle: Circle, message: String, what: String): SendResult { sent += Triple(circle.id, message, what); return SendResult(fail == null, fail) }
        override fun log() = logged
    }

    private var now = 1_000_000L
    private fun api(f: Fake) = FriendsApi(f) { now }
    private fun FriendsApi.get(p: String) = handle("GET", p, "")
    private fun FriendsApi.post(p: String, body: String = "{}") = handle("POST", p, body)
    private fun body(r: FriendsApi.Reply) = JSONObject(r.body)

    // --- templates ---

    @Test fun `the usual wording is unchanged`() {
        assertEquals("🚶 Mo is going for a walk. Join them!", Templates.render(Templates.START, "Mo", "walking"))
        assertEquals("🏃 Mo is going for a run. Join them!", Templates.render("", "Mo", "running"))
        assertEquals("🏁 Mo finished a run: 2.3 km in 31 min", Templates.render(Templates.FINISH, "Mo", "run", 31, 2.34))
    }

    @Test fun `own wording is filled in and names stay safe`() {
        assertEquals("Mo is out for a walk, come along 🚶", Templates.render("{name} is out for a {kind}, come along {emoji}", "Mo", "walking"))
        val t = Templates.render("{name}: join at https://discord.gg/abc", "@everyone <@1> **x** https://evil.example", "walking")
        assertFalse("@" in t.substringBefore(":")); assertFalse("evil" in t); assertTrue(t.endsWith("join at https://discord.gg/abc"))
        assertEquals("Mo took 31 min", Templates.render("{name} took {minutes} min", "Mo", "walking", 31, null))
        assertEquals("Mo walked", Templates.render("{name} walked {km}", "Mo", "walking", null, null)) // an empty piece leaves no gap
    }

    @Test fun `bad wording is refused with a reason`() {
        assertNull(Templates.problem("")); assertNull(Templates.problem("{name} is out"))
        assertTrue(Templates.problem("{nam} is out")!!.contains("{nam}"))
        assertNotNull(Templates.problem("a { b")); assertNotNull(Templates.problem("a } b"))
        assertNotNull(Templates.problem("x".repeat(Templates.MAX + 1))); assertNotNull(Templates.problem("bell\u0007"))
        assertNull(Templates.problem("line one\nline two"))
    }

    // --- log ---

    @Test fun `the log keeps the newest and never a secret`() {
        var l = emptyList<LogEntry>()
        repeat(ActivityLog.MAX + 10) { l = ActivityLog.add(l, LogEntry(it.toLong(), "Chat", "walk", true)) }
        assertEquals(ActivityLog.MAX, l.size); assertEquals((ActivityLog.MAX + 9).toLong(), l.first().at)
        val e = ActivityLog.clean(LogEntry(1, "Chat", "weird", false, "failed at https://api.telegram.org/bot$tgToken/sendMessage with bot$tgToken"))
        assertFalse(tgToken in e.note); assertFalse("telegram" in e.note); assertEquals("manual", e.what)
    }

    @Test fun `the log survives saving and drops damage`() {
        val l = listOf(LogEntry(5, "Discord", "walk", true), LogEntry(4, "ntfy", "test", false, "No connection"))
        assertEquals(l, ActivityLog.decode(ActivityLog.encode(l)))
        assertEquals(emptyList<LogEntry>(), ActivityLog.decode("not json")); assertEquals(emptyList<LogEntry>(), ActivityLog.decode(null))
        assertEquals(1, ActivityLog.decode("""[{"at":1,"chat":"a","what":"walk","ok":true},{"oops":1}]""").size)
    }

    // --- body reading ---

    @Test fun `a body is read exactly and bounded`() {
        assertEquals("{\"a\":1}", FriendsBridge.readBody(ByteArrayInputStream("{\"a\":1}EXTRA".toByteArray()), 7))
        assertNull(FriendsBridge.readBody(ByteArrayInputStream(ByteArray(10)), null)); assertNull(FriendsBridge.readBody(ByteArrayInputStream(ByteArray(10)), -1))
        assertNull(FriendsBridge.readBody(ByteArrayInputStream(ByteArray(40_000)), 40_000)); assertNull(FriendsBridge.readBody(ByteArrayInputStream(ByteArray(3)), 9))
    }

    // --- the interface ---

    @Test fun `state describes everything and reveals no secret`() {
        val f = Fake(); f.chats += Circle("c1", Target.Discord(hook, "Walkers")); f.chats += Circle("c2", Target.Telegram(tgToken, "-100123456", "Family")); f.chats += Circle("c3", Target.Ntfy(ntfy, "Phone"))
        val r = api(f).get("/state"); assertEquals(200, r.status)
        for (secret in listOf(hook, "SECRETTAIL", "AbCdEf", tgToken, "ABCdef", "ultimate-walks-private-topic")) assertFalse("leaked $secret", secret in r.body)
        val o = body(r)
        assertEquals("Pixel Test", o.getString("device")); assertEquals("Maurice", o.getString("name")); assertFalse(o.getBoolean("enabled"))
        assertEquals(3, o.getJSONArray("chats").length()); assertEquals("discord", o.getJSONArray("chats").getJSONObject(0).getString("kind"))
        assertTrue(o.getJSONArray("chats").getJSONObject(1).getString("hint").contains("123456789")) // the bot's number is public, its secret is not
        assertEquals(Templates.START, o.getJSONObject("defaults").getString("start")); assertEquals(6, o.getJSONArray("pieces").length())
    }

    @Test fun `the switch needs a name and a chat`() {
        val f = Fake(); f.name = ""
        assertEquals(409, api(f).post("/settings", """{"enabled":true}""").status)
        f.name = "Mo"; assertEquals(409, api(f).post("/settings", """{"enabled":true}""").status)
        f.chats += Circle("c1", Target.Ntfy(ntfy)); assertEquals(200, api(f).post("/settings", """{"enabled":true}""").status); assertTrue(f.settings.enabled)
        assertEquals(200, api(f).post("/settings", """{"enabled":false}""").status); assertFalse(f.settings.enabled)
    }

    @Test fun `rules are clamped and quiet hours and private work`() {
        val f = Fake(); val a = api(f)
        a.post("/settings", """{"steadyMinutes":9999,"cooldownMinutes":0,"askFirst":false,"summary":false}""")
        assertEquals(60, f.settings.steadyMinutes); assertEquals(5, f.settings.cooldownMinutes); assertFalse(f.settings.askFirst); assertFalse(f.summary)
        a.post("/settings", """{"quietOn":false}"""); assertEquals(f.settings.quietFromMin, f.settings.quietToMin)
        a.post("/settings", """{"quietOn":true}"""); assertEquals(22 * 60, f.settings.quietFromMin); assertEquals(7 * 60, f.settings.quietToMin)
        a.post("/settings", """{"quietFromMin":99999,"quietToMin":-5}"""); assertEquals(1439, f.settings.quietFromMin); assertEquals(0, f.settings.quietToMin)
        a.post("/settings", """{"privateHours":4}"""); assertEquals(now + 4 * 3_600_000L, f.settings.privateUntil)
        assertEquals(f.settings.privateUntil, body(a.get("/state")).getLong("privateUntil"))
        a.post("/settings", """{"privateHours":0}"""); assertEquals(0L, f.settings.privateUntil)
        assertEquals(400, a.post("/settings", """{"steadyMinutes":"lots"}""").status); assertEquals(400, a.post("/settings", "not json").status)
    }

    @Test fun `name is made safe`() {
        val f = Fake(); api(f).post("/name", """{"name":"@everyone Mo https://x.example"}""")
        assertFalse("@" in f.name); assertFalse("x.example" in f.name); assertTrue(f.name.startsWith("everyone Mo"))
    }

    @Test fun `chats are validated, added, toggled and removed`() {
        val f = Fake(); val a = api(f)
        assertEquals(400, a.post("/chats", """{"kind":"discord","url":"https://evil.example/x"}""").status)
        assertEquals(400, a.post("/chats", """{"kind":"telegram","token":"nope","chat":"1"}""").status)
        assertEquals(400, a.post("/chats", """{"kind":"myspace"}""").status)
        assertEquals(200, a.post("/chats", """{"kind":"discord","url":"$hook","label":"@Walkers <b>"}""").status)
        assertEquals(200, a.post("/chats", """{"kind":"telegram","token":"$tgToken","chat":"-100123456"}""").status)
        assertEquals(2, f.chats.size); assertFalse("@" in f.chats[0].target.label); assertEquals("Telegram", f.chats[1].target.label)
        a.post("/chats/c1/notify", """{"on":true}"""); assertEquals(setOf("c1"), f.settings.notify)
        a.post("/chats/c1/notify", """{"on":false}"""); assertEquals(emptySet<String>(), f.settings.notify)
        assertEquals(404, a.post("/chats/zzz/notify", """{"on":true}""").status)
        a.post("/chats/c1/remove"); assertEquals(1, f.chats.size)
        repeat(20) { a.post("/chats", """{"kind":"ntfy","url":"https://ntfy.sh/topic-number-$it-abc"}""") }; assertEquals(12, f.chats.size)
    }

    @Test fun `a test goes to one chat and is rate limited`() {
        val f = Fake(); f.chats += Circle("c1", Target.Ntfy(ntfy)); val a = api(f)
        val r = a.post("/chats/c1/test"); assertTrue(body(r).getBoolean("ok")); assertEquals("test", f.sent.single().third); assertTrue("Maurice" in f.sent.single().second)
        assertEquals(429, a.post("/chats/c1/test").status); now += 3_001; assertEquals(200, a.post("/chats/c1/test").status)
        f.fail = "The chat refused it."; now += 3_001; assertEquals("The chat refused it.", body(a.post("/chats/c1/test")).getString("error"))
    }

    @Test fun `announce tells the chosen chats once and uses the owner's wording`() {
        val f = Fake(); f.chats += Circle("c1", Target.Ntfy(ntfy)); f.chats += Circle("c2", Target.Ntfy("https://ntfy.sh/other-topic-xyz")); val a = api(f)
        assertEquals(409, a.post("/announce").status) // nobody is switched on yet
        f.settings = f.settings.copy(notify = setOf("c1")); f.templateStart = "{emoji} {name} is out, come on!"
        val r = body(a.post("/announce", """{"state":"run"}""")); assertEquals("🏃 Maurice is out, come on!", r.getString("text"))
        assertEquals(listOf("c1"), f.sent.map { it.first }); assertEquals("manual", f.sent.single().third)
        assertEquals(429, a.post("/announce").status); assertEquals(1, f.sent.size)
        now += 8_001; a.post("/announce", """{"chats":["c2"]}"""); assertEquals(listOf("c1", "c2"), f.sent.map { it.first })
        now += 8_001; f.name = ""; assertEquals(409, a.post("/announce").status)
    }

    @Test fun `messages preview and save with checks`() {
        val f = Fake(); val a = api(f)
        assertEquals("🚶 Maurice is going for a walk. Join them!", body(a.post("/preview", """{"template":""}""")).getString("text"))
        assertEquals("🏁 Maurice finished a run: 5.0 km in 40 min", body(a.post("/preview", """{"template":"","which":"finish","state":"run","minutes":40,"km":5.0}""")).getString("text"))
        assertTrue(body(a.post("/preview", """{"template":"{bad}"}""")).getString("error").contains("{bad}"))
        assertEquals(400, a.post("/templates", """{"start":"{bad}"}""").status); assertEquals("", f.templateStart)
        a.post("/templates", """{"start":"  {name} is out  ","finish":""}"""); assertEquals("{name} is out", f.templateStart); assertEquals("", f.templateFinish)
    }

    @Test fun `unknown paths and wrong methods are refused`() {
        val a = api(Fake()); assertEquals(404, a.get("/nothing").status); assertEquals(404, a.handle("GET", "/chats/c1/remove", "").status); assertEquals(404, a.post("/state").status)
    }

    /** Writes real replies for the page to be tried against in a browser (see scripts/dashboard-harness). */
    @Test fun `write samples for the browser harness`() {
        val f = Fake(); f.chats += Circle("c1", Target.Discord(hook, "Walkers")); f.chats += Circle("c2", Target.Telegram(tgToken, "-100123456", "Family")); f.chats += Circle("c3", Target.Ntfy(ntfy, "Mum"))
        f.settings = AnnounceSettings(enabled = true, notify = setOf("c1", "c2")); f.templateStart = "{emoji} {name} is heading out for a {kind}. Come along!"
        f.logged = listOf(LogEntry(now - 60_000, "Walkers", "walk", true), LogEntry(now - 3_600_000, "Family", "walk", false, "The chat refused it (the link may have been deleted or revoked)."), LogEntry(now - 86_400_000, "Mum", "test", true), LogEntry(now - 90_000_000, "Walkers", "finish", true))
        val a = api(f)
        val samples = JSONObject().put("state", body(a.get("/state"))).put("preview", body(a.post("/preview", """{"template":"{emoji} {name} is heading out for a {kind}. Come along!"}"""))).put("previewBad", body(a.post("/preview", """{"template":"{oops}"}""")))
            .put("announce", body(a.post("/announce", """{}"""))).put("test", body(a.post("/chats/c1/test")))
        File(System.getProperty("friends.samples") ?: "build/friends-api-samples.json").also { it.parentFile?.mkdirs() }.writeText(samples.toString(2))
    }
}
