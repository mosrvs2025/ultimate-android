package app.ultimate.social

import org.json.JSONArray
import org.json.JSONObject

/** One thing the friends feature did: a message that was sent (or failed) to one chat. */
data class LogEntry(val at: Long, val chat: String, val what: String, val ok: Boolean, val note: String = "")

/**
 * The last few things the feature did, newest first, so the dashboard can answer "did it actually go out?". Holds no secrets: a
 * note is scrubbed of anything that looks like a bot token or a webhook link before it is kept. Pure, tested.
 */
object ActivityLog {
    const val MAX = 60

    /** What a log line may say [what]: "walk", "finish", "test" or "manual". */
    private val KINDS = setOf("walk", "finish", "test", "manual")

    fun add(list: List<LogEntry>, e: LogEntry): List<LogEntry> = (listOf(clean(e)) + list).take(MAX)

    fun clean(e: LogEntry) = LogEntry(e.at, redact(e.chat).take(30), if (e.what in KINDS) e.what else "manual", e.ok, redact(e.note).take(140))

    fun redact(text: String): String = text
        .replace(Regex("""(?i)bot\d+:[A-Za-z0-9_\-]+"""), "bot…")
        .replace(Regex("""(?i)https?://\S+"""), "…")
        .filter { it >= ' ' && it != '\u007f' }

    fun encode(list: List<LogEntry>): String = JSONArray().also { a ->
        list.take(MAX).forEach { a.put(JSONObject().put("at", it.at).put("chat", it.chat).put("what", it.what).put("ok", it.ok).put("note", it.note)) }
    }.toString()

    /** Reads what [encode] wrote; anything damaged is dropped rather than failing. */
    fun decode(text: String?): List<LogEntry> = runCatching {
        val a = JSONArray(text ?: return emptyList())
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { clean(LogEntry(it.getLong("at"), it.getString("chat"), it.getString("what"), it.getBoolean("ok"), it.optString("note", ""))) } }.getOrNull() }.take(MAX)
    }.getOrDefault(emptyList())
}
