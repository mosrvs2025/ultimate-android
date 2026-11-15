package app.ultimate.launcher

import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.roundToInt

/** An app on the home screen: what to call it and how to open it. */
data class HomeApp(val pkg: String, val label: String) {
    val letter: Char get() = LauncherMath.letterOf(label)
}

/**
 * The arithmetic behind the home screen, apart from drawing so it can be tested: which letter an app files under, which letter a
 * finger is on, and the shape of the wave the letters make around the finger.
 */
object LauncherMath {
    /** Letters in the rail's order; '#' collects names that start with a digit or a symbol. */
    const val ORDER = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** "Éclair" files under E, "1Password" and "+Notes" under #. */
    fun letterOf(label: String): Char {
        val first = Normalizer.normalize(label.trim(), Normalizer.Form.NFD).firstOrNull { it.isLetterOrDigit() } ?: return '#'
        val c = first.uppercaseChar()
        return if (c in 'A'..'Z') c else '#'
    }

    /** Only the letters that have apps, in rail order, so the finger never lands on an empty letter. */
    fun letters(apps: List<HomeApp>): List<Char> {
        val used = apps.map { it.letter }.toSet()
        return ORDER.filter { it in used }.toList()
    }

    fun appsFor(apps: List<HomeApp>, letter: Char): List<HomeApp> = apps.filter { it.letter == letter }.sortedBy { it.label.lowercase() }

    /** The letter (as a fractional index, for smooth waves) under a finger at [y] on a rail spanning [top]..[top]+[height]. */
    fun position(y: Float, top: Float, height: Float, count: Int): Float {
        if (count <= 1 || height <= 0f) return 0f
        val step = height / (count - 1)
        return ((y - top) / step).coerceIn(0f, (count - 1).toFloat())
    }

    fun index(position: Float, count: Int): Int = position.roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))

    /**
     * How far letter [i] bulges out from the edge when the finger is at [at] (a fractional letter index): 1 under the finger,
     * falling off like a bell over about [spread] letters either side. Multiplied by the drag's strength, so it eases in and out.
     */
    fun bulge(i: Int, at: Float, spread: Float = 3.2f): Float {
        val d = (i - at) / spread
        return exp(-d * d)
    }

    /** Typing to search: every word typed must start a word of the name ("yt mu" finds "YT Music"), letters ignoring accents and case. */
    fun search(apps: List<HomeApp>, query: String): List<HomeApp> {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return apps.filter { a -> val parts = fold(a.label).split(' ', '-', '.', '_'); words.all { w -> parts.any { it.startsWith(w) } } || fold(a.label).replace(" ", "").startsWith(words.joinToString("")) }
            .sortedWith(compareBy({ !fold(it.label).startsWith(words.first()) }, { it.label.lowercase() }))
    }

    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

    /**
     * The favourites to show when none have been chosen yet: the apps used most over the last two weeks when Usage access is
     * allowed, otherwise the phone's own dialer, messages, camera and browser.
     */
    fun defaultFavourites(byUse: List<Pair<String, Long>>, defaults: List<String>, installed: Set<String>, self: String, max: Int = 5): List<String> {
        val used = byUse.filter { it.first in installed && it.first != self && it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        return (used + defaults.filter { it in installed }).distinct().take(max)
    }
}
