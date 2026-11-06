package app.ultimate.air

import java.util.Locale
import kotlin.math.sqrt

/**
 * What you can say to Air mode, read reliably without a model: opening an app by name, Back, Home, recent apps and
 * scrolling. Anything else goes to Needle (Local AI). Pure, so it is tested.
 */
object AirCommands {
    sealed interface Command {
        data class OpenApp(val label: String) : Command
        data object Back : Command
        data object Home : Command
        data object Recents : Command
        data class Scroll(val down: Boolean) : Command
    }

    /** Reads [said]; for "open …", picks the best match among [apps] (their names). Null when it isn't one of these. */
    fun parse(said: String, apps: List<String>): Command? {
        val t = said.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?')
        if (Regex("^(go )?back$").matches(t)) return Command.Back
        if (Regex("^(go )?home( screen)?$").matches(t)) return Command.Home
        if (Regex("^(show )?(recent|recents|recent apps|switch apps?)$").matches(t)) return Command.Recents
        Regex("^scroll (down|up)$").find(t)?.let { return Command.Scroll(it.groupValues[1] == "down") }
        val name = Regex("^(open|launch|start|go to)\\s+(the\\s+)?(.+?)(\\s+app)?$").find(t)?.groupValues?.get(3) ?: return null
        return bestApp(name, apps)?.let { Command.OpenApp(it) }
    }

    /** The app whose name matches [name] best: exact, then starts with, then contains; shorter names win ties. */
    fun bestApp(name: String, apps: List<String>): String? {
        val n = name.lowercase(Locale.ROOT).trim()
        if (n.isEmpty()) return null
        fun l(a: String) = a.lowercase(Locale.ROOT)
        return apps.firstOrNull { l(it) == n }
            ?: apps.filter { l(it).startsWith(n) }.minByOrNull { it.length }
            ?: apps.filter { n in l(it) }.minByOrNull { it.length }
            ?: apps.filter { l(it).replace(" ", "") == n.replace(" ", "") }.minByOrNull { it.length }
    }
}

/**
 * Where the phone is: propped up and still (Air mode makes sense) or in your hand (touch makes sense). Decided from
 * how much the phone's acceleration wobbles over the last second; a hand is never perfectly still, a table is.
 * It takes [settleMs] of stillness to go back to propped, so setting the phone down doesn't flicker. Pure, so it is tested.
 */
class PhoneContext(private val windowMs: Long = 1_000, private val heldWobble: Float = 0.35f, private val settleMs: Long = 1_500) {
    private val samples = ArrayDeque<Pair<Long, Float>>()
    var held = false; private set
    private var stillSince = 0L

    /** Feeds one accelerometer reading (its size, m/s²); returns true while the phone is in your hand. */
    fun feed(t: Long, magnitude: Float): Boolean {
        samples.addLast(t to magnitude)
        while (samples.isNotEmpty() && t - samples.first().first > windowMs) samples.removeFirst()
        if (samples.size < 5) return held
        val mean = samples.sumOf { it.second.toDouble() } / samples.size
        val sd = sqrt(samples.sumOf { (it.second - mean) * (it.second - mean) } / samples.size).toFloat()
        if (sd > heldWobble) { held = true; stillSince = 0 }
        else if (held) { if (stillSince == 0L) stillSince = t; if (t - stillSince >= settleMs) held = false }
        return held
    }
}
