package app.ultimate.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The two colours the launcher draws its glow, rail and highlights in. A fixed [Accent], or colours picked out of the wallpaper. */
interface Palette { val a: Color; val b: Color }

data class Duo(override val a: Color, override val b: Color) : Palette

/**
 * Picks two accent colours out of the colours the phone found in the wallpaper, and makes them readable: the wallpaper's own colours are
 * often dark or washed out, so they are moved lighter (on a dark background) or darker (on a light one) until they stand out clearly,
 * keeping their hue. Plain arithmetic on colour numbers, so it is tested without a phone.
 */
object WallpaperPalette {
    /** Backgrounds the accents are made readable against (the lightest of the dark looks, and Paper). */
    private const val DARK_BG = 0xFF1B212B.toInt()
    private const val LIGHT_BG = 0xFFF1EADB.toInt()
    const val MIN_CONTRAST = 4.5

    class Hsl(val h: Float, val s: Float, val l: Float)

    fun hsl(argb: Int): Hsl {
        val r = ((argb shr 16) and 0xFF) / 255f; val g = ((argb shr 8) and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val l = (mx + mn) / 2f; val d = mx - mn
        if (d < 1e-6f) return Hsl(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (mx) { r -> ((g - b) / d).mod(6f); g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f
        return Hsl(h, s.coerceIn(0f, 1f), l)
    }

    fun color(h: Float, s: Float, l: Float): Color {
        val c = (1f - abs(2f * l - 1f)) * s; val hh = (h.mod(360f)) / 60f; val x = c * (1f - abs(hh.mod(2f) - 1f)); val m = l - c / 2f
        val (r, g, b) = when (hh.toInt()) { 0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
        return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
    }

    private fun contrast(a: Color, b: Color): Double { val l1 = max(a.luminance(), b.luminance()).toDouble(); val l2 = min(a.luminance(), b.luminance()).toDouble(); return (l1 + 0.05) / (l2 + 0.05) }

    /** [h] and [s] made readable on a dark (or, if [light], a pale) background: lightness moves until the contrast is enough. */
    fun readable(h: Float, s: Float, light: Boolean): Color {
        val bg = Color(if (light) LIGHT_BG else DARK_BG)
        var l = if (light) 0.34f else 0.66f
        var c = color(h, s, l)
        var guard = 0
        while (contrast(c, bg) < MIN_CONTRAST && guard++ < 40) { l = if (light) l - 0.015f else l + 0.015f; c = color(h, s, l.coerceIn(0.05f, 0.95f)) }
        return c
    }

    private fun hueGap(a: Float, b: Float): Float { val d = abs(a - b) % 360f; return min(d, 360f - d) }

    /** Two accents from the wallpaper's colours ([argb], most important first), or null when it has none. */
    fun derive(argb: List<Int>, light: Boolean): Duo? {
        val cs = argb.filter { (it ushr 24) > 0x80 }.map { hsl(it) }
        if (cs.isEmpty()) return null
        // The most colourful one leads; the second is a colour clearly apart in hue, or the same colour turned a little.
        val first = cs.maxBy { it.s * (1f - abs(it.l - 0.5f)) }
        val second = cs.filter { hueGap(it.h, first.h) >= 40f }.maxByOrNull { it.s } ?: Hsl(first.h + 38f, first.s, first.l)
        fun lift(c: Hsl) = readable(c.h, if (c.s < 0.12f) c.s else c.s.coerceIn(0.5f, 0.95f), light)
        return Duo(lift(first), lift(second))
    }
}

/**
 * The wallpaper's colours as the launcher sees them, kept as Compose state so everything drawn in [Accent.Wallpaper] updates the moment
 * the wallpaper (or a look's background) changes. Until the phone has said anything it is the original teal and violet.
 */
object WallpaperInk {
    private var raw by mutableStateOf<List<Int>>(emptyList())
    private var light by mutableStateOf(false)
    val duo: Duo get() = WallpaperPalette.derive(raw, light) ?: Duo(Accent.Aurora.a, Accent.Aurora.b)
    fun set(argb: List<Int>) { if (argb != raw) raw = argb }
    /** Whether the look's background is light, so the colours are drawn dark enough for it. */
    fun backdropLight(isLight: Boolean) { if (isLight != light) light = isLight }
}
