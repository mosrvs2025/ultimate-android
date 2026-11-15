package app.ultimate

import app.ultimate.web.WebNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class WebNodesTest {
    private val iphone = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1"
    private val quest = "Mozilla/5.0 (X11; Linux x86_64; Quest 3) AppleWebKit/537.36 OculusBrowser/35.0 Chrome/128 VR Safari/537.36"

    @Test fun namesAndKindsFromTheBrowser() {
        assertEquals("iPhone", WebNodes.guessName(iphone)); assertEquals("phone", WebNodes.kind(iphone))
        assertEquals("Quest", WebNodes.guessName(quest)); assertEquals("headset", WebNodes.kind(quest))
        assertEquals("Windows PC", WebNodes.guessName("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130")); assertEquals("pc", WebNodes.kind("Windows NT"))
    }

    @Test fun aTypedNameWinsAndIdsSurviveReloads() {
        val w = WebNodes()
        val a = w.hello(null, "  Mo's laptop\u0007 ", iphone, 390, 844)
        assertEquals("Mo's laptop", a.name)
        val again = w.hello(a.id, "", iphone, 390, 844)
        assertEquals(a.id, again.id); assertEquals("iPhone", again.name); assertEquals(1, w.list().size)
        assertEquals(36 - 20, w.hello("../../etc", "x", iphone, 1, 1).id.length) // a bad id is replaced, not trusted
    }

    @Test fun somethingSentWakesAWaitingScreen() {
        val w = WebNodes(); val n = w.hello(null, "Mac", "Macintosh", 1440, 900)
        var got: List<String>? = null
        val t = thread { got = w.poll(n.id, 5_000) }
        Thread.sleep(150)
        assertTrue(w.send(n.id, """{"type":"link"}"""))
        t.join(2_000)
        assertEquals(listOf("""{"type":"link"}"""), got)
    }

    @Test fun waitingTimesOutEmptyAndUnknownScreensAreToldSo() {
        val w = WebNodes(); val n = w.hello(null, "", "Windows", 1, 1)
        val t0 = System.currentTimeMillis()
        assertEquals(emptyList<String>(), w.poll(n.id, 120))
        assertTrue(System.currentTimeMillis() - t0 >= 100)
        assertNull(w.poll("nope", 10)); assertFalse(w.send("nope", "{}"))
    }

    @Test fun quietScreensLeaveAndFilesExpire() {
        var now = 1_000L
        val w = WebNodes { now }
        val n = w.hello(null, "", iphone, 1, 1)
        val tok = w.keep("a.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        assertNotNull(w.blob(tok))
        now += WebNodes.GONE_MS + 1
        assertTrue(w.list().isEmpty()); assertFalse(w.send(n.id, "{}"))
        now += WebNodes.BLOB_MS
        assertNull(w.blob(tok))
    }
}
