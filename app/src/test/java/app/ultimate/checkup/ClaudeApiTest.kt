package app.ultimate.checkup

import app.ultimate.checkup.ai.AiResult
import app.ultimate.checkup.ai.ClaudeApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeApiTest {
    @Test fun requestCarriesTheReportTheVersionAndTheFallback() {
        val body = JSONObject(ClaudeApi.body("claude-opus-5-5", "REPORT", "What first?"))
        assertEquals("claude-opus-5-5", body.getString("model"))
        assertEquals(16_000, body.getInt("max_tokens"))
        assertEquals("medium", body.getJSONObject("output_config").getString("effort"))
        assertEquals("default", body.getString("fallbacks"))
        assertFalse("sampling fields are rejected on 2026-10-21", body.has("temperature") || body.has("top_p") || body.has("top_k"))
        val msg = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", msg.getString("role"))
        assertEquals("What first?\n\n---\n\nREPORT", msg.getString("content"))
        val h = ClaudeApi.headers("sk-ant-x", "claude-opus-5-5")
        assertEquals("2026-10-21", h["anthropic-version"])
        assertEquals("sk-ant-x", h["x-api-key"])
        assertEquals(ClaudeApi.FALLBACK_BETA, h["anthropic-beta"])
    }

    @Test fun haikuGetsNoServerSideFallback() {
        assertFalse(JSONObject(ClaudeApi.body("claude-haiku-5-5", "r", "q")).has("fallbacks"))
        assertNull(ClaudeApi.headers("k", "claude-haiku-5-5")["anthropic-beta"])
    }

    @Test fun answersJoinTextBlocksAndSkipOthers() {
        val reply = """{"model":"claude-opus-5-5","stop_reason":"end_turn","content":[{"type":"thinking","thinking":""},{"type":"text","text":"Fix the lock."},{"type":"text","text":"Then update."}],"usage":{"input_tokens":1200,"output_tokens":80}}"""
        val r = ClaudeApi.parse(200, reply, "claude-opus-5-5") as AiResult.Answer
        assertEquals("Fix the lock.\n\nThen update.", r.text)
        assertEquals(1200, r.inputTokens); assertEquals(80, r.outputTokens)
    }

    @Test fun refusalIsCheckedBeforeContent() {
        val reply = """{"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber","explanation":"x"},"content":[{"type":"text","text":"partial"}]}"""
        assertEquals(AiResult.Refused("cyber"), ClaudeApi.parse(200, reply, "m"))
        assertEquals(AiResult.Refused(null), ClaudeApi.parse(200, """{"stop_reason":"refusal","stop_details":{"category":null},"content":[]}""", "m"))
    }

    @Test fun truncatedAnswersSaySo() {
        val r = ClaudeApi.parse(200, """{"stop_reason":"max_tokens","content":[{"type":"text","text":"Long"}]}""", "m") as AiResult.Answer
        assertTrue(r.text.endsWith("(The answer was cut off at its length limit.)"))
    }

    @Test fun errorsBecomePlainMessages() {
        val bad = ClaudeApi.parse(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""", "m") as AiResult.Failed
        assertTrue(bad.badKey)
        assertTrue((ClaudeApi.parse(429, "", "m") as AiResult.Failed).message.startsWith("Rate limited"))
        assertTrue((ClaudeApi.parse(529, "{}", "m") as AiResult.Failed).message.contains("busy"))
        val other = ClaudeApi.parse(400, """{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens too large"}}""", "m") as AiResult.Failed
        assertEquals("Anthropic returned an error (400): max_tokens too large", other.message)
        assertTrue((ClaudeApi.parse(200, "not json", "m") as AiResult.Failed).message.contains("could not read"))
    }
}

class ProvidersTest {
    @Test fun geminiRequestAndReply() {
        val b = JSONObject(app.ultimate.checkup.ai.Gemini.body("gemini-2.5-flash", "R", "Q"))
        assertEquals("Q\n\n---\n\nR", b.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertTrue(app.ultimate.checkup.ai.Gemini.url("gemini-2.5-flash").endsWith("/models/gemini-2.5-flash:generateContent"))
        assertEquals("AIzaX", app.ultimate.checkup.ai.Gemini.headers("AIzaX", "m")["x-goog-api-key"])
        val ok = app.ultimate.checkup.ai.Gemini.parse(200, """{"candidates":[{"content":{"parts":[{"text":"Fix "},{"text":"lock."}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":3}}""", "m") as AiResult.Answer
        assertEquals("Fix lock.", ok.text); assertEquals(9, ok.inputTokens)
        assertTrue(app.ultimate.checkup.ai.Gemini.parse(200, """{"candidates":[{"finishReason":"SAFETY"}]}""", "m") is AiResult.Refused)
        assertTrue((app.ultimate.checkup.ai.Gemini.parse(400, """{"error":{"code":400,"message":"API key not valid"}}""", "m") as AiResult.Failed).message.contains("API key not valid"))
    }

    @Test fun openAiAndGrokShareTheChatFormat() {
        listOf(app.ultimate.checkup.ai.OpenAi, app.ultimate.checkup.ai.Xai).forEach { p ->
            val b = JSONObject(p.body("m1", "R", "Q"))
            assertEquals("m1", b.getString("model"))
            assertEquals("system", b.getJSONArray("messages").getJSONObject(0).getString("role"))
            assertEquals("Bearer k", p.headers("k", "m")["authorization"])
            val ok = p.parse(200, """{"model":"m1","choices":[{"message":{"content":"Hi","refusal":null},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":1}}""", "m1") as AiResult.Answer
            assertEquals("Hi", ok.text)
            assertTrue(p.parse(200, """{"choices":[{"message":{"content":null,"refusal":"No."},"finish_reason":"stop"}]}""", "m") is AiResult.Refused)
            assertTrue((p.parse(404, """{"error":{"message":"model not found"}}""", "nope") as AiResult.Failed).message.contains("no model called"))
        }
        assertTrue(app.ultimate.checkup.ai.Xai.url("x").startsWith("https://api.x.ai/"))
    }
}
