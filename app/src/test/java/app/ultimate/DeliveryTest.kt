package app.ultimate

import app.ultimate.social.Delivery
import app.ultimate.social.Target
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryTest {
    private val hook = "https://discord.com/api/webhooks/1234567890123456789/abcDEF_ghi-JKLmnop1234567890abcDEF_ghi-JKLmnop12345"

    @Test fun onlyRealLookingLinksAreAccepted() {
        assertTrue(Delivery.discordOk(hook)); assertTrue(Delivery.discordOk(" $hook "))
        assertTrue(Delivery.discordOk(hook.replace("discord.com", "discordapp.com")))
        assertFalse(Delivery.discordOk(hook.replace("https", "http"))); assertFalse(Delivery.discordOk("https://evil.example/api/webhooks/1234567890123456789/abc"))
        assertFalse(Delivery.discordOk(hook + "/slack")); assertFalse(Delivery.discordOk("https://discord.com.evil.example/api/webhooks/1234567890123456789/abcDEF_ghi-JKLmnop1234567890"))
        assertTrue(Delivery.ntfyOk("https://ntfy.sh/ultimate-walks-4f9c")); assertFalse(Delivery.ntfyOk("https://ntfy.sh/")); assertFalse(Delivery.ntfyOk("ntfy.sh/topic123"))
        assertTrue(Delivery.telegramOk("123456789:AAEhBP0av28hQRtSuvwxyz_abc-def1234567", "-1001234567890")); assertTrue(Delivery.telegramOk("123456789:AAEhBP0av28hQRtSuvwxyz_abc-def1234567", "@walkers"))
        assertFalse(Delivery.telegramOk("nope", "-100123"))
        assertNull(Delivery.problem(Target.Discord(hook))); assertNotNull(Delivery.problem(Target.Discord("hello")))
    }

    @Test fun messagesCannotPingOrLinkOrFormat() {
        val m = Delivery.text("@everyone <@123> **Mo** https://evil.example/x `x` www.bad.com", "walking")
        assertFalse(m.contains("@")); assertFalse(m.contains("<")); assertFalse(m.contains("*")); assertFalse(m.contains("http")); assertFalse(m.contains("www"))
        assertTrue(m.startsWith("🚶 ") && m.endsWith("is going for a walk. Join them!"))
        assertEquals("🏃 Mo is going for a run. Join them!", Delivery.text("Mo\u0007", "running"))
        assertEquals("🚶 A friend is going for a walk. Join them!", Delivery.text("@@@", "walking"))
        assertEquals(24 + "🚶  is going for a walk. Join them!".length, Delivery.text("x".repeat(200), "walking").length)
    }

    @Test fun summariesSayOnlyWhatWasAgreed() {
        assertEquals("🏁 Mo finished a walk: 2.3 km in 31 min", Delivery.summary("Mo", "walking", 31, 2.34))
        assertEquals("🏁 Mo finished a run: 31 min", Delivery.summary("Mo", "running", 31, null))
        assertEquals("🏁 Mo finished a walk", Delivery.summary("Mo", "walking", null, null))
    }

    @Test fun discordRequestNeverAllowsPings() {
        val r = Delivery.build(Target.Discord(hook), "hi @everyone")
        val j = JSONObject(r.body)
        assertEquals(0, j.getJSONObject("allowed_mentions").getJSONArray("parse").length())
        assertEquals("Ultimate", j.getString("username")); assertEquals(hook, r.url); assertEquals("application/json", r.contentType)
        assertEquals(1_900, JSONObject(Delivery.build(Target.Discord(hook), "x".repeat(5_000)).body).getString("content").length)
    }

    @Test fun ntfyAndTelegramRequests() {
        val n = Delivery.build(Target.Ntfy("https://ntfy.sh/ultimate-walks-4f9c"), "🚶 Mo is going for a walk. Join them!")
        assertEquals("text/plain; charset=utf-8", n.contentType); assertEquals("Ultimate", n.headers["Title"]); assertTrue(n.body.startsWith("🚶"))
        val t = Delivery.build(Target.Telegram(" 123456789:AAEhBP0av28hQRtSuvwxyz_abc-def1234567 ", "-100123456"), "hello")
        assertEquals("https://api.telegram.org/bot123456789:AAEhBP0av28hQRtSuvwxyz_abc-def1234567/sendMessage", t.url)
        assertEquals("-100123456", JSONObject(t.body).getString("chat_id")); assertTrue(JSONObject(t.body).getBoolean("disable_web_page_preview"))
    }

    @Test fun rateLimitAnswersAreRespected() {
        assertEquals(1_500L, Delivery.retryAfterMs(429, """{"message":"You are being rate limited.","retry_after":1.5}""", null))
        assertEquals(7_000L, Delivery.retryAfterMs(429, """{"ok":false,"parameters":{"retry_after":7}}""", null))
        assertEquals(5_000L, Delivery.retryAfterMs(429, "garbage", null)); assertEquals(30_000L, Delivery.retryAfterMs(429, """{"retry_after":9999}""", null))
        assertNull(Delivery.retryAfterMs(200, "", null)); assertNull(Delivery.retryAfterMs(500, "", null))
    }
}
