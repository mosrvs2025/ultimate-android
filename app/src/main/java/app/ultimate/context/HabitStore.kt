package app.ultimate.context

import android.content.Context
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Remembers which tool you open in which situation, on this phone only, and only when you turn it on in About and settings.
 * Off by default; turning it off or choosing Forget deletes everything it learned. Stores tool ids and a few coarse words.
 */
class HabitStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("habits", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val model = HabitModel.fromJson(prefs.getString("model", "[]").orEmpty())
    private var lastTool: String? = prefs.getString("last", null)
    private var lastAt = prefs.getLong("lastAt", 0)

    private val _enabled = MutableStateFlow(prefs.getBoolean("on", false))
    val enabled: StateFlow<Boolean> = _enabled
    private val _count = MutableStateFlow(model.size)
    /** How many opens it has learned from. */
    val count: StateFlow<Int> = _count

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean("on", on).apply()
        if (!on) forget()
    }

    fun forget() = synchronized(this) {
        model.clear(); lastTool = null; lastAt = 0; _count.value = 0
        prefs.edit().remove("model").remove("last").remove("lastAt").apply()
    }

    private fun prev(now: Long) = lastTool?.takeIf { now - lastAt < PREV_WINDOW_MS }

    /** The situation right now, for asking the model. */
    fun situation(now: Long = System.currentTimeMillis()): Situation = SituationReader.now(context, synchronized(this) { prev(now) }, now)

    /** Called when a tool is opened. Does nothing unless learning is on. */
    fun record(tool: String, now: Long = System.currentTimeMillis()) {
        if (!_enabled.value) return
        val s = situation(now)
        synchronized(this) { model.observe(tool, s, now); lastTool = tool; lastAt = now; _count.value = model.size }
        io.execute { synchronized(this) { prefs.edit().putString("model", model.toJson()).putString("last", lastTool).putLong("lastAt", lastAt).apply() } }
    }

    /** The one tool worth putting first right now, or null. Silent unless the evidence is real. */
    fun suggest(available: Collection<String>, now: Long = System.currentTimeMillis()): Prediction? {
        if (!_enabled.value) return null
        val s = situation(now)
        return synchronized(this) { model.suggest(s, now, available) }
    }

    companion object { const val PREV_WINDOW_MS = 30 * 60_000L }
}
