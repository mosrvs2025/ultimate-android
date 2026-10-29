package app.ultimate.pluck.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The user's pattern for saved file names, e.g. "{name}_{version}". Unknown braces are kept as typed. */
object FileNames {
    const val DEFAULT = "{name}_{version}"

    /** Placeholder and what it becomes, for the picker. */
    val TOKENS = listOf(
        "{name}" to "App name",
        "{package}" to "Package",
        "{version}" to "Version",
        "{code}" to "Version code",
        "{date}" to "Today's date",
    )

    val PRESETS = listOf(DEFAULT, "{name}", "{package}_{code}", "{name}-v{version}-{date}")

    /** Fills in [template] for [app]. Characters that file systems or share targets choke on become "_". Never blank. */
    fun render(template: String, app: AppItem, now: Date = Date()): String {
        val filled = template
            .replace("{name}", app.label)
            .replace("{package}", app.packageName)
            .replace("{version}", app.versionName)
            .replace("{code}", app.versionCode.toString())
            .replace("{date}", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now))
        val cleaned = filled.trim().map { if (it.isLetterOrDigit() || it in "._-+() " ) it else '_' }.joinToString("")
            .replace(Regex("_{2,}"), "_").trim('_', '.', ' ')
        return cleaned.ifBlank { app.packageName }.take(120)
    }
}
