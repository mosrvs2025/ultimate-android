package app.ultimate.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings sheet is dark whatever look is worn, so a dark accent (Ink, for the light Paper look) must be lightened to stay readable on it. */
class SettingsInkTest {
    private val sheet = Color(0xFF080B10)
    private fun contrast(a: Color, b: Color): Double { val l1 = maxOf(a.luminance(), b.luminance()).toDouble(); val l2 = minOf(a.luminance(), b.luminance()).toDouble(); return (l1 + 0.05) / (l2 + 0.05) }

    @Test fun everyFixedAccentReadsOnTheDarkSheet() {
        Accent.entries.filter { it != Accent.Wallpaper }.forEach { ac ->
            assertTrue("${ac.label} a: ${contrast(readableOnDark(ac.a), sheet)}", contrast(readableOnDark(ac.a), sheet) >= 4.5)
            assertTrue("${ac.label} b: ${contrast(readableOnDark(ac.b), sheet)}", contrast(readableOnDark(ac.b), sheet) >= 4.5)
        }
    }

    @Test fun inkWasTheProblemAndBrightAccentsAreLeftAlone() {
        assertTrue("Ink on the dark sheet was unreadable before: ${contrast(Accent.Ink.a, sheet)}", contrast(Accent.Ink.a, sheet) < 2.0)
        listOf(Accent.Aurora, Accent.Phosphor, Accent.Amber, Accent.Mono).forEach { assertEquals(it.label, it.a, readableOnDark(it.a)) }
    }

    @Test fun theSettingsInkFollowsTheWornAccent() {
        assertEquals(readableOnDark(Accent.Ink.a), LauncherSettings(accent = Accent.Ink).ink)
        assertEquals(Accent.Aurora.a, LauncherSettings().ink)
    }
}
