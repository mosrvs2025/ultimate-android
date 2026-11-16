package app.ultimate.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.staticCompositionLocalOf

/** The two colours the launcher's glow, rail and highlights are drawn in. [Aurora] is the original teal and violet. */
enum class Accent(val label: String, val a: Color, val b: Color) {
    Aurora("Aurora", Color(0xFF3DDCD0), Color(0xFF9D8CFF)),
    Sunset("Sunset", Color(0xFFFFB347), Color(0xFFFF6F91)),
    Ocean("Ocean", Color(0xFF4FC3F7), Color(0xFF7C8CFF)),
    Meadow("Meadow", Color(0xFF7BE495), Color(0xFF3DDCD0)),
    Rose("Rose", Color(0xFFFF7EB3), Color(0xFFA394FF)),
    Ember("Ember", Color(0xFFFF8A65), Color(0xFFFFD54F)),
}

/** What is behind everything: the original soft blue-black glow, pure black (kind to OLED screens), or a lighter graphite. */
enum class Backdrop(val label: String, val colors: List<Color>) {
    Aurora("Aurora", listOf(Color(0xFF070A0F), Color(0xFF0E141C), Color(0xFF0A0E14))),
    Black("Black", listOf(Color(0xFF000000), Color(0xFF000000), Color(0xFF000000))),
    Graphite("Graphite", listOf(Color(0xFF14181F), Color(0xFF1B212B), Color(0xFF12161D))),
}

enum class ClockStyle(val label: String) { System("Phone's setting"), H24("24-hour"), H12("12-hour") }

/** Everything the owner can change about the launcher. Defaults are how it looked before settings existed. */
data class LauncherSettings(
    val accent: Accent = Accent.Aurora,
    val backdrop: Backdrop = Backdrop.Aurora,
    val clock: ClockStyle = ClockStyle.System,
    val showDate: Boolean = true,
    /** A tick under the thumb for each letter while sliding the rail. */
    val railTicks: Boolean = true,
    /** How many favourites are listed on the home screen (the rest stay favourites, just not shown). */
    val favouritesShown: Int = 5,
    /** The "Now" pods that offer an app or action for this moment. */
    val suggestions: Boolean = true,
    /** Apps kept out of the rail, the lists and search (they stay installed and can still be opened from elsewhere). */
    val hidden: Set<String> = emptySet(),
) {
    fun clamped() = copy(favouritesShown = favouritesShown.coerceIn(MIN_FAVS, MAX_FAVS))

    companion object { const val MIN_FAVS = 2; const val MAX_FAVS = 8 }
}

/** The theme in effect, read by everything the launcher draws. */
internal val LocalLauncherTheme = staticCompositionLocalOf { Accent.Aurora }

/** Settings to and from the plain strings kept in preferences, forgiving of anything unknown (a setting from a later version, a typo). */
object SettingsCodec {
    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E = enumValues<E>().firstOrNull { it.name == name } ?: default

    fun decode(accent: String?, backdrop: String?, clock: String?, showDate: Boolean, railTicks: Boolean, favs: Int, suggestions: Boolean, hidden: String?) = LauncherSettings(
        enumOr(accent, Accent.Aurora), enumOr(backdrop, Backdrop.Aurora), enumOr(clock, ClockStyle.System), showDate, railTicks, favs, suggestions,
        hidden.orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
    ).clamped()

    fun encodeHidden(hidden: Set<String>) = hidden.filter { it.isNotBlank() && ',' !in it }.sorted().joinToString(",")
}
