package app.ultimate.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class FlingTest {
    private fun receive(frames: List<Pair<String, Map<String, String>>>): ByteArray {
        var inbox: Fling.Inbox? = null
        var out: ByteArray? = null
        for ((cmd, a) in frames) when (cmd) {
            Cmd.FLING_BEGIN -> inbox = Fling.Inbox(a.getValue("id"), a.getValue("name"), a.getValue("size").toLong(), a.getValue("sha"), a.getValue("mime"))
            Cmd.FLING_CHUNK -> inbox!!.accept(a.getValue("i").toInt(), a.getValue("data"))
            Cmd.FLING_END -> out = inbox!!.finish()
        }
        return out!!
    }

    @Test fun aFileSurvivesTheTripExactly() {
        for (size in listOf(0, 1, Fling.CHUNK - 1, Fling.CHUNK, Fling.CHUNK + 1, 3 * Fling.CHUNK + 777)) {
            val bytes = Random(size).nextBytes(size)
            val frames = Fling.frames("x", "photo.jpg", "image/jpeg", bytes)
            assertEquals(Fling.pieces(size.toLong()) + 2, frames.size)
            assertTrue(bytes.contentEquals(receive(frames)))
        }
    }

    @Test fun aDamagedPieceIsCaughtNotSaved() {
        val bytes = Random(1).nextBytes(2 * Fling.CHUNK)
        val frames = Fling.frames("x", "a.bin", "application/octet-stream", bytes).toMutableList()
        val (cmd, a) = frames[1]
        val bad = Fling.decode(a.getValue("data")).also { it[10] = (it[10] + 1).toByte() }
        frames[1] = cmd to (a + ("data" to Fling.encode(bad)))
        try { receive(frames); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("damaged")) }
    }

    @Test fun piecesOutOfOrderOrMissingAreRefused() {
        val bytes = Random(2).nextBytes(3 * Fling.CHUNK)
        val frames = Fling.frames("x", "a.bin", "application/octet-stream", bytes)
        try { receive(frames.filterIndexed { i, _ -> i != 2 }); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("expected")) }
        try { receive(frames.dropLast(2) + frames.last()); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("incomplete")) }
    }

    @Test fun tooBigIsRefusedBeforeAnythingIsSent() {
        assertNotNull(Fling.refuse(Fling.MAX_BYTES + 1))
        assertNull(Fling.refuse(Fling.MAX_BYTES))
        try { Fling.Inbox("x", "a", Fling.MAX_BYTES + 1, "", ""); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun namesCannotEscapeTheFolder() {
        assertEquals("passwd", Fling.safeName("../../etc/passwd"))
        assertEquals("evil.txt", Fling.safeName("C:\\Windows\\evil.txt"))
        assertEquals("hidden", Fling.safeName(".hidden"))
        assertEquals("file", Fling.safeName(".."))
        assertEquals("file", Fling.safeName(null))
        assertEquals("ab.pdf", Fling.safeName("a\u0000b?.pdf"))
        val long = Fling.safeName("x".repeat(200) + ".jpeg")
        assertEquals(80, long.length); assertTrue(long.endsWith(".jpeg"))
    }

    @Test fun onlyWebLinksCount() {
        assertEquals("https://example.com/a?b=1", Fling.link("look at this https://example.com/a?b=1."))
        assertEquals("http://x.org", Fling.link("(http://x.org)"))
        assertNull(Fling.link("intent://evil#Intent;end"))
        assertNull(Fling.link("just words"))
    }
}
