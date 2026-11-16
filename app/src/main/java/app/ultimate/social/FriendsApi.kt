package app.ultimate.social

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/** A chat the owner announces walks to. [target] holds the secret link or token; it is only ever stored sealed. */
data class Circle(val id: String, val target: Target)

/** How one send to one chat went: [error] is words for the screen when it didn't. */
class SendResult(val ok: Boolean, val error: String?)

/** What the dashboard can read and change on the phone. The phone implements it; tests use a fake. */
interface FriendsPort {
    fun device(): String
    var name: String
    var settings: AnnounceSettings
    var summary: Boolean
    var templateStart: String
    var templateFinish: String
    fun circles(): List<Circle>
    fun told(): Map<String, Long>
    fun add(t: Target): Circle
    fun remove(id: String)
    /** Sends [message] to one chat and records it in the activity log as [what]. Blocking. */
    fun send(circle: Circle, message: String, what: String): SendResult
    fun log(): List<LogEntry>
}

/** Where the phone's web server finds the dashboard's brain. Registered by Ultimate+ (null in the other editions). */
object FriendsBridge {
    @Volatile var port: FriendsPort? = null
    private var cached: Pair<FriendsPort, FriendsApi>? = null

    @Synchronized fun api(): FriendsApi? {
        val p = port ?: return null
        cached?.let { if (it.first === p) return it.second }
        return FriendsApi(p).also { cached = p to it }
    }

    /** Reads exactly [length] bytes of a request body as text, or null if it is missing, negative or longer than [cap]. */
    fun readBody(input: InputStream, length: Long?, cap: Int = 16 * 1024): String? {
        if (length == null || length < 0 || length > cap) return null
        val buf = ByteArray(length.toInt()); var got = 0
        while (got < buf.size) { val n = input.read(buf, got, buf.size - got); if (n < 0) return null; got += n }
        return String(buf, Charsets.UTF_8)
    }
}

/**
 * The dashboard's JSON interface, as plain functions of (method, path, body) so it is tested without a phone or a network. It
 * never returns a secret: a chat is described by what it is and the last few characters of its link, never the link or token.
 * Anything that sends to real chats is rate limited, so a page left open or a double click can't spam friends.
 */
class FriendsApi(private val port: FriendsPort, private val clock: () -> Long = System::currentTimeMillis) {
    class Reply(val status: Int, val body: String)

    private val lastSend = HashMap<String, Long>()
    private var lastAnnounce = 0L

    fun handle(method: String, path: String, body: String): Reply = try { route(method, path.trimEnd('/'), body) }
    catch (e: BadRequest) { fail(400, e.message ?: "That didn't look right.") }
    catch (e: org.json.JSONException) { fail(400, "That didn't look right.") }
    catch (e: Exception) { fail(500, "Something went wrong on the phone.") }

    private class BadRequest(message: String) : Exception(message)

    private fun route(method: String, path: String, body: String): Reply {
        val post = method == "POST"
        val parts = path.removePrefix("/").split('/')
        return when {
            method == "GET" && path == "/state" -> ok(state())
            post && path == "/name" -> { port.name = Delivery.safe(json(body).optString("name"), 24); ok(state()) }
            post && path == "/settings" -> settings(json(body))
            post && path == "/templates" -> templates(json(body))
            post && path == "/preview" -> preview(json(body))
            post && path == "/chats" -> addChat(json(body))
            post && path == "/announce" -> announce(json(body))
            post && parts.size == 3 && parts[0] == "chats" -> chatAction(parts[1], parts[2], json(body))
            else -> fail(404, "No such thing.")
        }
    }

    // --- reading ---

    private fun state(): JSONObject {
        val s = port.settings; val told = port.told(); val now = clock()
        return JSONObject().apply {
            put("device", port.device()); put("now", now)
            put("name", port.name)
            put("enabled", s.enabled); put("askFirst", s.askFirst); put("steadyMinutes", s.steadyMinutes); put("cooldownMinutes", s.cooldownMinutes)
            put("quietOn", s.quietFromMin != s.quietToMin); put("quietFromMin", s.quietFromMin); put("quietToMin", s.quietToMin)
            put("privateUntil", if (s.privateUntil > now) s.privateUntil else 0L)
            put("summary", port.summary)
            put("templates", JSONObject().put("start", port.templateStart).put("finish", port.templateFinish))
            put("defaults", JSONObject().put("start", Templates.START).put("finish", Templates.FINISH))
            put("pieces", JSONArray().apply { Templates.PIECES.forEach { (k, d) -> put(JSONObject().put("key", k).put("about", d)) } })
            put("chats", JSONArray().apply { port.circles().forEach { c -> put(chat(c, c.id in s.notify, told[c.id] ?: 0L)) } })
            put("log", JSONArray().apply { port.log().forEach { put(JSONObject().put("at", it.at).put("chat", it.chat).put("what", it.what).put("ok", it.ok).put("note", it.note)) } })
        }
    }

    private fun chat(c: Circle, notify: Boolean, toldAt: Long) = JSONObject().put("id", c.id).put("kind", kind(c.target)).put("label", c.target.label)
        .put("hint", hint(c.target)).put("notify", notify).put("toldAt", toldAt)

    private fun kind(t: Target) = when (t) { is Target.Discord -> "discord"; is Target.Telegram -> "telegram"; is Target.Ntfy -> "ntfy" }

    /** A reminder of which link this is, from which the link itself can't be rebuilt. */
    private fun hint(t: Target): String = when (t) {
        is Target.Discord -> "Webhook ending …" + t.url.trim().takeLast(4)
        is Target.Ntfy -> "${t.url.trim().removePrefix("https://").substringBefore('/')} · topic ending …" + t.url.trim().takeLast(3)
        is Target.Telegram -> "Bot ${t.token.trim().substringBefore(':')} · chat ${t.chatId.trim()}"
    }

    // --- changing ---

    private fun settings(o: JSONObject): Reply {
        var s = port.settings
        if (o.has("enabled")) {
            val on = o.getBoolean("enabled")
            if (on && port.name.isBlank()) return fail(409, "Add your name first.")
            if (on && port.circles().isEmpty()) return fail(409, "Add a chat first.")
            s = s.copy(enabled = on)
        }
        if (o.has("askFirst")) s = s.copy(askFirst = o.getBoolean("askFirst"))
        if (o.has("steadyMinutes")) s = s.copy(steadyMinutes = o.getInt("steadyMinutes").coerceIn(1, 60))
        if (o.has("cooldownMinutes")) s = s.copy(cooldownMinutes = o.getInt("cooldownMinutes").coerceIn(5, 1_440))
        if (o.has("quietOn")) s = if (o.getBoolean("quietOn")) s.copy(quietFromMin = if (s.quietFromMin == s.quietToMin) 22 * 60 else s.quietFromMin, quietToMin = if (s.quietFromMin == s.quietToMin) 7 * 60 else s.quietToMin)
            else s.copy(quietFromMin = 0, quietToMin = 0)
        if (o.has("quietFromMin")) s = s.copy(quietFromMin = o.getInt("quietFromMin").coerceIn(0, 1_439))
        if (o.has("quietToMin")) s = s.copy(quietToMin = o.getInt("quietToMin").coerceIn(0, 1_439))
        if (o.has("privateHours")) { val h = o.getInt("privateHours").coerceIn(0, 72); s = s.copy(privateUntil = if (h == 0) 0L else clock() + h * 3_600_000L) }
        port.settings = s
        if (o.has("summary")) port.summary = o.getBoolean("summary")
        return ok(state())
    }

    private fun templates(o: JSONObject): Reply {
        for (k in listOf("start", "finish")) if (o.has(k)) Templates.problem(o.getString(k))?.let { return fail(400, it) }
        if (o.has("start")) port.templateStart = o.getString("start").trim()
        if (o.has("finish")) port.templateFinish = o.getString("finish").trim()
        return ok(state())
    }

    private fun preview(o: JSONObject): Reply {
        val template = o.optString("template")
        Templates.problem(template)?.let { return ok(JSONObject().put("text", "").put("error", it)) }
        val finish = o.optString("which") == "finish"
        val state = if (o.optString("state") == "run") "running" else "walking"
        val text = if (finish) Templates.render(template.ifBlank { Templates.FINISH }, port.name, state, o.optInt("minutes", 31), o.optDouble("km", 2.3))
        else Templates.render(template.ifBlank { Templates.START }, port.name, state)
        return ok(JSONObject().put("text", text).put("error", JSONObject.NULL))
    }

    private fun addChat(o: JSONObject): Reply {
        if (port.circles().size >= MAX_CHATS) return fail(409, "That's plenty of chats ($MAX_CHATS). Remove one first.")
        val label = Delivery.safe(o.optString("label"), 20)
        val t = when (o.optString("kind")) {
            "discord" -> Target.Discord(o.optString("url").trim(), label.ifBlank { "Discord" })
            "ntfy" -> Target.Ntfy(o.optString("url").trim(), label.ifBlank { "ntfy" })
            "telegram" -> Target.Telegram(o.optString("token").trim(), o.optString("chat").trim(), label.ifBlank { "Telegram" })
            else -> return fail(400, "Pick Discord, Telegram or ntfy.")
        }
        Delivery.problem(t)?.let { return fail(400, it) }
        port.add(t)
        return ok(state())
    }

    private fun chatAction(id: String, action: String, o: JSONObject): Reply {
        val c = port.circles().firstOrNull { it.id == id } ?: return fail(404, "That chat is gone.")
        return when (action) {
            "notify" -> { val on = o.getBoolean("on"); port.settings = port.settings.let { it.copy(notify = if (on) it.notify + id else it.notify - id) }; ok(state()) }
            "remove" -> { port.remove(id); ok(state()) }
            "test" -> {
                limit("test:$id", 3_000L)?.let { return it }
                val who = port.name.takeIf { it.isNotBlank() }?.let { " for ${Delivery.safe(it, 24)}" } ?: ""
                val r = port.send(c, "✅ Ultimate is connected$who. Walk announcements will appear here.", "test")
                ok(JSONObject().put("ok", r.ok).put("error", r.error ?: JSONObject.NULL).put("state", state()))
            }
            else -> fail(404, "No such thing.")
        }
    }

    /** Tells chats about a walk right now, because the owner pressed the button. Ignores quiet hours and cooldown; that was their choice. */
    private fun announce(o: JSONObject): Reply {
        val chosen = o.optJSONArray("chats")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: port.settings.notify
        val targets = port.circles().filter { it.id in chosen }
        if (targets.isEmpty()) return fail(409, "Turn on at least one chat first.")
        if (port.name.isBlank()) return fail(409, "Add your name first.")
        if (clock() - lastAnnounce < ANNOUNCE_GAP_MS) return fail(429, "You just did that. Give it a few seconds.")
        lastAnnounce = clock()
        val state = if (o.optString("state") == "run") "running" else "walking"
        val msg = Delivery.text(port.name, state, port.templateStart)
        val results = JSONArray()
        for (c in targets) {
            val r = port.send(c, msg, "manual")
            results.put(JSONObject().put("id", c.id).put("label", c.target.label).put("ok", r.ok).put("error", r.error ?: JSONObject.NULL))
        }
        return ok(JSONObject().put("results", results).put("text", msg).put("state", state()))
    }

    // --- helpers ---

    private fun limit(key: String, gapMs: Long): Reply? {
        val now = clock()
        if (now - (lastSend[key] ?: Long.MIN_VALUE / 2) < gapMs) return fail(429, "Easy: wait a few seconds.")
        lastSend[key] = now
        return null
    }

    private fun json(body: String): JSONObject = if (body.isBlank()) JSONObject() else try { JSONObject(body) } catch (e: Exception) { throw BadRequest("That wasn't understood.") }
    private fun ok(o: JSONObject) = Reply(200, o.toString())
    private fun fail(status: Int, message: String) = Reply(status, JSONObject().put("error", message).toString())

    private companion object { const val MAX_CHATS = 12; const val ANNOUNCE_GAP_MS = 8_000L }
}
