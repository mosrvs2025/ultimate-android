package app.ultimate.checkup.model

import org.json.JSONArray
import org.json.JSONObject

/** What one app could reach at a checkup: ordinary permissions plus the powerful special ones. */
data class AppSnap(val pkg: String, val label: String, val access: Set<String>, val special: Set<String>)

data class Snapshot(val at: Long, val score: Int, val apps: List<AppSnap>)

/** One thing that changed between two checkups. [bad] changes need a look; the others are news or improvements. */
data class Change(val pkg: String?, val text: String, val bad: Boolean)

/**
 * Compares checkups over time, so a weekly run can say exactly what is new ("Flashlight can now see your location") instead
 * of repeating every finding. Pure, so it is tested.
 */
object Guardian {
    private val specialNames = mapOf(
        "accessibility" to "see and control everything on screen",
        "notifications" to "read your notifications",
        "admin" to "act as a device administrator",
        "vpn" to "route your internet traffic",
        "installer" to "install other apps",
    )

    fun snapshot(scan: Scan, own: Set<String>): Snapshot = Snapshot(scan.at, scan.score, scan.apps.filter { !it.system && it.packageName !in own }.map { a ->
        AppSnap(a.packageName, a.label, a.granted.map { it.name }.toSet(), buildSet {
            if (a.accessibility) add("accessibility")
            if (a.notificationAccess == true) add("notifications")
            if (a.deviceAdmin) add("admin")
            if (a.providesVpn) add("vpn")
            if (a.canInstallApps) add("installer")
        })
    })

    fun diff(old: Snapshot, new: Snapshot): List<Change> {
        val before = old.apps.associateBy { it.pkg }
        val after = new.apps.associateBy { it.pkg }
        val out = mutableListOf<Change>()
        after.values.filter { it.pkg !in before }.sortedBy { it.label }.forEach { a ->
            val reach = a.access.mapNotNull { n -> runCatching { Access.valueOf(n).label.lowercase() }.getOrNull() } + a.special.mapNotNull { specialNames[it] }
            out += Change(a.pkg, "${a.label} was installed" + if (reach.isNotEmpty()) " and can use ${reach.take(4).joinToString()}" else "", reach.isNotEmpty() && a.special.isNotEmpty())
        }
        before.values.filter { it.pkg !in after }.sortedBy { it.label }.forEach { out += Change(it.pkg, "${it.label} was removed", false) }
        after.values.filter { it.pkg in before }.sortedBy { it.label }.forEach { a ->
            val b = before.getValue(a.pkg)
            (a.special - b.special).forEach { s -> out += Change(a.pkg, "${a.label} can now ${specialNames[s] ?: s}", true) }
            (a.access - b.access).forEach { n -> runCatching { Access.valueOf(n) }.getOrNull()?.let { out += Change(a.pkg, "${a.label} can now use ${it.label.lowercase()}", true) } }
            val lost = (b.access - a.access).mapNotNull { n -> runCatching { Access.valueOf(n).label.lowercase() }.getOrNull() } + (b.special - a.special).mapNotNull { specialNames[it] }
            if (lost.isNotEmpty()) out += Change(a.pkg, "${a.label} can no longer use ${lost.joinToString()}", false)
        }
        if (new.score != old.score) out += Change(null, "Score went from ${old.score} to ${new.score}", new.score < old.score)
        return out.sortedByDescending { it.bad }
    }

    fun encode(s: Snapshot): String = JSONObject().put("at", s.at).put("score", s.score).put("apps", JSONArray().apply {
        s.apps.forEach { a -> put(JSONObject().put("pkg", a.pkg).put("label", a.label).put("access", JSONArray(a.access.toList())).put("special", JSONArray(a.special.toList()))) }
    }).toString()

    fun decode(text: String): Snapshot {
        val o = JSONObject(text); val a = o.getJSONArray("apps")
        fun set(arr: JSONArray) = List(arr.length()) { arr.getString(it) }.toSet()
        return Snapshot(o.getLong("at"), o.getInt("score"), List(a.length()) { a.getJSONObject(it) }.map { j -> AppSnap(j.getString("pkg"), j.getString("label"), set(j.getJSONArray("access")), set(j.getJSONArray("special"))) })
    }
}
