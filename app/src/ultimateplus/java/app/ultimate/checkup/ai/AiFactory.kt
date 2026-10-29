package app.ultimate.checkup.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AiFactory {
    fun create(): AiAssistant = AiHttp()
}

/** Sends a checkup report to the chosen provider with the user's own API key, over HTTPS straight to that provider. */
internal class AiHttp(private val urlOverride: String? = null) : AiAssistant {
    override suspend fun ask(provider: Provider, apiKey: String, model: String, report: String, question: String): AiResult = withContext(Dispatchers.IO) {
        val c = URL(urlOverride ?: provider.url(model)).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            // Long answers can take a few minutes; the request is not streamed.
            c.readTimeout = 600_000
            c.doOutput = true
            provider.headers(apiKey, model).forEach { (k, v) -> c.setRequestProperty(k, v) }
            val bytes = provider.body(model, report, question).toByteArray()
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            provider.parse(status, text, model)
        } catch (e: IOException) {
            AiResult.Failed("Could not reach ${provider.label}: ${e.message?.take(160) ?: e.javaClass.simpleName}")
        } finally {
            c.disconnect()
        }
    }
}
