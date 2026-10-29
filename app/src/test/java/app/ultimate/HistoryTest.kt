package app.ultimate

import app.ultimate.clips.ClipKind
import app.ultimate.clips.ClipRules
import app.ultimate.inbox.LogRules
import app.ultimate.inbox.dayLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    @Test fun notificationBodyPrefersMessagesThenBigText() {
        assertEquals("Ana: hi\nAna: you there?", LogRules.body("2 messages", "big", listOf("l1"), listOf("Ana: hi", "Ana: you there?")))
        assertEquals("the whole story", LogRules.body("the whole…", "the whole story", emptyList(), emptyList()))
        assertEquals("a\nb", LogRules.body("2 new", null, listOf("a", "b"), emptyList()))
        assertEquals("short", LogRules.body("short", null, emptyList(), emptyList()))
    }

    @Test fun noiseIsSkipped() {
        assertTrue(LogRules.skip("app.ultimate.plus", "app.ultimate.plus", false, "t", "b", emptySet()))
        assertTrue(LogRules.skip("x", "me", true, "Downloading", "40%", emptySet()))
        assertTrue(LogRules.skip("x", "me", false, "", "", emptySet()))
        assertTrue(LogRules.skip("bank", "me", false, "t", "b", setOf("bank")))
        assertFalse(LogRules.skip("com.whatsapp", "me", false, "Ana", "hi", emptySet()))
        assertEquals(LogRules.fingerprint("a", "t", "b"), LogRules.fingerprint("a", "t", "b"))
        assertNotEquals(LogRules.fingerprint("a", "t", "b"), LogRules.fingerprint("a", "t", "c"))
    }

    @Test fun dayHeaders() {
        val now = java.util.Calendar.getInstance().apply { set(2026, 9, 28, 12, 0) }.timeInMillis
        assertEquals("Today", dayLabel(now - 3_600_000L, now))
        assertEquals("Yesterday", dayLabel(now - 20 * 3_600_000L, now))
        assertFalse(dayLabel(now - 3 * 86_400_000L, now).startsWith("Yesterday"))
    }

    @Test fun clipKinds() {
        assertEquals(ClipKind.Link, ClipRules.kindOf("https://example.com/a?b=1"))
        assertEquals(ClipKind.Link, ClipRules.kindOf("www.example.com"))
        assertEquals(ClipKind.Email, ClipRules.kindOf("ana@mail.com"))
        assertEquals(ClipKind.Phone, ClipRules.kindOf("+1 (555) 123-4567"))
        assertEquals(ClipKind.Number, ClipRules.kindOf("1,234.50"))
        assertEquals(ClipKind.Code, ClipRules.kindOf("fun x() {\n  return 1\n}"))
        assertEquals(ClipKind.Text, ClipRules.kindOf("see you at 5"))
    }

    @Test fun clipsMoveUpInsteadOfDuplicatingAndPinsSurvive() {
        var list = ClipRules.add(emptyList(), "a", 1, "t")
        list = ClipRules.add(list, "b", 2, "t")
        list = ClipRules.add(list, "a", 3, "t")
        assertEquals(listOf("a", "b"), list.map { it.text })
        list = list.map { if (it.text == "b") it.copy(pinned = true) else it }
        repeat(10) { list = ClipRules.add(list, "x$it", 10L + it, "t", max = 4) }
        assertEquals(4, list.size)
        assertTrue(list.any { it.text == "b" && it.pinned })
        assertEquals(list, ClipRules.add(list, "   ", 99, "t"))
    }
}
