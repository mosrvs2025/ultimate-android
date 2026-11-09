package app.ultimate.home

import android.content.Context
import app.ultimate.timeline.Finding
import app.ultimate.timeline.Moment
import app.ultimate.timeline.Source
import org.json.JSONArray
import org.json.JSONObject

/**
 * The dashboard's last numbers, kept on the phone so Home can draw them the instant it opens and swap in fresh ones a moment
 * later, instead of building up piece by piece. Keeps only the summary the cards need (no notification text); a missing or
 * unreadable cache just means the old behaviour. Pure encode/decode, so it is tested.
 */
object FactsCache {
    private const val KEY = "facts"

    fun encode(f: Facts): String = JSONObject().apply {
        f.battery?.let { put("bat", it) }; put("chg", f.charging); put("free", f.free); put("total", f.total)
        f.screenMsToday?.let { put("scr", it) }; f.notificationsToday?.let { put("n", it) }; f.notificationAccess?.let { put("acc", it) }
        f.checkupScore?.let { put("cs", it) }; f.checkupAt?.let { put("ca", it) }; f.updateVersion?.let { put("upd", it) }
        f.finding?.let { put("find", it) }
        f.findingTarget?.let { t -> put("ft", JSONObject().put("text", t.text).put("src", t.source?.name ?: "").put("q", t.query ?: "").put("late", t.lateNight)) }
        // Just enough of today for the cards (counts, most-used app, minutes played or focused): no titles or details.
        put("today", JSONArray().apply { f.today.take(300).forEach { m -> put(JSONObject().put("s", m.source.name).put("at", m.at).put("e", m.end).put("a", m.app)) } })
    }.toString()

    fun decode(json: String, now: Long = System.currentTimeMillis()): Facts? = runCatching {
        val o = JSONObject(json)
        fun optLong(k: String) = if (o.has(k)) o.getLong(k) else null
        val ft = o.optJSONObject("ft")?.let { t ->
            Finding(t.getString("text"), t.getString("src").takeIf { it.isNotEmpty() }?.let { s -> Source.entries.firstOrNull { it.name == s } }, t.getString("q").takeIf { it.isNotEmpty() }, t.optBoolean("late"))
        }
        val arr = o.getJSONArray("today")
        val today = (0 until arr.length()).mapNotNull { i -> arr.getJSONObject(i).let { m -> Source.entries.firstOrNull { it.name == m.getString("s") }?.let { s -> Moment(s, m.getLong("at"), "", app = m.optString("a"), end = m.getLong("e")) } } }
        Facts(
            if (o.has("bat")) o.getInt("bat") else null, o.getBoolean("chg"), o.getLong("free"), o.getLong("total"),
            optLong("scr"), if (o.has("n")) o.getInt("n") else null, if (o.has("acc")) o.getBoolean("acc") else null,
            if (o.has("cs")) o.getInt("cs") else null, optLong("ca"), o.optString("upd").takeIf { it.isNotEmpty() }, now,
            today, o.optString("find").takeIf { it.isNotEmpty() }, ft,
        )
    }.getOrNull()

    fun save(c: Context, f: Facts) { runCatching { c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString(KEY, encode(f)).apply() } }
    /** The last saved facts. An "update is in your Downloads" note that is no newer than this build is dropped, so a stale one never flashes. */
    fun load(c: Context): Facts? = runCatching { c.getSharedPreferences("home", Context.MODE_PRIVATE).getString(KEY, null)?.let { decode(it) } }.getOrNull()
        ?.let { f -> if (f.updateVersion == app.ultimate.BuildConfig.VERSION_NAME) f.copy(updateVersion = null) else f }
}
