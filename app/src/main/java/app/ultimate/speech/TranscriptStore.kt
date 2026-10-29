package app.ultimate.speech

import android.content.Context
import app.ultimate.core.JsonFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Transcripts of anything in the Library, by file uri, so every tool's results can be read and searched by what was said. */
class TranscriptStore(context: Context) {
    private val index = JsonFile(File(context.filesDir, "transcripts.json"))
    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<String, String>> = _all.asStateFlow()

    suspend fun put(uri: String, text: String) { _all.value = _all.value + (uri to text); persist() }
    suspend fun remove(uri: String) { _all.value = _all.value - uri; persist() }

    private fun load(): Map<String, String> {
        val a = index.read()
        return List(a.length()) { a.getJSONObject(it) }.associate { it.getString("uri") to it.getString("text") }
    }
    private suspend fun persist() = index.write(JSONArray().apply { _all.value.forEach { (u, t) -> put(JSONObject().put("uri", u).put("text", t)) } })
}
