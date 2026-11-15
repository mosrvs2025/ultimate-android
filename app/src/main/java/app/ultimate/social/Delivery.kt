package app.ultimate.social

import org.json.JSONObject

/**
 * Where a walk announcement goes: a chat the owner's friends are already in. Nothing here talks to the network; it only builds the
 * request, so it is checked exactly (and a bad link is refused before anything is sent).
 *
 *  - Discord: a webhook link. It can only post to the one channel it was made for, so it reveals nothing and cannot read.
 *  - ntfy: a topic on ntfy.sh (or a server of the owner's); friends subscribe with the ntfy app.
 *  - Telegram: a bot token and a chat id. The token is stronger than a webhook (the bot can do more than post), so it is the
 *    owner's own bot and is kept sealed on the phone.
 */
sealed interface Target {
    val label: String

    data class Discord(val url: String, override val label: String = "Discord") : Target
    data class Ntfy(val url: String, override val label: String = "ntfy") : Target
    data class Telegram(val token: String, val chatId: String, override val label: String = "Telegram") : Target
}

/** One HTTPS request, ready to send. */
class Request(val url: String, val contentType: String, val headers: Map<String, String>, val body: String)

object Delivery {
    private val DISCORD = Regex("""^https://(canary\.|ptb\.)?(discord|discordapp)\.com/api/(v\d+/)?webhooks/\d{15,22}/[A-Za-z0-9_\-]{20,150}$""")
    private val NTFY = Regex("""^https://[A-Za-z0-9.\-]+(:\d+)?/[A-Za-z0-9_\-]{6,64}$""")
    private val TELEGRAM_TOKEN = Regex("""^\d{5,12}:[A-Za-z0-9_\-]{30,60}$""")
    private val TELEGRAM_CHAT = Regex("""^(-?\d{4,20}|@[A-Za-z][A-Za-z0-9_]{4,31})$""")

    fun discordOk(url: String) = DISCORD.matches(url.trim())
    fun ntfyOk(url: String) = NTFY.matches(url.trim()) && !url.contains("/api/") && !url.contains("webhooks")
    fun telegramOk(token: String, chat: String) = TELEGRAM_TOKEN.matches(token.trim()) && TELEGRAM_CHAT.matches(chat.trim())

    /** Why [t] cannot be used, in words for the screen, or null when it is fine. */
    fun problem(t: Target): String? = when (t) {
        is Target.Discord -> if (discordOk(t.url)) null else "That doesn't look like a Discord webhook link (it starts https://discord.com/api/webhooks/…)."
        is Target.Ntfy -> if (ntfyOk(t.url)) null else "That doesn't look like an ntfy topic link (like https://ntfy.sh/your-secret-topic)."
        is Target.Telegram -> if (telegramOk(t.token, t.chatId)) null else "The bot token or chat id doesn't look right."
    }

    /**
     * Text that is safe to put in a message: no pings (@everyone, @here, <@123>), no links, no formatting tricks, no control
     * characters, and short. People's chosen names pass through this before they are put in anything others read.
     */
    fun safe(text: String, max: Int = 40): String = text
        .replace(Regex("""(?i)(https?://|www\.)\S+"""), "")
        .filter { it >= ' ' && it != '\u007f' && it !in "@<>`*_~|\\[]()#:" }
        .replace(Regex("\\s+"), " ").trim().take(max)

    /** "🚶 Maurice is going for a walk. Join them!", or the owner's own wording for it. */
    fun text(name: String, state: String, template: String = Templates.START): String = Templates.render(template, name, state)

    /** "🏁 Maurice finished a walk: 2.3 km in 31 min", only with what the owner agreed to share. */
    fun summary(name: String, state: String, minutes: Int?, km: Double?, template: String = Templates.FINISH): String = Templates.render(template, name, state, minutes, km)

    fun build(t: Target, message: String): Request = when (t) {
        is Target.Discord -> Request(t.url.trim(), "application/json", emptyMap(),
            JSONObject().put("username", "Ultimate").put("content", message.take(1_900)).put("allowed_mentions", JSONObject().put("parse", org.json.JSONArray())).toString())
        is Target.Ntfy -> Request(t.url.trim(), "text/plain; charset=utf-8", mapOf("Title" to "Ultimate", "Tags" to "walking", "Priority" to "default"), message.take(1_900))
        is Target.Telegram -> Request("https://api.telegram.org/bot${t.token.trim()}/sendMessage", "application/json", emptyMap(),
            JSONObject().put("chat_id", t.chatId.trim()).put("text", message.take(3_900)).put("disable_web_page_preview", true).toString())
    }

    /** How long to wait before trying again after a rate-limit answer (HTTP 429) with [body], or null if it was not one. */
    fun retryAfterMs(code: Int, body: String, header: String?): Long? {
        if (code != 429) return null
        val fromBody = runCatching { JSONObject(body).let { if (it.has("retry_after")) it.getDouble("retry_after") else it.optJSONObject("parameters")?.optDouble("retry_after") } }.getOrNull()
        val s = fromBody?.takeIf { it.isFinite() } ?: header?.toDoubleOrNull() ?: 5.0
        return (s * 1000).toLong().coerceIn(500L, 30_000L)
    }
}
