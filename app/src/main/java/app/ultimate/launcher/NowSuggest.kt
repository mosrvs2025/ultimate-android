package app.ultimate.launcher

import android.content.Context
import android.content.Intent
import app.ultimate.UltimateApp
import app.ultimate.context.HabitModel
import app.ultimate.context.Prediction
import app.ultimate.context.SituationReader
import java.io.File

/** Something worth offering right now: an app, or one of Ultimate's own actions, with why. */
sealed interface Now {
    val label: String
    val because: String
    data class App(val pkg: String, override val label: String, override val because: String) : Now
    data class Action(val id: String, override val label: String, override val because: String) : Now
}

/** What the phone knows about the moment, gathered by the caller; [NowSuggest] decides from it. */
data class NowContext(
    /** "walking" or "running" when the watch (or the phone) says so, recently; null otherwise. */
    val moving: String?,
    val recording: Boolean,
    val headphones: Boolean,
    val musicApp: String?,
    /** Apps the habit model expects now, most likely first, each with its reasons. */
    val habits: List<Prediction>,
    val installed: Set<String>,
    val canRecord: Boolean,
)

/**
 * Up to three things for the top of the home screen and the edge rail, decided by plain rules from the moment (no model guesses
 * what you meant): a walk your watch noticed offers to record it, a recording in progress offers to open it, headphones in offers
 * your music app, and the habit model offers what you usually open at this time and place, when it has seen enough to be sure.
 * Every offer says why, so a strange one can be understood (and ignored).
 */
object NowSuggest {
    fun suggest(c: NowContext, label: (String) -> String?): List<Now> {
        val out = ArrayList<Now>()
        if (c.recording) out += Now.Action("movement.open", "Recording", "Movement is recording")
        else if (c.moving != null && c.canRecord) out += Now.Action("movement.record", if (c.moving == "running") "Record this run" else "Record this walk", "your watch says you're ${c.moving}")
        if (c.headphones && c.musicApp != null && c.musicApp in c.installed) label(c.musicApp)?.let { out += Now.App(c.musicApp, it, "your headphones are in") }
        for (p in c.habits) {
            if (out.size >= 3) break
            if (p.tool !in c.installed || out.any { it is Now.App && it.pkg == p.tool }) continue
            val name = label(p.tool) ?: continue
            out += Now.App(p.tool, name, p.because.firstOrNull()?.let { "you often open it $it" } ?: "you often open it now")
        }
        return out.take(3)
    }
}

/**
 * Learns which apps you open when and where, from your own launches on Ultimate's home screen and edge rail only, using the same
 * explainable habit model as Ultimate's tool suggestions (time of day, weekday, charging, headphones, Wi-Fi, what you opened just
 * before). Kept in a private file; nothing leaves the phone. Offers an app only once it has seen at least [MIN_LAUNCHES] launches and
 * the app is clearly likelier than the rest.
 */
class LauncherHabits(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "launcher_habits.json")
    private val model: HabitModel = runCatching { HabitModel.fromJson(file.readText()) }.getOrDefault(HabitModel())
    private var launches = app.getSharedPreferences("launcher", Context.MODE_PRIVATE).getInt("launches", 0)
    private var last: String? = null

    private fun situation() = (app as? UltimateApp)?.habits?.situation() ?: SituationReader.now(app, last)

    @Synchronized fun record(pkg: String) {
        val now = System.currentTimeMillis()
        model.observe(pkg, situation(), now)
        last = pkg; launches++
        app.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putInt("launches", launches).apply()
        runCatching { file.writeText(model.toJson()) }
    }

    @Synchronized fun likely(installed: Collection<String>): List<Prediction> {
        if (launches < MIN_LAUNCHES) return emptyList()
        val r = model.rank(situation(), System.currentTimeMillis(), installed)
        return r.filter { it.probability >= 0.25 }.take(2)
    }

    companion object { const val MIN_LAUNCHES = 12 }
}

/** Reads the moment for [NowSuggest] from the phone: the senses bus (watch walking, a Movement recording) and the audio route. */
object NowReader {
    fun read(context: Context, installed: Set<String>, habits: LauncherHabits?): NowContext {
        val app = context.applicationContext as? UltimateApp
        val now = System.currentTimeMillis()
        val lastWatch = app?.senses?.recent?.value?.firstOrNull { it.source == "watch" && it.kind in setOf("walking", "running", "still") }
        val moving = lastWatch?.takeIf { it.kind != "still" && now - it.at < 10 * 60_000 }?.kind
        val recording = app?.senses?.sessions?.value?.any { it.id == "movement.record" } == true
        val music = runCatching { context.packageManager.resolveActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC), 0)?.activityInfo?.packageName }.getOrNull()
            ?.takeIf { it in installed } ?: listOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.sec.android.app.music", "com.apple.android.music").firstOrNull { it in installed }
        val canRecord = app != null && app.packageName.endsWith(".plus") &&
            context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return NowContext(moving, recording, SituationReader.headphones(context), music, habits?.likely(installed).orEmpty(), installed, canRecord)
    }

    /** Runs one of Ultimate's own actions offered by [NowSuggest]. */
    fun act(context: Context, id: String) {
        when (id) {
            "movement.record" -> runCatching { context.startForegroundService(Intent().setClassName(context.packageName, "app.ultimate.movement.MovementService").setAction("app.ultimate.movement.START")) }
            "movement.open" -> runCatching { context.startActivity(Intent().setClassName(context.packageName, "app.ultimate.MainActivity").putExtra("app.ultimate.TOOL", "movement").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
