package app.ultimate.checkup.ai

/** This edition has no network access, so there is no AI assistant; the report can still be shared. */
object AiFactory {
    fun create(): AiAssistant? = null
}
