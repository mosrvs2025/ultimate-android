package app.ultimate.search

import java.util.Locale

/** What kind of thing a result is: its section on screen and its badge. Order is the order of sections. */
enum class Kind(val section: String, val badge: String) {
    App("Apps", "App"), Tool("Ultimate", "Tool"), Action("Do it", "Action"), Contact("Contacts", "Contact"),
    Message("Notifications", "Notification"), Copied("Copied", "Copied"), File("Files", "File"),
    Setting("Settings", "Setting"), Record("Your records", "Record"), Sensed("Sensed", "Echo"), Rule("Rules", "Rule"),
}

/** One result. [score] orders results; [run] carries it out. */
class Hit(val kind: Kind, val title: String, val subtitle: String, val score: Int, val key: String, val iconPackage: String? = null, val run: () -> Unit)

/**
 * The command palette's ranking: how well a piece of text matches what was typed, and how results are grouped.
 * Pure, so it is tested; the searching of each source happens in [PaletteSources].
 */
object PaletteMath {
    /**
     * What a spoken or typed request is about, without its leading verb: "Play YouTube" and "open the YouTube app"
     * both search for "YouTube". Returns the request unchanged when it doesn't start with one.
     */
    fun withoutVerb(query: String): String {
        val m = Regex("^\\s*(play|open|launch|start|run|go to|show( me)?|find|search( for)?|call|text|message)\\s+(the\\s+|my\\s+)?(.+?)(\\s+app)?\\s*$", RegexOption.IGNORE_CASE).find(query)
        return m?.groupValues?.get(5)?.takeIf { it.isNotBlank() } ?: query
    }

    /** 0 when it doesn't match. Exact beats prefix beats a word starting with it beats anywhere; every typed word must appear. */
    fun score(text: String, query: String): Int {
        val t = text.lowercase(Locale.ROOT).trim(); val q = query.lowercase(Locale.ROOT).trim()
        if (q.isEmpty() || t.isEmpty()) return 0
        return when {
            t == q -> 100
            t.startsWith(q) -> 85
            Regex("(^|[^a-z0-9])" + Regex.escape(q)).containsMatchIn(t) -> 70
            t.contains(q) -> 50
            q.split(Regex("\\s+")).all { w -> w.isNotEmpty() && t.contains(w) } -> 35
            initials(t).startsWith(q.replace(" ", "")) && q.length >= 2 -> 30
            else -> 0
        }
    }

    /** "Notification history" -> "nh", so two letters can find a tool. */
    private fun initials(t: String) = t.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.joinToString("") { it.take(1) }

    /** Best of several fields (title counts fully, others a little less). */
    fun best(query: String, title: String, vararg others: String): Int =
        maxOf(score(title, query), others.maxOfOrNull { (score(it, query) * 0.8).toInt() } ?: 0)

    /**
     * The top hit (the single best result overall, when it clearly leads) and then each kind in section order, best
     * first, at most [perKind] each.
     */
    fun arrange(hits: List<Hit>, perKind: Int = 4): Pair<Hit?, List<Pair<Kind, List<Hit>>>> {
        val sorted = hits.filter { it.score > 0 }.distinctBy { it.key }.sortedByDescending { it.score }
        val top = sorted.firstOrNull()?.takeIf { it.score >= 70 }
        val rest = sorted.filter { it !== top }
        val groups = Kind.entries.mapNotNull { k -> rest.filter { it.kind == k }.take(perKind).takeIf { it.isNotEmpty() }?.let { k to it } }
        return top to groups
    }

    /** Android settings pages worth jumping to, with the words people use for them. */
    val SETTINGS: List<Triple<String, String, List<String>>> = listOf(
        Triple("Wi-Fi", "android.settings.WIFI_SETTINGS", listOf("wifi", "wireless", "internet", "network")),
        Triple("Bluetooth", "android.settings.BLUETOOTH_SETTINGS", listOf("bluetooth", "pair", "headphones", "watch")),
        Triple("Battery", "android.intent.action.POWER_USAGE_SUMMARY", listOf("battery", "power", "charging")),
        Triple("Display and brightness", "android.settings.DISPLAY_SETTINGS", listOf("display", "brightness", "screen", "dark mode")),
        Triple("Sound and vibration", "android.settings.SOUND_SETTINGS", listOf("sound", "volume", "ringtone", "vibration")),
        Triple("Apps", "android.settings.APPLICATION_SETTINGS", listOf("apps", "applications", "uninstall")),
        Triple("Notifications", "android.settings.NOTIFICATION_SETTINGS", listOf("notifications", "alerts")),
        Triple("Location", "android.settings.LOCATION_SOURCE_SETTINGS", listOf("location", "gps")),
        Triple("Storage", "android.settings.INTERNAL_STORAGE_SETTINGS", listOf("storage", "space")),
        Triple("Accessibility", "android.settings.ACCESSIBILITY_SETTINGS", listOf("accessibility")),
        Triple("Date and time", "android.settings.DATE_SETTINGS", listOf("date", "time", "clock")),
        Triple("About phone", "android.settings.DEVICE_INFO_SETTINGS", listOf("about", "phone", "model", "version")),
        Triple("Developer options", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", listOf("developer", "usb debugging")),
        Triple("Mobile data", "android.settings.DATA_ROAMING_SETTINGS", listOf("mobile data", "cellular", "roaming")),
        Triple("Do not disturb", "android.settings.ZEN_MODE_SETTINGS", listOf("do not disturb", "dnd", "silent")),
    )
}
