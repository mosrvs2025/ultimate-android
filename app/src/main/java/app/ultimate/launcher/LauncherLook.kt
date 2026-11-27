package app.ultimate.launcher

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import app.ultimate.core.Liquid

/**
 * A launcher "look" is not one setting but a handful of parts that can be mixed: the colours ([Accent] and [Backdrop]), the typeface,
 * how the clock is laid out, how the favourites are laid out, what the rail looks like and the shape icons are cut to. A [LookPreset]
 * sets all of them at once (Terminal, Paper, Zen...); any part can still be changed on its own afterwards, which makes a look of your own.
 */
enum class Face(val label: String, val family: FontFamily) {
    Default("Default", FontFamily.Default), Clean("Clean", FontFamily.SansSerif), Serif("Serif", FontFamily.Serif), Mono("Mono", FontFamily.Monospace),
}

enum class ClockLayout(val label: String) { Day("Big day"), Digital("Digital"), Terminal("Terminal"), Editorial("Editorial"), Hidden("None") }

enum class FavLayout(val label: String) { List("List"), Numbered("Numbered"), Grid("Grid"), Pills("Pills"), Centered("Centered") }

/** The letter rail down the right edge: the liquid one that swells round the thumb, plain letters, or nothing until it is touched. */
enum class RailStyle(val label: String) { Liquid("Liquid"), Plain("Plain letters"), Hidden("Hidden") }

enum class IconShape(val label: String, val shape: Shape) {
    AsIs("As they are", RectangleShape), Circle("Circle", CircleShape), Squircle("Squircle", RoundedCornerShape(30)),
}

/**
 * What everything the launcher draws reads, so that a light look can have dark text and a mono look mono type: the main and quiet
 * text colours, whether the background is [light], the search panel's colour, the typeface, the rail style and the icon shape.
 */
@Immutable
data class LookTokens(
    val fg: Color, val dim: Color, val light: Boolean, val sheet: Color, val face: FontFamily, val rail: RailStyle, val iconShape: Shape,
) {
    companion object { val Default = LookTokens(Color.White, Liquid.dim, false, Color(0xF00A0E14), FontFamily.Default, RailStyle.Liquid, RectangleShape) }
}

/**
 * The same look as drawn on the dark panel search slides up in: a light look has dark text, which would vanish on that panel, so its text
 * becomes the paper colour (a dark look is already right).
 */
fun LookTokens.onSheet(): LookTokens = if (light) copy(fg = Color(0xFFF7F2E8), dim = Color(0xFFB9B1A0), light = false) else this

fun LauncherSettings.tokens() = LookTokens(backdrop.fg, backdrop.dim, backdrop.light, backdrop.sheet, face.family, rail, iconShape.shape)

internal val LocalLook = staticCompositionLocalOf { LookTokens.Default }

/** A ready-made look: a name, a line about it, and what it sets. Only the look's own parts are compared to tell which one is in use. */
class LookPreset(val id: String, val name: String, val blurb: String, val apply: (LauncherSettings) -> LauncherSettings)

object Looks {
    private fun LauncherSettings.parts(accent: Accent, backdrop: Backdrop, face: Face, clock: ClockLayout, fav: FavLayout, rail: RailStyle, icons: IconShape) =
        copy(accent = accent, backdrop = backdrop, face = face, clockLayout = clock, favLayout = fav, rail = rail, iconShape = icons)

    val aurora = LookPreset("aurora", "Aurora", "The original: a soft glow and a liquid rail that swells round your thumb") {
        it.parts(Accent.Aurora, Backdrop.Aurora, Face.Default, ClockLayout.Day, FavLayout.List, RailStyle.Liquid, IconShape.AsIs).copy(drawerShowIcons = true, favouritesShown = 5)
    }
    val terminal = LookPreset("terminal", "Terminal", "Green on black, monospace, numbered favourites and a prompt for a clock") {
        it.parts(Accent.Phosphor, Backdrop.Terminal, Face.Mono, ClockLayout.Terminal, FavLayout.Numbered, RailStyle.Plain, IconShape.AsIs).copy(drawerShowIcons = false, favouritesShown = 6)
    }
    val paper = LookPreset("paper", "Paper", "Warm paper, ink and a serif: a quiet page with the date in large type") {
        it.parts(Accent.Ink, Backdrop.Paper, Face.Serif, ClockLayout.Editorial, FavLayout.List, RailStyle.Plain, IconShape.AsIs).copy(drawerShowIcons = true, favouritesShown = 5)
    }
    val zen = LookPreset("zen", "Zen", "Black, a big thin clock, three favourites in the middle and a rail that hides until touched") {
        it.parts(Accent.Mono, Backdrop.Black, Face.Clean, ClockLayout.Digital, FavLayout.Centered, RailStyle.Hidden, IconShape.Circle).copy(drawerShowIcons = true, favouritesShown = 3)
    }
    val tiles = LookPreset("tiles", "Tiles", "A grid of favourites as rounded tiles on deep blue, the way most launchers look") {
        it.parts(Accent.Ocean, Backdrop.Midnight, Face.Clean, ClockLayout.Digital, FavLayout.Grid, RailStyle.Liquid, IconShape.Squircle).copy(drawerShowIcons = true, favouritesShown = 8)
    }
    val dusk = LookPreset("dusk", "Dusk", "Plum and sunset, favourites as soft pills and round icons") {
        it.parts(Accent.Sunset, Backdrop.Dusk, Face.Clean, ClockLayout.Day, FavLayout.Pills, RailStyle.Liquid, IconShape.Circle).copy(drawerShowIcons = true, favouritesShown = 5)
    }

    val all = listOf(aurora, terminal, paper, zen, tiles, dusk)

    private fun LauncherSettings.sameLook(o: LauncherSettings) =
        accent == o.accent && backdrop == o.backdrop && face == o.face && clockLayout == o.clockLayout && favLayout == o.favLayout && rail == o.rail && iconShape == o.iconShape

    /** The preset [s] is exactly set to (in its look parts), or null when it is a mix of your own. */
    fun matching(s: LauncherSettings): LookPreset? = all.firstOrNull { it.apply(s).sameLook(s) }
}

/**
 * A look as a short line of text, to paste to another phone or a friend: `ULOOK1:Phosphor|Terminal|Mono|Terminal|Numbered|Plain|AsIs|6|false`.
 * It carries only the look's parts (plus how many favourites show and whether the lists show icons), never apps or anything personal.
 * Reading is forgiving: a name a newer version added that this one doesn't know falls back to the default for that part.
 */
object LookCode {
    private const val PREFIX = "ULOOK1:"
    private const val PARTS = 9

    fun encode(s: LauncherSettings): String =
        PREFIX + listOf(s.accent.name, s.backdrop.name, s.face.name, s.clockLayout.name, s.favLayout.name, s.rail.name, s.iconShape.name, s.favouritesShown.toString(), s.drawerShowIcons.toString()).joinToString("|")

    private inline fun <reified E : Enum<E>> pick(name: String, default: E): E = enumValues<E>().firstOrNull { it.name == name } ?: default

    /** [base] with the look in [code] applied, or null if [code] isn't a look code at all. */
    fun apply(code: String, base: LauncherSettings): LauncherSettings? {
        val text = code.trim()
        if (!text.startsWith(PREFIX)) return null
        val p = text.removePrefix(PREFIX).split('|')
        if (p.size != PARTS) return null
        val favs = p[7].toIntOrNull() ?: return null
        return base.copy(
            accent = pick(p[0], base.accent), backdrop = pick(p[1], base.backdrop), face = pick(p[2], base.face), clockLayout = pick(p[3], base.clockLayout),
            favLayout = pick(p[4], base.favLayout), rail = pick(p[5], base.rail), iconShape = pick(p[6], base.iconShape),
            favouritesShown = favs, drawerShowIcons = p[8].toBooleanStrictOrNull() ?: base.drawerShowIcons,
        ).clamped()
    }
}
