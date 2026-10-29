package app.ultimate

import app.ultimate.terminal.Bridge
import app.ultimate.terminal.Suggest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTest {
    @Test fun parsesWholeRequestsAndKeepsTheRest() {
        val one = "/tmp/r1\u001f/sdcard\u001fsqueeze\u001fmy video.mp4\u001f\u001e"
        val (reqs, rest) = Bridge.parse(one + "/tmp/r2\u001f/sd")
        assertEquals(1, reqs.size)
        assertEquals(Bridge.Request("/tmp/r1", "/sdcard", listOf("squeeze", "my video.mp4")), reqs[0])
        assertEquals("/tmp/r2\u001f/sd", rest)
        val (more, left) = Bridge.parse(rest + "\u001fbattery\u001f\u001e")
        assertEquals(listOf("battery"), more.single().args)
        assertEquals("", left)
    }

    @Test fun keepsNewlinesAndEmptyArguments() {
        val (reqs, _) = Bridge.parse("/r\u001f/\u001fclip\u001fset\u001fline one\nline two\u001f\u001f\u001e")
        assertEquals(listOf("clip", "set", "line one\nline two", ""), reqs.single().args)
    }

    @Test fun resolvesPathsAgainstTheShellsFolder() {
        assertEquals("/sdcard/DCIM/a.mp4", Bridge.resolve("/sdcard/DCIM", "a.mp4"))
        assertEquals("/sdcard/DCIM/a.mp4", Bridge.resolve("/sdcard/DCIM/", "a.mp4"))
        assertEquals("/data/x", Bridge.resolve("/sdcard", "/data/x"))
    }

    @Test fun startupFileDefinesEveryCommand() {
        val rc = Bridge.rc("/data/f/term/cmd.fifo")
        Bridge.COMMANDS.forEach { assertTrue(it, rc.contains("\n$it() { _ult $it ")) }
        assertTrue(rc.startsWith("export ULT_FIFO='/data/f/term/cmd.fifo'"))
        assertTrue(rc.contains("clip() {"))
    }

    @Test fun suggestsCommandsFromPlainEnglish() {
        assertEquals("storage", Suggest.english("how much free space do I have").first().command)
        assertTrue(Suggest.english("find my biggest videos").first().command.contains("-size +500M"))
        assertTrue(Suggest.english("where is invoice").any { it.command.contains("*invoice*") })
        assertEquals("battery", Suggest.english("Battery?").first().command)
        assertEquals("torch on", Suggest.english("turn the flashlight on").first().command)
        assertTrue(Suggest.english("").isEmpty())
        assertTrue(Suggest.english("zzzz qqq").isEmpty())
    }
}
