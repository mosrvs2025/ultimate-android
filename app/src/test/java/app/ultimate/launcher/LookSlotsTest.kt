package app.ultimate.launcher

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved looks, their QR codes, and a rule that wears a saved look. */
class LookSlotsTest {
    private val terminal = LookCode.encode(Looks.terminal.apply(LauncherSettings()))
    private val paper = LookCode.encode(Looks.paper.apply(LauncherSettings()))

    @Test fun slotsSurviveStorageInOrder() {
        val slots = listOf(LookSlot("Night", terminal), LookSlot("Day", paper))
        assertEquals(slots, LookSlots.decode(LookSlots.encode(slots)))
        assertEquals(emptyList<LookSlot>(), LookSlots.decode(""))
        assertEquals(emptyList<LookSlot>(), LookSlots.decode(null))
    }

    @Test fun damagedSlotsAreDroppedAndTheRestKept() {
        val text = "no tab here\n\t$terminal\nBroken\tULOOK1:nope\nGood\t$paper\nAlso broken\tnot a look at all"
        assertEquals(listOf(LookSlot("Good", paper)), LookSlots.decode(text))
        assertEquals(LookSlots.MAX, LookSlots.decode((1..20).joinToString("\n") { "L$it\t$terminal" }).size)
    }

    @Test fun namesAreCleanedAndShortened() {
        assertEquals("a b c", LookSlots.cleanName("  a\tb\nc  "))
        assertEquals(LookSlots.MAX_NAME, LookSlots.cleanName("x".repeat(80)).length)
        // A tab or newline in a name must not be able to break the stored lines.
        val stored = LookSlots.encode(listOf(LookSlot("bad\tname\nhere", terminal)))
        assertEquals(1, stored.lines().size); assertEquals("bad name here", LookSlots.decode(stored).single().name)
    }

    @Test fun savingAddsReplacesBySameNameAndStopsWhenFull() {
        val one = (LookSlots.save(emptyList(), "Night", terminal) as LookSlots.Saved.Ok).slots
        assertEquals(listOf(LookSlot("Night", terminal)), one)
        val replaced = (LookSlots.save(one, "night", paper) as LookSlots.Saved.Ok).slots
        assertEquals("the same name (any case) replaces, not duplicates", listOf(LookSlot("night", paper)), replaced)
        assertEquals(LookSlots.Saved.NoName, LookSlots.save(one, "   ", terminal))
        val full = (1..LookSlots.MAX).map { LookSlot("L$it", terminal) }
        assertEquals(LookSlots.Saved.Full, LookSlots.save(full, "One more", paper))
        assertTrue("a full list can still replace one", LookSlots.save(full, "l3", paper) is LookSlots.Saved.Ok)
    }

    @Test fun deletingRemovesOnlyThatOne() {
        val s = listOf(LookSlot("A", terminal), LookSlot("B", paper), LookSlot("C", terminal))
        assertEquals(listOf(s[0], s[2]), LookSlots.delete(s, 1))
        assertEquals(s, LookSlots.delete(s, 9))
    }

    @Test fun theSuggestedNameIsTheLooksNameAndNeverTaken() {
        assertEquals("Aurora", LookSlots.suggestName(LauncherSettings(), emptyList()))
        assertEquals("Terminal", LookSlots.suggestName(Looks.terminal.apply(LauncherSettings()), emptyList()))
        assertEquals("Terminal 2", LookSlots.suggestName(Looks.terminal.apply(LauncherSettings()), listOf(LookSlot("terminal", terminal))))
        assertEquals("Terminal 3", LookSlots.suggestName(Looks.terminal.apply(LauncherSettings()), listOf(LookSlot("Terminal", terminal), LookSlot("Terminal 2", terminal))))
        assertEquals("My look", LookSlots.suggestName(Looks.terminal.apply(LauncherSettings()).copy(face = Face.Serif), emptyList()))
    }

    @Test fun slotsAreStoredWithTheSettingsAndAnOldInstallHasNone() {
        val s = LauncherSettings(slots = listOf(LookSlot("Night", terminal)))
        val stored = SettingsCodec.extras(s)
        assertEquals(s.slots, SettingsCodec.withExtras(LauncherSettings()) { stored[it] }.slots)
        assertEquals(emptyList<LookSlot>(), SettingsCodec.withExtras(LauncherSettings()) { null }.slots)
    }

    @Test fun aRuleCanWearASavedLookAndTheNameShows() {
        val slots = listOf(LookSlot("Night", terminal))
        val base = LauncherSettings(slots = slots, rules = listOf(LookRule(When.Focusing, terminal)))
        val living = LivingLook.effective(base, Signals(600, focusing = true))
        assertEquals(Looks.terminal, Looks.matching(living.settings))
        assertEquals("Night", LivingLook.lookName(terminal, slots))
        assertEquals("Your look", LivingLook.lookName(terminal))
        assertEquals("the saved looks stay when a rule is wearing", slots, living.settings.slots)
        assertEquals("Right now: Night while you focus.", LivingLook.status(living))
    }

    // ---- QR

    @Test fun aLookSurvivesBeingDrawnAsAQrAndReadBack() {
        for (code in listOf(terminal, paper, LookCode.encode(LauncherSettings(accent = Accent.Wallpaper, rules = emptyList())))) {
            val (px, w, h) = LookQr.pixels(code)!!
            assertEquals(code, LookQr.decode(px, w, h))
        }
    }

    @Test fun aQrOfAnythingElseIsNotALook() {
        val m = QRCodeWriter().encode("https://example.com/not-a-look", BarcodeFormat.QR_CODE, 300, 300)
        val px = IntArray(300 * 300) { i -> if (m[i % 300, i / 300]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertNull(LookQr.decode(px, 300, 300))
        assertNull("a blank picture has no QR", LookQr.decode(IntArray(200 * 200) { 0xFFFFFFFF.toInt() }, 200, 200))
        assertNotNull(LookQr.cells(terminal))
    }

    @Test fun aLookQrIsSmallEnoughToScanFromAScreen() {
        val cells = LookQr.cells(terminal)!!
        assertTrue("version <= 5 (37 modules) so each cell is big on a phone screen: ${cells.size}", cells.size <= 41)
    }
}
