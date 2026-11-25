package app.ultimate.launcher

import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.roundToInt

/** An app on the home screen: what to call it and how to open it. */
data class HomeApp(val pkg: String, val label: String, val installedAt: Long = 0L) {
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

    fun appsFor(apps: List<HomeApp>, letter: Char, sort: AppSort = AppSort.AZ): List<HomeApp> = sorted(apps.filter { it.letter == letter }, sort)

    /** [apps] in the chosen order; ties (and apps with no known install time) fall back to A to Z so the order is always steady. */
    fun sorted(apps: List<HomeApp>, sort: AppSort): List<HomeApp> {
        val byName = compareBy<HomeApp> { it.label.lowercase() }
        return when (sort) {
            AppSort.AZ -> apps.sortedWith(byName)
            AppSort.ZA -> apps.sortedWith(byName.reversed())
            AppSort.Newest -> apps.sortedWith(compareByDescending<HomeApp> { it.installedAt }.then(byName))
        }
    }

    /** The letter (as a fractional index, for smooth waves) under a finger at [y] on a rail spanning [top]..[top]+[height]. */
    fun position(y: Float, top: Float, height: Float, count: Int): Float {
        if (count <= 1 || height <= 0f) return 0f
        val step = height / (count - 1)
        return ((y - top) / step).coerceIn(0f, (count - 1).toFloat())
    }

    /** Apps the owner hasn't hidden. */
    fun visible(apps: List<HomeApp>, hidden: Set<String>): List<HomeApp> = if (hidden.isEmpty()) apps else apps.filter { it.pkg !in hidden }

    /** "21:05 · 16 November" or "9:05 PM · 16 November": the time in the chosen style, with the date if asked. [system24] is the phone's own preference. */
    fun clockLine(nowMs: Long, style: ClockStyle, showDate: Boolean, system24: Boolean, locale: java.util.Locale = java.util.Locale.getDefault(), tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val h24 = when (style) { ClockStyle.H24 -> true; ClockStyle.H12 -> false; ClockStyle.System -> system24 }
        val f = java.text.SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", locale).apply { timeZone = tz }
        val time = f.format(java.util.Date(nowMs))
        if (!showDate) return time
        return time + " · " + java.text.SimpleDateFormat("d MMMM", locale).apply { timeZone = tz }.format(java.util.Date(nowMs))
    }

    fun index(position: Float, count: Int): Int = position.roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))

    /**
     * Like [index], but stays on the letter it was on until the finger is clearly past it ([STICK] of the way to the next one
     * rather than halfway). A thumb sliding down a rail wobbles sideways and a little up and down; without this the letter
     * flickers back and forth on the boundary, buzzing and flashing the wrong app list. Pass the previous answer as [previous].
     */
    fun indexSticky(position: Float, previous: Int?, count: Int): Int {
        val raw = index(position, count)
        if (previous == null || previous !in 0 until count) return raw
        return if (kotlin.math.abs(position - previous) <= STICK) previous else raw
    }

    /** How far past a letter's centre (in letters) the finger must go before the rail moves on: a little more than half. */
    const val STICK = 0.7f

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
