package app.ultimate.pluck.engine

import android.content.Context
import app.ultimate.core.JsonFile
import app.ultimate.pluck.model.AppItem
import app.ultimate.pluck.model.Coverage
import app.ultimate.pluck.model.Replacement
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** What the user decided by hand: apps they marked as replaced, and apps whose missing features they said they do not need. */
data class Marks(val custom: Map<String, Replacement> = emptyMap(), val accepted: Set<String> = emptySet())

class ReplacementStore(context: Context) {
    private val json = JsonFile(File(context.filesDir, "replacements.json"))
    private val _marks = MutableStateFlow(load())
    val marks: StateFlow<Marks> = _marks.asStateFlow()

    /** Marks any installed app as replaced by one of our tools. The user chose it, so nothing is flagged as missing. */
    suspend fun mark(app: AppItem, toolId: String) {
        val r = Replacement(app.packageName, app.label, toolId, Coverage.Full, note = "You marked this app as replaced")
        _marks.update { it.copy(custom = it.custom + (app.packageName to r)) }
        persist()
    }

    suspend fun unmark(pkg: String) {
        _marks.update { it.copy(custom = it.custom - pkg) }
        persist()
    }

    suspend fun accept(pkg: String) {
        _marks.update { it.copy(accepted = it.accepted + pkg) }
        persist()
    }

    suspend fun revoke(pkg: String) {
        _marks.update { it.copy(accepted = it.accepted - pkg) }
        persist()
    }

    private suspend fun persist() {
        val m = _marks.value
        json.write(
            JSONArray().apply {
                m.custom.values.forEach { put(JSONObject().put("k", "custom").put("pkg", it.originalPackage).put("name", it.originalName).put("tool", it.toolId)) }
                m.accepted.forEach { put(JSONObject().put("k", "accepted").put("pkg", it)) }
            },
        )
    }

    private fun load(): Marks {
        val a = json.read()
        val custom = HashMap<String, Replacement>()
        val accepted = HashSet<String>()
        for (i in 0 until a.length()) {
            runCatching {
                val o = a.getJSONObject(i)
                when (o.getString("k")) {
                    "custom" -> custom[o.getString("pkg")] =
                        Replacement(o.getString("pkg"), o.getString("name"), o.getString("tool"), Coverage.Full, note = "You marked this app as replaced")
                    "accepted" -> accepted += o.getString("pkg")
                }
            }
        }
        return Marks(custom, accepted)
    }
}
