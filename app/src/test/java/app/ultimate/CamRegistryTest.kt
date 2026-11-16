package app.ultimate

import app.ultimate.send.CamRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CamRegistryTest {
    private var now = 1_000L
    private val reg = CamRegistry { now }
    private fun f(n: Int) = ByteArray(n)

    @Test fun `each device keeps only its newest picture`() {
        reg.put("a", "Laptop", f(1)); now += 100; reg.put("a", "Laptop", f(2)); reg.put("b", "Phone", f(3))
        assertEquals(listOf("a", "b"), reg.list().map { it.id })
        assertEquals(2, reg.get("a")!!.frame.size); assertEquals(2, reg.get("a")!!.frames)
    }

    @Test fun `a device that goes quiet drops off, and one that stops is gone at once`() {
        reg.put("a", "Laptop", f(1)); reg.put("b", "Phone", f(1))
        now += 5_000; assertEquals(2, reg.list().size)
        now += 1_500; assertTrue(reg.list().isEmpty()); assertNull(reg.get("a"))
        reg.put("c", "Tablet", f(1)); reg.stop("c"); assertTrue(reg.list().isEmpty())
    }

    @Test fun `there is a limit and the quietest goes first`() {
        for (i in 0 until CamRegistry.MAX) { reg.put("c$i", "Cam $i", f(1)); now += 10 }
        reg.put("c0", "Cam 0", f(1)); now += 10          // c0 speaks again, so c1 is now the quietest
        reg.put("new", "New", f(1))
        val ids = reg.list().map { it.id }
        assertEquals(CamRegistry.MAX, ids.size); assertTrue("new" in ids && "c0" in ids && "c1" !in ids)
    }

    @Test fun `ids and names are made safe`() {
        assertEquals("abc-12_x", CamRegistry.cleanId("abc-12_x"))
        assertEquals("abcpwn", CamRegistry.cleanId("../abc/<pwn>")); assertEquals("cam", CamRegistry.cleanId(null)); assertEquals("cam", CamRegistry.cleanId("///"))
        assertEquals(24, CamRegistry.cleanId("x".repeat(100)).length)
        assertEquals("Camera", CamRegistry.cleanName("\u0007 ")); assertEquals(40, CamRegistry.cleanName("n".repeat(99)).length)
    }
}
