package app.ultimate.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.random.Random

/**
 * The settings studio's sums, apart from drawing. Every settings page edits a draft that is shown in a live preview (and can be peeked at
 * on the real home screen); nothing changes on the phone until Apply. A draft is a whole [LauncherSettings] but for two things that act at
 * once and so always come from the real settings: the living-look rules and the saved looks.
 */
object LookStudio {
    /** [draft] as it would be worn on top of [base]: everything from the draft except the rules and saved looks, which stay [base]'s. */
    fun commit(draft: LauncherSettings, base: LauncherSettings): LauncherSettings = draft.copy(rules = base.rules, slots = base.slots).clamped()

    /** Whether [draft] would change anything on [base]. */
    fun pending(draft: LauncherSettings?, base: LauncherSettings): Boolean = draft != null && commit(draft, base) != base

    /** What to call a look: a preset's name, or "Your own mix". */
    fun name(s: LauncherSettings): String = Looks.matching(s)?.name ?: "Your own mix"

    private fun contrast(a: Color, b: Color): Double {
        val l1 = maxOf(a.luminance(), b.luminance()).toDouble(); val l2 = minOf(a.luminance(), b.luminance()).toDouble()
        return (l1 + 0.05) / (l2 + 0.05)
    }

    /** The accents that can be read on [backdrop] (both colours at 3:1 or better). The wallpaper's own pair is always allowed: it is made readable on its own. */
    fun readableAccents(backdrop: Backdrop): List<Accent> {
        val bg = backdrop.colors[1].let { if (it.alpha < 1f) Color(0xFF0A0E14) else it }
        return Accent.entries.filter { it == Accent.Wallpaper || (contrast(it.a, bg) >= 3.0 && contrast(it.b, bg) >= 3.0) }
    }

    /**
     * A surprise: every part of the look picked at random, but only combinations that can be read (a light background never gets a pale
     * accent), and never the same look it started from. Given the same [random] it gives the same answer, so it is tested.
     */
    fun shuffle(base: LauncherSettings, random: Random): LauncherSettings {
        val before = LookCode.encode(base)
        repeat(12) {
            val backdrop = Backdrop.entries.random(random)
            val accents = readableAccents(backdrop).let { list -> if (backdrop == Backdrop.Wallpaper) list else list.filter { it != Accent.Wallpaper } }
            val style = if (random.nextInt(100) < 45) HomeStyle.Lists else HomeStyle.entries.filter { it != HomeStyle.Lists }.random(random)
            val s = base.copy(
                backdrop = backdrop, accent = accents.random(random), face = Face.entries.random(random),
                clockLayout = ClockLayout.entries.filter { it != ClockLayout.Hidden }.random(random),
                favLayout = FavLayout.entries.random(random),
                rail = if (style == HomeStyle.Lists) RailStyle.entries.random(random) else RailStyle.Hidden,
                iconShape = IconShape.entries.random(random), homeStyle = style,
            ).clamped()
            if (LookCode.encode(s) != before) return s
        }
        return base
    }
}
