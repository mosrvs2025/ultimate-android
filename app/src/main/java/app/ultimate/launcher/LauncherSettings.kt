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

/**
 * What is behind everything: the original soft blue-black glow, pure black (kind to OLED screens), a lighter graphite, or the phone's own
 * wallpaper showing through a dark veil (so the white text stays readable on a bright picture).
 */
enum class Backdrop(val label: String, val colors: List<Color>) {
    Aurora("Aurora", listOf(Color(0xFF070A0F), Color(0xFF0E141C), Color(0xFF0A0E14))),
    Black("Black", listOf(Color(0xFF000000), Color(0xFF000000), Color(0xFF000000))),
    Graphite("Graphite", listOf(Color(0xFF14181F), Color(0xFF1B212B), Color(0xFF12161D))),
    Wallpaper("Phone's wallpaper", listOf(Color(0x80000000), Color(0x66000000), Color(0x99000000))),
}

/** How big app icons are drawn. [scale] multiplies the usual size, which is Medium. */
enum class IconSize(val label: String, val scale: Float) { Small("Small", 0.8f), Medium("Medium", 1f), Large("Large", 1.3f) }

/** How the apps inside a letter (and in search) are ordered. */
enum class AppSort(val label: String) { AZ("A to Z"), ZA("Z to A"), Newest("Newest first") }

/** What a gesture on an empty part of the home screen can do. */
enum class GestureAction(val label: String) {
    None("Nothing"), Search("Search apps"), Notifications("Open notifications"), QuickSettings("Open quick settings"),
    Ultimate("Open Ultimate"), SystemSettings("Open phone settings"), EditPanel("Edit home screen"),
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
    /** Favourites: how big their icons are, and whether each shows its name (off gives a row of icons only). */
    val dockIcons: IconSize = IconSize.Medium,
    val dockLabels: Boolean = true,
    /** The letter lists and search results: icon size, whether icons show at all, and the order. */
    val drawerIcons: IconSize = IconSize.Medium,
    val drawerShowIcons: Boolean = true,
    val drawerSort: AppSort = AppSort.AZ,
    val swipeUp: GestureAction = GestureAction.Search,
    val swipeDown: GestureAction = GestureAction.None,
    val doubleTap: GestureAction = GestureAction.None,
    val longPress: GestureAction = GestureAction.EditPanel,
    /** Search: list every app before anything is typed, and offer a web search when nothing matches. */
    val searchShowsAll: Boolean = true,
    val searchWeb: Boolean = false,
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

    /** Keys of the settings added after the first ones. All are plain strings, so a missing or odd one just means the default. */
    private val EXTRA_KEYS = listOf("dockIcons", "dockLabels", "drawerIcons", "drawerShowIcons", "drawerSort", "swipeUp", "swipeDown", "doubleTap", "longPress", "searchShowsAll", "searchWeb")

    /** [base] with the newer settings filled in from [get] (a lookup of the stored strings). */
    fun withExtras(base: LauncherSettings, get: (String) -> String?): LauncherSettings {
        fun bool(key: String, default: Boolean) = get(key)?.toBooleanStrictOrNull() ?: default
        return base.copy(
            dockIcons = enumOr(get("dockIcons"), base.dockIcons), dockLabels = bool("dockLabels", base.dockLabels),
            drawerIcons = enumOr(get("drawerIcons"), base.drawerIcons), drawerShowIcons = bool("drawerShowIcons", base.drawerShowIcons),
            drawerSort = enumOr(get("drawerSort"), base.drawerSort),
            swipeUp = enumOr(get("swipeUp"), base.swipeUp), swipeDown = enumOr(get("swipeDown"), base.swipeDown),
            doubleTap = enumOr(get("doubleTap"), base.doubleTap), longPress = enumOr(get("longPress"), base.longPress),
            searchShowsAll = bool("searchShowsAll", base.searchShowsAll), searchWeb = bool("searchWeb", base.searchWeb),
        )
    }

    /** The newer settings as the strings to store, under the keys [withExtras] reads. */
    fun extras(s: LauncherSettings): Map<String, String> = mapOf(
        "dockIcons" to s.dockIcons.name, "dockLabels" to s.dockLabels.toString(), "drawerIcons" to s.drawerIcons.name,
        "drawerShowIcons" to s.drawerShowIcons.toString(), "drawerSort" to s.drawerSort.name, "swipeUp" to s.swipeUp.name,
        "swipeDown" to s.swipeDown.name, "doubleTap" to s.doubleTap.name, "longPress" to s.longPress.name,
        "searchShowsAll" to s.searchShowsAll.toString(), "searchWeb" to s.searchWeb.toString(),
    ).also { check(it.keys.toList() == EXTRA_KEYS) }
}

/** The pages of the settings hub, in the order they are listed, with the words that find them when you type in the box on top. */
enum class SettingsPage(val title: String, val subtitle: String, val words: String) {
    Colours("Themes", "Colours of the glow, rail and highlights", "theme colour color accent aurora sunset ocean meadow rose ember look"),
    Wallpaper("Wallpaper", "Background behind the home screen", "wallpaper background black graphite glow picture photo"),
    Home("Home screen", "Clock, suggestions and the letter rail", "home clock date time suggestions now tick rail letters haptic favourites reset"),
    Dock("Favourites", "How many show, their size and names", "favourites favorites dock icons size labels names count"),
    Drawer("App list", "Order, icons and hidden apps", "app drawer list sort order icons hidden hide letters"),
    Gestures("Gestures", "What swipes, double tap and hold do", "gesture swipe up down double tap long press hold notifications"),
    Search("Search", "How searching for apps behaves", "search web find apps keyboard"),
    ;

    companion object {
        /** The pages that match [query]: every word typed must start a word of the title, subtitle or keywords. Blank matches all. */
        fun matching(query: String): List<SettingsPage> {
            val words = query.lowercase().split(' ', ',').filter { it.isNotBlank() }
            if (words.isEmpty()) return entries.toList()
            return entries.filter { p ->
                val have = (p.title + " " + p.subtitle + " " + p.words).lowercase().split(' ', ',', '.')
                words.all { w -> have.any { it.startsWith(w) } }
            }
        }
    }
}
