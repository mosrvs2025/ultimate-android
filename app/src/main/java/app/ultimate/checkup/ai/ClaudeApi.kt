package app.ultimate.checkup.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Claude Messages API request and response, as plain JSON. No network here: Ultimate+ sends it, and tests check both directions.
 * Shapes follow the API reference: `POST /v1/messages` with `x-api-key` and `anthropic-version`.
 */
object ClaudeApi {
    const val URL = "https://api.anthropic.com/v1/messages"
    const val VERSION = "2026-10-21"
    /** Lets Anthropic re-run a request its safety system declines on the model it recommends for that case. */
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

    val MODELS = listOf(
        AiModel("claude-opus-5-5", "Opus 5.5", "Most thorough"),
        AiModel("claude-sonnet-5-5", "Sonnet 5.5", "Faster, cheaper"),
        AiModel("claude-haiku-5-5", "Haiku 5.5", "Cheapest"),
    )

    const val SYSTEM = "You are a privacy and security advisor reviewing a checkup report generated on the user's own Android phone. " +
        "Be concrete and practical: name the specific apps and settings, say why each matters, and give the exact steps to change it " +
        "on Android. Lead with what matters most. If the report says something was not measured, do not guess about it."

    /** Haiku has no server-side fallback; the other models take the "default" mode. */
    fun usesFallback(model: String) = model != "claude-haiku-5-5"

    fun headers(apiKey: String, model: String): Map<String, String> = buildMap {
        put("content-type", "application/json")
        put("x-api-key", apiKey)
        put("anthropic-version", VERSION)
        if (usesFallback(model)) put("anthropic-beta", FALLBACK_BETA)
    }

    fun body(model: String, report: String, question: String): String = JSONObject().apply {
        put("model", model)
        put("max_tokens", 16_000)
        put("system", SYSTEM)
        put("output_config", JSONObject().put("effort", "medium"))
        put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "$question\n\n---\n\n$report")))
        if (usesFallback(model)) put("fallbacks", "default")
    }.toString()

    /** Turns an HTTP status and body into an answer, a refusal or a readable failure. */
    fun parse(status: Int, body: String, model: String): AiResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (status !in 200..299) {
            val message = json?.optJSONObject("error")?.optString("message").orEmpty().take(200)
            return when (status) {
                401 -> AiResult.Failed("Anthropic did not accept this API key.", badKey = true)
                403 -> AiResult.Failed("This API key is not allowed to use $model.${if (message.isNotEmpty()) " $message" else ""}", badKey = true)
                429 -> AiResult.Failed("Rate limited or out of credit. Try again in a minute, or check your Anthropic account.")
                529, 503 -> AiResult.Failed("Anthropic is busy right now. Try again in a moment.")
                else -> AiResult.Failed("Anthropic returned an error ($status)${if (message.isNotEmpty()) ": $message" else "."}")
            }
        }
        if (json == null) return AiResult.Failed("Anthropic sent a reply this app could not read.")
        // Always look at why it stopped before reading the content: a refusal carries no answer.
        if (json.optString("stop_reason") == "refusal") {
            val category = json.optJSONObject("stop_details")?.optString("category")?.takeIf { it.isNotEmpty() && it != "null" }
            return AiResult.Refused(category)
        }
        val content = json.optJSONArray("content") ?: JSONArray()
        val text = (0 until content.length()).mapNotNull { i -> content.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("text") }
            .joinToString("\n\n").trim()
        val usage = json.optJSONObject("usage")
        val truncated = json.optString("stop_reason") == "max_tokens"
        return AiResult.Answer(
            (text.ifEmpty { "(No text in the answer.)" }) + if (truncated) "\n\n(The answer was cut off at its length limit.)" else "",
            json.optString("model", model), usage?.optLong("input_tokens") ?: 0, usage?.optLong("output_tokens") ?: 0,
        )
    }
}
