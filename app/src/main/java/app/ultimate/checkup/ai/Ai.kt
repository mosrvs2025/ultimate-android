package app.ultimate.checkup.ai

/** A model the user can pick, cheapest last. */
data class AiModel(val id: String, val label: String, val blurb: String)

sealed interface AiResult {
    data class Answer(val text: String, val model: String, val inputTokens: Long, val outputTokens: Long) : AiResult
    /** The model's safety system declined; [category] says why when it says. */
    data class Refused(val category: String?) : AiResult
    data class Failed(val message: String, val badKey: Boolean = false) : AiResult
}

/** Asks an AI about a checkup report with the user's own key. Only network-enabled editions have one. */
interface AiAssistant {
    val providers: List<Provider> get() = Providers.all
    suspend fun ask(provider: Provider, apiKey: String, model: String, report: String, question: String): AiResult
}

object Ai {
    /** Null in editions without network access: they offer the shareable report instead. */
    val assistant: AiAssistant? by lazy { AiFactory.create() }
}
