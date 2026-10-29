package app.ultimate.checkup.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * One AI service the user can bring their own key for: where requests go, what they look like, and how to read the reply.
 * Pure JSON, no network, so every provider is checked by unit tests. Model names change often, so they are editable in the app.
 */
interface Provider {
    val id: String
    val label: String
    /** Models to offer as one-tap choices; the first is the default. The user can also type any other model name. */
    val models: List<AiModel>
    val keyPrefix: String
    val keyUrl: String
    fun url(model: String): String
    fun headers(apiKey: String, model: String): Map<String, String>
    fun body(model: String, report: String, question: String): String
    fun parse(status: Int, body: String, model: String): AiResult
}

object Providers {
    const val SYSTEM = ClaudeApi.SYSTEM
    val all: List<Provider> = listOf(Anthropic, Gemini, OpenAi, Xai)
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: Anthropic

    internal fun prompt(report: String, question: String) = "$question\n\n---\n\n$report"

    /** Shared HTTP error wording, so every provider fails the same understandable way. */
    internal fun httpError(provider: String, status: Int, message: String, model: String): AiResult.Failed = when (status) {
        401 -> AiResult.Failed("$provider did not accept this API key.", badKey = true)
        403 -> AiResult.Failed("This key is not allowed to use $model.${if (message.isNotEmpty()) " $message" else ""}", badKey = true)
        404 -> AiResult.Failed("$provider has no model called \"$model\". Pick another model name.")
        429 -> AiResult.Failed("Rate limited or out of credit at $provider. Try again in a minute, or check your account.")
        500, 502, 503, 529 -> AiResult.Failed("$provider is busy right now. Try again in a moment.")
        else -> AiResult.Failed("$provider returned an error ($status)${if (message.isNotEmpty()) ": $message" else "."}")
    }
}

object Anthropic : Provider {
    override val id = "anthropic"
    override val label = "Claude"
    override val models = ClaudeApi.MODELS
    override val keyPrefix = "sk-ant-"
    override val keyUrl = "console.anthropic.com"
    override fun url(model: String) = ClaudeApi.URL
    override fun headers(apiKey: String, model: String) = ClaudeApi.headers(apiKey, model)
    override fun body(model: String, report: String, question: String) = ClaudeApi.body(model, report, question)
    override fun parse(status: Int, body: String, model: String) = ClaudeApi.parse(status, body, model)
}

/** Google Gemini. Has a free tier, which makes it the no-cost cloud option. */
object Gemini : Provider {
    override val id = "gemini"
    override val label = "Gemini"
    override val models = listOf(AiModel("gemini-2.5-flash", "2.5 Flash", "Fast, free tier"), AiModel("gemini-2.5-pro", "2.5 Pro", "Most thorough"))
    override val keyPrefix = "AIza"
    override val keyUrl = "aistudio.google.com/apikey"
    override fun url(model: String) = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
    override fun headers(apiKey: String, model: String) = mapOf("content-type" to "application/json", "x-goog-api-key" to apiKey)
    override fun body(model: String, report: String, question: String): String = JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Providers.SYSTEM))))
        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", Providers.prompt(report, question))))))
        .toString()
    override fun parse(status: Int, body: String, model: String): AiResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (status !in 200..299) return Providers.httpError(label, status, json?.optJSONObject("error")?.optString("message").orEmpty().take(200), model)
        json ?: return AiResult.Failed("Gemini sent a reply this app could not read.")
        json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let { return AiResult.Refused(it.lowercase()) }
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: return AiResult.Failed("Gemini sent no answer.")
        if (candidate.optString("finishReason") in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST")) return AiResult.Refused(candidate.optString("finishReason").lowercase())
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotEmpty() } }.joinToString("").trim()
        val usage = json.optJSONObject("usageMetadata")
        val cut = if (candidate.optString("finishReason") == "MAX_TOKENS") "\n\n(The answer was cut off at its length limit.)" else ""
        return AiResult.Answer(text.ifEmpty { "(No text in the answer.)" } + cut, json.optString("modelVersion", model), usage?.optLong("promptTokenCount") ?: 0, usage?.optLong("candidatesTokenCount") ?: 0)
    }
}

/** OpenAI's chat completions format, which xAI's Grok also speaks. */
open class ChatCompletions(
    override val id: String, override val label: String, override val models: List<AiModel>,
    override val keyPrefix: String, override val keyUrl: String, private val endpoint: String,
) : Provider {
    override fun url(model: String) = endpoint
    override fun headers(apiKey: String, model: String) = mapOf("content-type" to "application/json", "authorization" to "Bearer $apiKey")
    override fun body(model: String, report: String, question: String): String = JSONObject()
        .put("model", model)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", Providers.SYSTEM))
            .put(JSONObject().put("role", "user").put("content", Providers.prompt(report, question))))
        .toString()
    override fun parse(status: Int, body: String, model: String): AiResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (status !in 200..299) return Providers.httpError(label, status, json?.optJSONObject("error")?.optString("message").orEmpty().take(200), model)
        json ?: return AiResult.Failed("$label sent a reply this app could not read.")
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return AiResult.Failed("$label sent no answer.")
        val message = choice.optJSONObject("message")
        message?.optString("refusal")?.takeIf { it.isNotEmpty() && it != "null" }?.let { return AiResult.Refused(null) }
        if (choice.optString("finish_reason") == "content_filter") return AiResult.Refused("content_filter")
        val text = message?.optString("content").orEmpty().trim()
        val usage = json.optJSONObject("usage")
        val cut = if (choice.optString("finish_reason") == "length") "\n\n(The answer was cut off at its length limit.)" else ""
        return AiResult.Answer(text.ifEmpty { "(No text in the answer.)" } + cut, json.optString("model", model), usage?.optLong("prompt_tokens") ?: 0, usage?.optLong("completion_tokens") ?: 0)
    }
}

object OpenAi : ChatCompletions(
    "openai", "ChatGPT", listOf(AiModel("gpt-4o-mini", "4o mini", "Cheap, fast"), AiModel("gpt-4o", "4o", "More thorough")),
    "sk-", "platform.openai.com/api-keys", "https://api.openai.com/v1/chat/completions",
)

object Xai : ChatCompletions(
    "xai", "Grok", listOf(AiModel("grok-3-mini", "3 mini", "Cheap, fast"), AiModel("grok-3", "3", "More thorough")),
    "xai-", "console.x.ai", "https://api.x.ai/v1/chat/completions",
)
