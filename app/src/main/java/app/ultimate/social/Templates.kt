package app.ultimate.social

import java.util.Locale

/**
 * The wording of the messages friends see, written by the owner with a few fill-in pieces:
 * `{name}` `{kind}` (walk or run) `{emoji}` `{minutes}` `{km}` and `{stats}` (": 2.3 km in 31 min", or nothing).
 *
 * The owner's own words go through as written (they may include a link, say to a voice channel), but everything that is filled
 * in goes through [Delivery.safe] first, so a name can never carry a ping or a link. Pure, so every rule is tested exactly.
 */
object Templates {
    const val START = "{emoji} {name} is going for a {kind}. Join them!"
    const val FINISH = "🏁 {name} finished a {kind}{stats}"
    const val MAX = 300

    /** The pieces, with what each stands for, in the order the designer lists them. */
    val PIECES: List<Pair<String, String>> = listOf(
        "name" to "your name", "kind" to "walk or run", "emoji" to "🚶 or 🏃", "minutes" to "how long (finish only)",
        "km" to "distance (finish only)", "stats" to "\": 2.3 km in 31 min\" (finish only)",
    )
    private val KEYS = PIECES.map { it.first }.toSet()
    private val PIECE = Regex("""\{([^{}]*)}""")

    /** Why [template] can't be used, in words for the screen, or null when it is fine. Blank is fine: it means "the usual wording". */
    fun problem(template: String): String? {
        if (template.length > MAX) return "That's too long (most $MAX characters)."
        if (template.any { it < ' ' && it != '\n' || it == '\u007f' }) return "That has a character that can't be sent."
        PIECE.findAll(template).firstOrNull { it.groupValues[1] !in KEYS }?.let { m ->
            return "{${m.groupValues[1]}} isn't one of the pieces. Use ${PIECES.joinToString(" ") { "{" + it.first + "}" }}."
        }
        if (PIECE.replace(template, "").let { '{' in it || '}' in it }) return "A { or } is left over. Pieces look like {name}."
        return null
    }

    /** [template] with its pieces filled in; [state] is "walking"/"running" (or "walk"/"run"). */
    fun render(template: String, name: String, state: String, minutes: Int? = null, km: Double? = null): String {
        val run = state == "running" || state == "run"
        val who = Delivery.safe(name, 24).ifBlank { "A friend" }
        val kmText = km?.takeIf { it > 0.05 }?.let { "%.1f".format(Locale.US, it) }
        val minText = minutes?.takeIf { it > 0 }?.toString()
        val bits = listOfNotNull(kmText?.let { "$it km" }, minText?.let { "$it min" })
        val values = mapOf(
            "name" to who, "kind" to if (run) "run" else "walk", "emoji" to if (run) "🏃" else "🚶",
            "minutes" to (minText ?: ""), "km" to (kmText ?: ""), "stats" to if (bits.isEmpty()) "" else ": " + bits.joinToString(" in "),
        )
        val body = template.ifBlank { START }.let { PIECE.replace(it) { m -> values[m.groupValues[1]] ?: "" } }
        return body.filter { it >= ' ' || it == '\n' }.lines().joinToString("\n") { it.replace(Regex(" {2,}"), " ").trim() }.trim().take(1_900)
    }
}
