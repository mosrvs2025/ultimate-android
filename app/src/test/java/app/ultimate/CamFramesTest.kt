package app.ultimate

import app.ultimate.mesh.CamFrames
import app.ultimate.mesh.CamFrames.Step
import app.ultimate.mesh.Cap
import app.ultimate.mesh.DeviceProfile
import app.ultimate.mesh.Kind
import app.ultimate.mesh.MeshWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CamFramesTest {
    private fun pic(n: Int, seed: Long = 1) = ByteArray(n).also { Random(seed).nextBytes(it) }

    /** Plays the viewer against a device holding [latest], returning the picture it ends up with and how many requests it took. */
    private fun pull(latest: () -> Pair<Int, ByteArray>?, have: Int): Pair<Step, Int> {
        val asm = CamFrames.Assembler(); var part = 0; var seq = -1; var calls = 0
        while (calls < 50) {
            calls++
            when (val st = asm.accept(CamFrames.reply(latest(), have, part, seq))) {
                is Step.More -> { part = st.next; seq = st.seq }
                else -> return st to calls
            }
        }
        error("never finished")
    }

    @Test fun `a picture of any size comes back exactly, in pieces that fit the mesh line`() {
        for (size in listOf(1, 7_999, 8_000, 8_001, 23_456, 63_999)) {
            val jpeg = pic(size, size.toLong()); val l = CamFrames.Latest(); l.put(jpeg)
            val (st, calls) = pull({ l.get() }, have = 0)
            assertTrue("size $size: $st", st is Step.Done); assertArrayEquals(jpeg, (st as Step.Done).jpeg)
            assertEquals((size + CamFrames.CHUNK - 1) / CamFrames.CHUNK, calls)
        }
        // Every reply is well inside the mesh's line limit once it is wrapped as a message.
        val big = pic(CamFrames.CHUNK * 4); val r = CamFrames.reply(1 to big, 0, 0, -1)
        assertTrue("reply is ${r["data"]!!.length} chars", r["data"]!!.length + 200 < MeshWire.MAX_LINE)
    }

    @Test fun `nothing newer, nothing yet, or too big to send live`() {
        val l = CamFrames.Latest()
        assertEquals(Step.None, pull({ l.get() }, 0).first)
        val s = l.put(pic(100)); assertEquals(Step.Same, pull({ l.get() }, s).first)
        l.put(pic(100, 2)); assertTrue(pull({ l.get() }, s).first is Step.Done)
        val huge = CamFrames.reply(1 to pic(CamFrames.CHUNK * (CamFrames.MAX_PARTS + 1)), 0, 0, -1)
        assertEquals("1", huge["none"]); assertEquals("1", huge["big"])
    }

    @Test fun `if the picture is replaced halfway, the viewer starts again instead of mixing two pictures`() {
        val l = CamFrames.Latest(); val a = pic(20_000, 1); val b = pic(20_000, 2); l.put(a)
        val asm = CamFrames.Assembler()
        var st = asm.accept(CamFrames.reply(l.get(), 0, 0, -1)) as Step.More          // piece 0 of picture 1
        l.put(b)                                                                       // the camera moved on
        assertEquals(Step.Gone, asm.accept(CamFrames.reply(l.get(), 0, st.next, st.seq)))
        val (done, _) = pull({ l.get() }, 0); assertArrayEquals(b, (done as Step.Done).jpeg)
    }

    @Test fun `damaged or out of order replies never produce a wrong picture`() {
        val asm = CamFrames.Assembler(); val l = CamFrames.Latest(); l.put(pic(20_000))
        val first = CamFrames.reply(l.get(), 0, 0, -1); val second = CamFrames.reply(l.get(), 0, 1, 1)
        assertEquals(Step.Gone, asm.accept(second))                                    // a middle piece with no start
        asm.accept(first); assertEquals(Step.Gone, asm.accept(first.plus("part" to "2")))  // the wrong piece next
        assertEquals(Step.Gone, asm.accept(mapOf("seq" to "1", "parts" to "99", "part" to "0", "data" to "AAAA")))
        assertEquals(Step.Gone, asm.accept(mapOf("seq" to "1", "parts" to "2", "part" to "0", "data" to "!!not base64!!")))
        assertEquals(Step.Gone, asm.accept(emptyMap()))
    }

    @Test fun `the device says whether its camera is shared, and older versions read as not shared`() {
        val p = DeviceProfile("Pixel", Kind.Phone, setOf(Cap.Camera), lends = true, camOn = true)
        assertTrue(DeviceProfile.fromPayload(p.toPayload()).camOn); assertTrue("camon" in p.toPayload())
        assertFalse(DeviceProfile.fromPayload(p.copy(camOn = false).toPayload()).camOn)
        assertFalse(DeviceProfile.fromPayload(mapOf("model" to "Old", "kind" to "phone", "caps" to "cam", "lends" to "1")).camOn)
    }
}
