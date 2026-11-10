package app.ultimate.health

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One thing eaten or drunk, logged by hand. [kcal] is optional: a rough guess is fine, and many people never fill it in. */
data class FoodEntry(val id: String, val day: Long, val at: Long, val meal: String, val text: String, val kcal: Int?)

/**
 * A small food and water journal, kept on the phone and nowhere else. No database of foods and no scanning: you write what you
 * had, optionally a calorie guess, and tap for each glass of water. The point is to see a week at a glance next to your steps and
 * sleep, not to count to the gram. Pure maths here, so it is tested; [FoodJournalStore] keeps it.
 */
object FoodJournal {
    val MEALS = listOf("Breakfast", "Lunch", "Dinner", "Snack", "Drink")
    const val WATER_GOAL = 8

    data class Day(val day: Long, val entries: List<FoodEntry>, val glasses: Int) {
        val kcal: Int? get() = entries.mapNotNull { it.kcal }.takeIf { it.isNotEmpty() }?.sum()
        val logged get() = entries.isNotEmpty() || glasses > 0
    }

    fun day(all: List<FoodEntry>, water: Map<Long, Int>, d: Long) = Day(d, all.filter { it.day == d }.sortedBy { it.at }, water[d] ?: 0)

    /** The last [n] days, oldest first, including days with nothing logged. */
    fun week(all: List<FoodEntry>, water: Map<Long, Int>, today: Long, n: Int = 7) = (today - n + 1..today).map { day(all, water, it) }

    /** Plain words about the week, or null when too little is logged to say anything. */
    fun summary(week: List<Day>): String? {
        val logged = week.filter { it.logged }
        if (logged.size < 3) return null
        val kcal = week.mapNotNull { it.kcal }
        val glasses = logged.map { it.glasses }.average()
        return buildString {
            append("Logged ${logged.size} of ${week.size} days")
            if (kcal.size >= 3) append(", about ${kcal.average().toInt()} kcal a day where you gave a number")
            append(". Water averages ${"%.1f".format(glasses)} of $WATER_GOAL glasses.")
        }
    }
}

class FoodJournalStore(context: Context) {
    private val prefs = context.getSharedPreferences("food-journal", Context.MODE_PRIVATE)

    fun entries(): List<FoodEntry> = runCatching {
        val a = JSONArray(prefs.getString("entries", "[]")); (0 until a.length()).map { i -> a.getJSONObject(i).let { FoodEntry(it.getString("id"), it.getLong("day"), it.getLong("at"), it.getString("meal"), it.getString("text"), if (it.has("kcal")) it.getInt("kcal") else null) } }
    }.getOrDefault(emptyList())

    fun water(): Map<Long, Int> = runCatching { val o = JSONObject(prefs.getString("water", "{}")); o.keys().asSequence().associate { it.toLong() to o.getInt(it) } }.getOrDefault(emptyMap())

    fun add(e: FoodEntry): List<FoodEntry> = (entries() + e).takeLast(2000).also { save(it) }
    fun remove(id: String): List<FoodEntry> = entries().filterNot { it.id == id }.also { save(it) }
    fun setWater(day: Long, glasses: Int): Map<Long, Int> = (water() + (day to glasses.coerceIn(0, 30))).also { m -> prefs.edit().putString("water", JSONObject(m.mapKeys { it.key.toString() }).toString()).apply() }
    /** Deletes everything in the journal. */
    fun clear() { prefs.edit().clear().apply() }

    private fun save(list: List<FoodEntry>) {
        prefs.edit().putString("entries", JSONArray().also { a -> list.forEach { e -> a.put(JSONObject().put("id", e.id).put("day", e.day).put("at", e.at).put("meal", e.meal).put("text", e.text).also { o -> e.kcal?.let { o.put("kcal", it) } }) } }.toString()).apply()
    }
}
