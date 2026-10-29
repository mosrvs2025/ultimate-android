package app.ultimate.checkup

import app.ultimate.checkup.ai.AiResult
import app.ultimate.checkup.ai.AiHttp
import app.ultimate.checkup.ai.Anthropic
import app.ultimate.checkup.ai.Gemini
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sends a real HTTP request to a local stand-in for the API and checks what went over the wire and what came back. */
class AiHttpTest {
    private val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    @Volatile private var seenHeaders: Map<String, String> = emptyMap()
    @Volatile private var seenBody = ""

    /** Answers exactly one HTTP request with [status] and [body]. */
    private fun reply(status: Int, body: String) {
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream().buffered()
                fun line(): String { val sb = StringBuilder(); while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }; return sb.toString() }
                line() // request line
                val headers = generateSequence { line().takeIf { it.isNotEmpty() } }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                seenHeaders = headers
                val length = headers["content-length"]?.toInt() ?: 0
                seenBody = ByteArray(length).also { var read = 0; while (read < length) read += input.read(it, read, length - read) }.decodeToString()
                val out = body.toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(out); flush()
                }
            }
        }
    }

    private val url get() = "http://127.0.0.1:${server.localPort}/v1/messages"

    @After fun stop() = server.close()

    @Test fun sendsTheRequestAndReadsTheAnswer() = runBlocking {
        reply(200, """{"model":"claude-opus-5-5","stop_reason":"end_turn","content":[{"type":"text","text":"Turn on a screen lock."}],"usage":{"input_tokens":10,"output_tokens":5}}""")
        val r = AiHttp(url).ask(Anthropic, "sk-ant-test", "claude-opus-5-5", "REPORT", "Q?")
        assertEquals("Turn on a screen lock.", (r as AiResult.Answer).text)
        assertEquals("sk-ant-test", seenHeaders["x-api-key"])
        assertEquals("2026-10-21", seenHeaders["anthropic-version"])
        assertEquals("server-side-fallback-2026-07-01", seenHeaders["anthropic-beta"])
        assertTrue(seenHeaders["content-type"]!!.startsWith("application/json"))
        assertEquals("Q?\n\n---\n\nREPORT", JSONObject(seenBody).getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun readsTheErrorBodyOnFailure() = runBlocking {
        reply(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        val r = AiHttp(url).ask(Anthropic, "bad", "claude-sonnet-5-5", "r", "q") as AiResult.Failed
        assertTrue(r.badKey)
    }

    @Test fun anUnreachableServerIsAReadableFailure() = runBlocking {
        val r = AiHttp("http://127.0.0.1:1/v1/messages").ask(Gemini, "k", "gemini-2.5-flash", "r", "q") as AiResult.Failed
        assertTrue(r.message.startsWith("Could not reach Gemini"))
    }
}
