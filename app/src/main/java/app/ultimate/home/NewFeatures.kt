package app.ultimate.home

import android.content.Context
import app.ultimate.UltimateApp
import app.ultimate.core.Tool

/**
 * New things should find you, not the other way round. Changelog entries name the tools they add or change ([Changelog.Entry.tries]);
 * those show as "New for you" on Home, with a New badge on their tiles, until you open them or hide the card.
 */
object NewFeatures {
    /** Tool ids worth trying from the newest [versions] entries up to [current], newest first, without repeats. */
    fun ids(current: String, entries: List<Changelog.Entry> = Changelog.entries, versions: Int = 3): List<String> =
        entries.dropWhile { it.version != current }.take(versions).flatMap { it.tries }.distinct()

    private fun prefs(c: Context) = c.getSharedPreferences("new_features", Context.MODE_PRIVATE)
    fun hidden(c: Context): Set<String> = prefs(c).getStringSet("hidden", emptySet()).orEmpty()
    fun hide(c: Context, ids: Collection<String>) { prefs(c).edit().putStringSet("hidden", hidden(c) + ids).apply() }

    /** The new tools this person hasn't opened yet and hasn't hidden. */
    fun pending(app: UltimateApp, current: String): List<Tool> {
        val hide = hidden(app)
        return ids(current).filter { it !in hide && app.insights.opens(it) == 0 }.mapNotNull { id -> app.tools.firstOrNull { it.id == id } }
    }
}
