package app.ultimate.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Living looks (rules that change the look by themselves) and the colours picked out of a wallpaper. */
class LivingLookTest {
    private val night = When.Between(22 * 60, 6 * 60)
    private fun at(h: Int, m: Int = 0, focusing: Boolean = false, goal: Boolean = false) = Signals(h * 60 + m, focusing, goal)

    @Test fun aWindowThatCrossesMidnightCoversBothSides() {
        assertTrue(LivingLook.applies(night, at(23)))
        assertTrue(LivingLook.applies(night, at(0, 30)))
        assertTrue(LivingLook.applies(night, at(5, 59)))
        assertTrue("22:00 itself is inside", LivingLook.applies(night, at(22)))
        assertFalse("06:00 itself is outside", LivingLook.applies(night, at(6)))
        assertFalse(LivingLook.applies(night, at(12)))
        assertFalse(LivingLook.applies(night, at(21, 59)))
    }

    @Test fun aDaytimeWindowAndADegenerateOne() {
        val day = When.Between(7 * 60, 18 * 60)
        assertTrue(LivingLook.applies(day, at(7))); assertTrue(LivingLook.applies(day, at(17, 59)))
        assertFalse(LivingLook.applies(day, at(18))); assertFalse(LivingLook.applies(day, at(3)))
        assertFalse("from == to never applies (it would otherwise mean all day or none, which a rule shouldn't guess)", LivingLook.applies(When.Between(600, 600), at(10)))
    }

    @Test fun focusAndGoalFollowTheirSignals() {
        assertTrue(LivingLook.applies(When.Focusing, at(10, focusing = true))); assertFalse(LivingLook.applies(When.Focusing, at(10)))
        assertTrue(LivingLook.applies(When.GoalReached, at(10, goal = true))); assertFalse(LivingLook.applies(When.GoalReached, at(10)))
    }

    @Test fun theFirstRuleThatAppliesWins() {
        val rules = listOf(LookRule(When.Focusing, "terminal"), LookRule(night, "zen"), LookRule(When.GoalReached, "dusk"))
        assertEquals("terminal", LivingLook.active(rules, at(23, focusing = true, goal = true))?.look)
        assertEquals("zen", LivingLook.active(rules, at(23, goal = true))?.look)
        assertEquals("dusk", LivingLook.active(rules, at(12, goal = true))?.look)
        assertNull(LivingLook.active(rules, at(12)))
    }

    @Test fun aRuleWearsOnlyTheLooksPartsAndNothingPersonal() {
        val base = LauncherSettings(hidden = setOf("com.secret"), swipeUp = GestureAction.Notifications, dockLabels = false,
            rules = listOf(LookRule(night, "zen")))
        val living = LivingLook.effective(base, at(23))
        assertEquals(Looks.zen, Looks.matching(living.settings))
        assertEquals(night, living.rule?.whenever)
        assertEquals("apps you hid stay hidden", setOf("com.secret"), living.settings.hidden)
        assertEquals(GestureAction.Notifications, living.settings.swipeUp); assertFalse(living.settings.dockLabels)
        assertEquals("the base is untouched", Looks.aurora, Looks.matching(LivingLook.effective(base, at(12)).settings))
        assertNull(LivingLook.effective(base, at(12)).rule)
    }

    @Test fun aRuleCanWearALookOfYourOwnGivenAsACode() {
        val mine = LookCode.encode(Looks.terminal.apply(LauncherSettings()).copy(face = Face.Serif))
        val base = LauncherSettings(rules = listOf(LookRule(When.Focusing, mine)))
        val s = LivingLook.effective(base, at(10, focusing = true)).settings
        assertEquals(Face.Serif, s.face); assertEquals(Accent.Phosphor, s.accent)
        assertEquals("Your look", LivingLook.lookName(mine))
    }

    @Test fun aRuleWhoseLookIsUnknownIsSkippedAndTheNextOneIsUsed() {
        val rules = listOf(LookRule(When.Focusing, "no-such-look"), LookRule(When.Focusing, "paper"))
        assertEquals("paper", LivingLook.active(rules, at(10, focusing = true))?.look)
        assertEquals(Looks.aurora, Looks.matching(LivingLook.effective(LauncherSettings(rules = listOf(LookRule(When.Focusing, "nope"))), at(10, focusing = true)).settings))
    }

    @Test fun rulesSurviveStorageAndDamagedOnesAreDropped() {
        val rules = listOf(LookRule(night, "zen"), LookRule(When.Focusing, "terminal"), LookRule(When.GoalReached, LookCode.encode(LauncherSettings())))
        assertEquals(rules, LivingLook.decode(LivingLook.encode(rules)))
        assertEquals(emptyList<LookRule>(), LivingLook.decode(""))
        assertEquals(emptyList<LookRule>(), LivingLook.decode(null))
        val damaged = "t99999-5@zen\nt600-600@zen\nteatime@zen\nfocus@\nfocus\nfuture-kind@zen\nfocus@paper"
        assertEquals(listOf(LookRule(When.Focusing, "paper")), LivingLook.decode(damaged))
        assertEquals(LivingLook.MAX_RULES, LivingLook.decode((1..20).joinToString("\n") { "focus@zen" }).size)
    }

    @Test fun rulesAreStoredWithTheOtherSettingsAndAnOldInstallHasNone() {
        val s = LauncherSettings(rules = listOf(LookRule(night, "zen")))
        val stored = SettingsCodec.extras(s)
        assertEquals(s.rules, SettingsCodec.withExtras(LauncherSettings()) { stored[it] }.rules)
        assertEquals(emptyList<LookRule>(), SettingsCodec.withExtras(LauncherSettings()) { null }.rules)
        assertEquals("a stored value that isn't rules gives none, not a crash", emptyList<LookRule>(), SettingsCodec.withExtras(LauncherSettings()) { if (it == "rules") "garbage" else null }.rules)
    }

    @Test fun addingKeepsTheOrderAvoidsDuplicatesAndStopsAtTheLimit() {
        val a = LookRule(When.Focusing, "zen"); val b = LookRule(night, "paper")
        assertEquals(listOf(a, b), LivingLook.add(LivingLook.add(emptyList(), a), b))
        assertEquals(listOf(a), LivingLook.add(listOf(a), a))
        val full = (0 until LivingLook.MAX_RULES).map { LookRule(When.Between(it * 10, it * 10 + 5), "zen") }
        assertEquals(full, LivingLook.add(full, b))
        assertEquals(listOf(b, a), LivingLook.move(listOf(a, b), 1, -1))
        assertEquals(listOf(a, b), LivingLook.move(listOf(a, b), 0, -1))
        assertEquals(listOf(a, b), LivingLook.move(listOf(a, b), 1, 1))
    }

    @Test fun theWordsSayWhatIsHappening() {
        assertEquals("22:00 to 06:00", LivingLook.describe(night))
        assertEquals("Right now: Zen until 06:00.", LivingLook.status(LivingLook.effective(LauncherSettings(rules = listOf(LookRule(night, "zen"))), at(23))))
        assertNull(LivingLook.status(LivingLook.effective(LauncherSettings(), at(23))))
        assertTrue(LivingLook.suggestions.all { (label, r) -> label.isNotBlank() && LivingLook.resolve(r.look, LauncherSettings()) != null })
        assertTrue("every look has a name a rule can show", Looks.all.all { LivingLook.lookName(it.id) == it.name })
    }

    // ---- wallpaper colours

    private fun contrast(a: Color, b: Color): Double { val l1 = maxOf(a.luminance(), b.luminance()).toDouble(); val l2 = minOf(a.luminance(), b.luminance()).toDouble(); return (l1 + 0.05) / (l2 + 0.05) }

    @Test fun wallpaperAccentsAreReadableOnTheBackgroundWhateverTheHue() {
        val dark = Color(0xFF1B212B); val paper = Color(0xFFF1EADB)
        for (hue in 0 until 360 step 15) for (sat in listOf(0.2f, 0.6f, 1f)) for (l in listOf(0.1f, 0.5f, 0.9f)) {
            val argb = WallpaperPalette.color(hue.toFloat(), sat, l).toArgbInt()
            val onDark = WallpaperPalette.derive(listOf(argb), light = false)!!
            val onPaper = WallpaperPalette.derive(listOf(argb), light = true)!!
            assertTrue("hue $hue s $sat l $l on dark: ${contrast(onDark.a, dark)}", contrast(onDark.a, dark) >= 4.0 && contrast(onDark.b, dark) >= 4.0)
            assertTrue("hue $hue s $sat l $l on paper: ${contrast(onPaper.a, paper)}", contrast(onPaper.a, paper) >= 4.0 && contrast(onPaper.b, paper) >= 4.0)
        }
    }

    @Test fun theMostColourfulWallpaperColourLeadsAndTheSecondIsClearlyDifferent() {
        val teal = 0xFF1FA39A.toInt(); val grey = 0xFF808080.toInt(); val orange = 0xFFE8742C.toInt()
        val d = WallpaperPalette.derive(listOf(grey, teal, orange), light = false)!!
        val hueA = WallpaperPalette.hsl(d.a.toArgbInt()).h; val hueB = WallpaperPalette.hsl(d.b.toArgbInt()).h
        assertTrue("leads with a colourful one, not the grey: $hueA", hueA in 150f..190f || hueA in 10f..35f)
        val gap = Math.abs(hueA - hueB).let { minOf(it, 360 - it) }
        assertTrue("the pair is two clearly different hues: $gap", gap >= 30f)
        // One colour only: the second is the same colour turned a little, never identical.
        val one = WallpaperPalette.derive(listOf(teal), light = false)!!
        assertTrue(one.a != one.b)
    }

    @Test fun noColoursOrTransparentOnesGiveNothing() {
        assertNull(WallpaperPalette.derive(emptyList(), false))
        assertNull(WallpaperPalette.derive(listOf(0x00FF0000), false))
    }

    @Test fun theMirrorLookUsesTheWallpaperForBothBackgroundAndColours() {
        val m = Looks.mirror.apply(LauncherSettings())
        assertEquals(Accent.Wallpaper, m.accent); assertEquals(Backdrop.Wallpaper, m.backdrop)
        assertEquals(Looks.mirror, Looks.matching(m))
        assertNotNull(LookCode.apply(LookCode.encode(m), LauncherSettings()))
        assertEquals(Accent.Wallpaper, LookCode.apply(LookCode.encode(m), LauncherSettings())!!.accent)
    }

    private fun Color.toArgbInt(): Int = ((alpha * 255).toInt() shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()
}
