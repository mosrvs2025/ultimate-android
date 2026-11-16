package app.ultimate

import app.ultimate.mesh.MeshWire
import app.ultimate.mesh.RtcSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RtcSignalTest {
    private fun sdp(n: Int) = buildString { var i = 0; while (length < n) { append("a=candidate:$i 1 udp 2113937151 192.168.1.${i % 250} 5${i % 9000} typ host\r\n"); i++ } }.take(n)

    private fun send(calls: RtcSignal.Calls, viewer: String, sid: String, text: String): String? {
        val parts = RtcSignal.split(text); var whole: String? = null
        for (i in parts.indices) whole = calls.offerPiece(viewer, RtcSignal.piece(sid, parts, i))
        return whole
    }

    @Test fun `a description of any size comes back exactly and every message fits the mesh line`() {
        for (n in listOf(1, 6_999, 7_000, 7_001, 20_000, 80_000)) {
            val text = sdp(n); val parts = RtcSignal.split(text)
            assertEquals((n + RtcSignal.PIECE - 1) / RtcSignal.PIECE, parts.size)
            parts.indices.forEach { assertTrue(RtcSignal.piece("s1", parts, it)["data"]!!.length + 200 < MeshWire.MAX_LINE) }
            val asm = RtcSignal.Assembler(); var out: String? = null
            for (i in parts.indices) out = asm.add(RtcSignal.piece("s1", parts, i))
            assertEquals(text, out)
        }
        val uni = "v=0\r\ns=Pixel – déjà vu 🎥\r\n"; val asm = RtcSignal.Assembler(); val p = RtcSignal.split(uni)
        assertEquals(uni, asm.add(RtcSignal.piece("s", p, 0)))
    }

    @Test fun `pieces that are damaged, out of order or too many are refused`() {
        val parts = RtcSignal.split(sdp(30_000)); val asm = RtcSignal.Assembler()
        fun refused(args: Map<String, String>) = try { asm.add(args); false } catch (e: IllegalArgumentException) { true }
        assertTrue("middle piece with no start", refused(RtcSignal.piece("s", parts, 2)))
        asm.add(RtcSignal.piece("s", parts, 0)); assertTrue("skipped a piece", refused(RtcSignal.piece("s", parts, 2)))
        assertTrue("different call", refused(RtcSignal.piece("other", parts, 1)))
        assertTrue("not base64", refused(mapOf("sid" to "s", "part" to "0", "parts" to "2", "data" to "!!!")))
        assertTrue("too many pieces", refused(mapOf("sid" to "s", "part" to "0", "parts" to "${RtcSignal.MAX_PIECES + 1}", "data" to "AAAA")))
        assertTrue("no id", refused(mapOf("part" to "0", "parts" to "1", "data" to "AAAA")))
        assertTrue("missing fields", refused(emptyMap()))
    }

    @Test fun `the whole exchange works, the answer is fetched in pieces, and bye ends it`() {
        var now = 1_000L; val calls = RtcSignal.Calls { now }
        val offer = sdp(15_000)
        assertEquals(offer, send(calls, "viewerA", "s1", offer))
        assertEquals(mapOf("wait" to "1"), calls.answerPiece("viewerA", "s1", 0))      // the camera is still making its answer
        val answer = sdp(9_000); calls.answered("viewerA", answer)
        val first = calls.answerPiece("viewerA", "s1", 0); val parts = first["parts"]!!.toInt()
        val asm = RtcSignal.Assembler(); var whole: String? = asm.add(first)
        for (i in 1 until parts) whole = asm.add(calls.answerPiece("viewerA", "s1", i))
        assertEquals(answer, whole)
        assertEquals(mapOf("fail" to "no such piece"), calls.answerPiece("viewerA", "s1", parts))
        assertTrue(calls.bye("viewerA")); assertFalse(calls.bye("viewerA"))
        assertEquals(mapOf("fail" to "no call from you"), calls.answerPiece("viewerA", "s1", 0))
    }

    @Test fun `a failed answer says why`() {
        val calls = RtcSignal.Calls { 0L }; send(calls, "v", "s", sdp(500)); calls.failed("v", "the camera is busy")
        assertEquals(mapOf("fail" to "the camera is busy"), calls.answerPiece("v", "s", 0))
    }

    @Test fun `only a few viewers at once, and idle calls are closed`() {
        var now = 0L; val calls = RtcSignal.Calls { now }
        for (v in listOf("a", "b", "c")) assertTrue(send(calls, v, "s", sdp(300)) != null)
        try { send(calls, "d", "s", sdp(300)); fail("a fourth viewer should be refused") } catch (e: IllegalArgumentException) { assertTrue("3" in e.message!!) }
        now += RtcSignal.IDLE_MS + 1
        assertEquals(setOf("a", "b", "c"), calls.expired().toSet())                    // the caller closes these
        now += RtcSignal.IDLE_MS + 1
        assertEquals(emptySet<String>(), calls.active())                               // and anything long forgotten is dropped
        assertTrue(send(calls, "d", "s", sdp(300)) != null)                            // so there is room again
        assertNull(calls.offerPiece("e", RtcSignal.piece("s", RtcSignal.split(sdp(20_000)), 0)))   // a first piece of several is not yet an offer
    }

    @Test fun `a connected call is not dropped as idle, only an unfinished one`() {
        var now = 0L; val calls = RtcSignal.Calls { now }
        send(calls, "up", "s", sdp(300)); calls.answered("up", sdp(300))            // connected: nothing more is said over the mesh
        send(calls, "stuck", "s", sdp(300))                                         // never answered
        now += RtcSignal.IDLE_MS * 3
        assertEquals(emptyList<String>(), calls.expired().filter { it == "up" })
        assertEquals(setOf("up"), calls.active())                                   // the stuck one is forgotten, the connected one stays
        assertTrue(calls.bye("up"))
    }
}
