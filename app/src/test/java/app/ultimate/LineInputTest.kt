package app.ultimate

import app.ultimate.terminal.LineInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineInputTest {
    private fun LineInput.type(s: String) = feed(s.toByteArray())

    @Test fun echoesWhatIsTypedAndFinishesOnEnter() {
        val l = LineInput(secret = false)
        assertEquals("ye", l.type("ye").echo)
        val done = l.type("s\r")
        assertEquals("s\r\n", done.echo)
        assertEquals("yes", done.line)
    }

    @Test fun passwordsShowNothing() {
        val l = LineInput(secret = true)
        assertEquals("", l.type("hunter2").echo)
        val done = l.type("\n")
        assertEquals("hunter2", done.line)
        assertEquals("\r\n", done.echo)
    }

    @Test fun backspaceRemovesTheLastCharacterOnScreenAndInTheLine() {
        val l = LineInput(false)
        l.type("noo")
        assertEquals("\b \b", l.type("\u007f").echo)
        assertEquals("no", l.type("\r").line)
        val p = LineInput(true); p.type("ab"); assertEquals("", p.type("\u007f").echo); assertEquals("a", p.type("\r").line)
        assertNull(LineInput(false).type("\u007f").line) // nothing to delete, nothing breaks
    }

    @Test fun controlCCancelsAndArrowKeysAreIgnored() {
        val l = LineInput(false)
        l.type("ab")
        assertEquals("", l.type("\u001b[A").echo)
        assertEquals("ab", l.type("\r").line)
        val c = LineInput(false).type("abc\u0003")
        assertTrue(c.cancelled); assertNull(c.line)
        assertFalse(LineInput(false).type("x").cancelled)
    }

    @Test fun lettersWithAccentsAndEmojiSurviveBackspace() {
        val l = LineInput(false)
        l.type("é😀")
        l.type("\u007f")
        assertEquals("é", l.type("\r").line)
    }
}
