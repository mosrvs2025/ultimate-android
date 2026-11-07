package app.ultimate.ai

import org.json.JSONArray
import org.json.JSONObject

/** One thing Needle proposes doing, with how sure it is. */
data class AiCall(val name: String, val args: Map<String, Any?>, val confidence: Double)

/**
 * What Local AI may do, and how its answers are read. Pure, so the policy is tested.
 *
 * Measured on the stock Needle 3 model with these tools (27 requests, see docs/LOCAL_AI.md): it is reliable for direct
 * phone actions (flashlight, volume, storage, clean-up) and unreliable for picking which app tool to open. So it only
 * ever proposes; the person taps to confirm; and below [MIN_CONFIDENCE] it says nothing, leaving the keyword matcher
 * and the rule parser to answer.
 */
object AiTools {
    /** At 0.5, 8 of the 9 requests it acted on were right; below that it guesses. */
    const val MIN_CONFIDENCE = 0.5

    val TOOLS: String = JSONArray().apply {
        fun tool(name: String, description: String, vararg params: Triple<String, String, String>) = put(JSONObject().apply {
            put("name", name); put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply { params.forEach { (n, t, d) -> put(n, JSONObject().put("type", t).put("description", d)) } })
                put("required", JSONArray(params.map { it.first }))
            })
        })
        tool("flashlight", "Turn the flashlight on or off", Triple("on", "boolean", "true to turn it on"))
        tool("set_volume", "Set the media volume", Triple("percent", "integer", "0 to 100"))
        tool("set_brightness", "Set the screen brightness", Triple("percent", "integer", "0 to 100"))
        tool("compress_video", "Make a video file smaller")
        tool("edit_video", "Edit, trim or add music or captions to a video")
        tool("record_audio", "Record a voice memo or audio")
        tool("find_notification", "Search past notifications", Triple("query", "string", "words to look for"))
        tool("find_copied", "Search things copied to the clipboard", Triple("query", "string", "words to look for"))
        tool("storage_status", "Show how much storage space is used and free")
        tool("clean_up", "Free up space: duplicates, old photos, unused apps")
        tool("start_echo", "Start sensing hands and movement near the phone with sound")
    }.toString()

    const val SYSTEM = "You control an Android phone through the Ultimate app. Only call a tool when the request clearly asks for it."

    /** Reads Needle's JSON answer. Calls under the confidence bar, or a failed answer, come back empty. */
    fun parse(json: String?, min: Double = MIN_CONFIDENCE): List<AiCall> {
        val o = runCatching { JSONObject(json ?: return emptyList()) }.getOrNull() ?: return emptyList()
        if (!o.optBoolean("success", false)) return emptyList()
        val conf = o.optDouble("confidence", 0.0)
        if (conf < min) return emptyList()
        val calls = o.optJSONArray("function_calls") ?: return emptyList()
        return (0 until calls.length()).mapNotNull { i ->
            val c = calls.optJSONObject(i) ?: return@mapNotNull null
            val a = c.optJSONObject("arguments") ?: JSONObject()
            AiCall(c.optString("name"), a.keys().asSequence().associateWith { k -> a.opt(k) }, conf)
        }.filter { it.name.isNotBlank() }
    }

    /** Requests about later ("when", "if", "every time") are rules, which the rule parser handles reliably; Needle acts now. */
    fun isRule(text: String) = Regex("^\\s*(when|whenever|if|every time|each time|as soon as)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** What a call would do, in words for the confirm button. */
    fun describe(c: AiCall): String = when (c.name) {
        "flashlight" -> if (c.args["on"] == false) "Turn the flashlight off" else "Turn the flashlight on"
        "set_volume" -> when (val p = (c.args["percent"] as? Number)?.toInt()) { -1 -> "Turn the volume up"; -2 -> "Turn the volume down"; 0 -> "Mute the media volume"; else -> "Set the media volume to ${p?.coerceIn(0, 100) ?: 50}%" }
        "set_brightness" -> when (val p = (c.args["percent"] as? Number)?.toInt()) { -1 -> "Make the screen brighter"; -2 -> "Make the screen dimmer"; else -> "Set the screen brightness to ${p?.coerceIn(0, 100) ?: 50}%" }
        "compress_video" -> "Make a video smaller (Squeeze)"
        "edit_video" -> "Edit a video (Studio)"
        "record_audio" -> "Record audio (Recorder)"
        "find_notification" -> "Search notifications for \"${c.args["query"] ?: ""}\""
        "find_copied" -> "Search what you copied for \"${c.args["query"] ?: ""}\""
        "storage_status" -> "Show your storage"
        "clean_up" -> "Free up space (Clean up)"
        "start_echo" -> "Start Echo"
        else -> c.name
    }
}
