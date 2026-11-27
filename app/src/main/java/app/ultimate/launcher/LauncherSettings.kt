package app.ultimate.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.staticCompositionLocalOf
import app.ultimate.core.Liquid

/** The two colours the launcher's glow, rail and highlights are drawn in. [Aurora] is the original teal and violet. */
enum class Accent(val label: String, a: Color, b: Color) : Palette {
    Aurora("Aurora", Color(0xFF3DDCD0), Color(0xFF9D8CFF)),
    Sunset("Sunset", Color(0xFFFFB347), Color(0xFFFF6F91)),
    Ocean("Ocean", Color(0xFF4FC3F7), Color(0xFF7C8CFF)),
    Meadow("Meadow", Color(0xFF7BE495), Color(0xFF3DDCD0)),
    Rose("Rose", Color(0xFFFF7EB3), Color(0xFFA394FF)),
    Ember("Ember", Color(0xFFFF8A65), Color(0xFFFFD54F)),
    /** Terminal green and a warm amber, a pale mono pair and a dark brown ink (for light backgrounds). */
    Phosphor("Phosphor", Color(0xFF39FF88), Color(0xFF00C46A)),
    Amber("Amber", Color(0xFFFFB000), Color(0xFFFF7A00)),
    Mono("Mono", Color(0xFFF2F2F2), Color(0xFF9A9A9A)),
    Ink("Ink", Color(0xFF3B352B), Color(0xFF8B5E3C)),
    /** Two colours picked out of the phone's wallpaper (see [WallpaperPalette]); they follow the wallpaper when it changes. */
    Wallpaper("From wallpaper", Color.Unspecified, Color.Unspecified) {
        override val a: Color get() = WallpaperInk.duo.a
        override val b: Color get() = WallpaperInk.duo.b
    };

    override val a: Color = a
    override val b: Color = b
}

/**
 * What is behind everything, and the colours the text on it is drawn in: the original soft blue-black glow, pure black (kind to OLED
 * screens), a lighter graphite, deeper colours, a light paper, or the phone's own wallpaper showing through a dark veil (so the white
 * text stays readable on a bright picture). [fg] is the main text, [dim] the quieter text, [light] says the background is light (so the
 * status bar icons turn dark), and [sheet] is the dark panel search slides up in. Every one but the wallpaper is fully opaque.
 */
enum class Backdrop(
    val label: String, val colors: List<Color>,
    val fg: Color = Color.White, val dim: Color = Liquid.dim, val light: Boolean = false, val sheet: Color = Color(0xF00A0E14),
) {
    Aurora("Aurora", listOf(Color(0xFF070A0F), Color(0xFF0E141C), Color(0xFF0A0E14))),
    Black("Black", listOf(Color(0xFF000000), Color(0xFF000000), Color(0xFF000000))),
    Graphite("Graphite", listOf(Color(0xFF14181F), Color(0xFF1B212B), Color(0xFF12161D))),
    Midnight("Midnight", listOf(Color(0xFF050B1E), Color(0xFF0B1838), Color(0xFF06102A)), dim = Color(0xFF8FA3C8), sheet = Color(0xF0060D22)),
    Dusk("Dusk", listOf(Color(0xFF1A0F2E), Color(0xFF3A1B4A), Color(0xFF4A1F3E)), dim = Color(0xFFC3A9D6), sheet = Color(0xF01A0F2E)),
    Terminal("Terminal", listOf(Color(0xFF020603), Color(0xFF000000), Color(0xFF010402)), fg = Color(0xFF8CFFB0), dim = Color(0xFF3F9462), sheet = Color(0xF0000000)),
    Paper("Paper", listOf(Color(0xFFF7F2E8), Color(0xFFF1EADB), Color(0xFFF5EFE3)), fg = Color(0xFF1E1B16), dim = Color(0xFF6B655A), light = true, sheet = Color(0xF01E1B16)),
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
    /** The look, apart from its colours: the typeface, how the clock and the favourites are laid out, the rail, and the shape of icons. */
    val face: Face = Face.Default,
    val clockLayout: ClockLayout = ClockLayout.Day,
    val favLayout: FavLayout = FavLayout.List,
    val rail: RailStyle = RailStyle.Liquid,
    val iconShape: IconShape = IconShape.AsIs,
    /** Living looks: rules that borrow another look at certain times (see [LivingLook]). Checked top to bottom. */
    val rules: List<LookRule> = emptyList(),
    /** Cards on the home screen that show something from Ultimate (see [HomeCards]). None until switched on. */
    val cards: Set<HomeCard> = emptySet(),
    /** Looks saved under a name (see [LookSlots]). */
    val slots: List<LookSlot> = emptyList(),
) {
    fun clamped() = copy(favouritesShown = favouritesShown.coerceIn(MIN_FAVS, MAX_FAVS))

    companion object { const val MIN_FAVS = 2; const val MAX_FAVS = 8 }
}

/** The theme in effect, read by everything the launcher draws. */
internal val LocalLauncherTheme = staticCompositionLocalOf<Palette> { Accent.Aurora }

/** Settings to and from the plain strings kept in preferences, forgiving of anything unknown (a setting from a later version, a typo). */
object SettingsCodec {
    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E = enumValues<E>().firstOrNull { it.name == name } ?: default

    fun decode(accent: String?, backdrop: String?, clock: String?, showDate: Boolean, railTicks: Boolean, favs: Int, suggestions: Boolean, hidden: String?) = LauncherSettings(
        enumOr(accent, Accent.Aurora), enumOr(backdrop, Backdrop.Aurora), enumOr(clock, ClockStyle.System), showDate, railTicks, favs, suggestions,
        hidden.orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
    ).clamped()

    fun encodeHidden(hidden: Set<String>) = hidden.filter { it.isNotBlank() && ',' !in it }.sorted().joinToString(",")

    /** Keys of the settings added after the first ones. All are plain strings, so a missing or odd one just means the default. */
    private val EXTRA_KEYS = listOf("dockIcons", "dockLabels", "drawerIcons", "drawerShowIcons", "drawerSort", "swipeUp", "swipeDown", "doubleTap", "longPress", "searchShowsAll", "searchWeb",
        "face", "clockLayout", "favLayout", "rail", "iconShape", "rules", "cards", "slots")

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
            face = enumOr(get("face"), base.face), clockLayout = enumOr(get("clockLayout"), base.clockLayout), favLayout = enumOr(get("favLayout"), base.favLayout),
            rail = enumOr(get("rail"), base.rail), iconShape = enumOr(get("iconShape"), base.iconShape),
            rules = get("rules")?.let { LivingLook.decode(it) } ?: base.rules,
            cards = get("cards")?.let { HomeCards.decode(it) } ?: base.cards,
            slots = get("slots")?.let { LookSlots.decode(it) } ?: base.slots,
        )
    }

    /** The newer settings as the strings to store, under the keys [withExtras] reads. */
    fun extras(s: LauncherSettings): Map<String, String> = mapOf(
        "dockIcons" to s.dockIcons.name, "dockLabels" to s.dockLabels.toString(), "drawerIcons" to s.drawerIcons.name,
        "drawerShowIcons" to s.drawerShowIcons.toString(), "drawerSort" to s.drawerSort.name, "swipeUp" to s.swipeUp.name,
        "swipeDown" to s.swipeDown.name, "doubleTap" to s.doubleTap.name, "longPress" to s.longPress.name,
        "searchShowsAll" to s.searchShowsAll.toString(), "searchWeb" to s.searchWeb.toString(),
        "face" to s.face.name, "clockLayout" to s.clockLayout.name, "favLayout" to s.favLayout.name, "rail" to s.rail.name, "iconShape" to s.iconShape.name,
        "rules" to LivingLook.encode(s.rules),
        "cards" to HomeCards.encode(s.cards),
        "slots" to LookSlots.encode(s.slots),
    ).also { check(it.keys.toList() == EXTRA_KEYS) }
}

/** The pages of the settings hub, in the order they are listed, with the words that find them when you type in the box on top. */
enum class SettingsPage(val title: String, val subtitle: String, val words: String) {
    Colours("Themes", "Whole looks, then colours, type, clock, favourites, rail and icons", "theme themes look looks living rule rules automatic time night day focus goal mirror saved save slots qr scan style styles colour color accent aurora sunset ocean meadow rose ember terminal paper zen tiles dusk font type typeface clock layout favourites rail icons shape share code"),
    Wallpaper("Wallpaper", "Background behind the home screen", "wallpaper background black graphite glow picture photo"),
    Home("Home screen", "Clock, suggestions and the letter rail", "home clock date time suggestions now tick rail letters haptic favourites reset cards activity rings heart rate camera cameras"),
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

/** What system Back does in the launcher settings: a page goes back to the list of pages, the list closes settings, and with settings closed there is nothing to undo. */
internal object SettingsBack {
    data class State(val open: Boolean, val page: SettingsPage?)

    fun next(s: State): State = when {
        !s.open -> s
        s.page != null -> s.copy(page = null)
        else -> s.copy(open = false)
    }
}

/**
 * The accent as text and highlights on the dark settings sheet. A light look's accent is dark (Ink is a brown), which would vanish on that sheet,
 * so a colour that isn't light enough is lightened until it reads (the look's own preview still draws its real colours).
 */
internal fun readableOnDark(c: Color): Color = if (c.luminance() >= 0.35f) c else lerp(c, Color.White, 0.62f)

internal val LauncherSettings.ink get() = readableOnDark(accent.a)
internal val LauncherSettings.inkB get() = readableOnDark(accent.b)
