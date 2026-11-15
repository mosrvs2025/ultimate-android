package app.ultimate.movement

import android.content.Context
import app.ultimate.fusion.Conditions
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Weather and air quality for a walk, kept next to the recording. Only after the owner allows it once (the place, rounded to about
 * a kilometre, goes to the free Open-Meteo service), and only for a walk they open; nothing is fetched in the background.
 */
class ConditionsStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("movement", Context.MODE_PRIVATE)
    private fun file(id: String) = File(File(File(app.filesDir, "movement"), id), "conditions.json")

    /** null: not asked yet; true/false: the owner's answer. */
    var allowed: Boolean?
        get() = if (prefs.contains("conditions")) prefs.getBoolean("conditions", false) else null
        set(v) { if (v == null) prefs.edit().remove("conditions").apply() else prefs.edit().putBoolean("conditions", v).apply() }

    fun saved(id: String): Conditions.Snapshot? = runCatching { Conditions.decode(file(id).readText()) }.getOrNull()

    /** Looks up the conditions at [lat], [lon] for the hour of [startMs], keeps them, and returns them (null if the service had nothing). Blocking. */
    fun fetch(id: String, lat: Double, lon: Double, startMs: Long): Conditions.Snapshot? {
        if (allowed != true) return null
        saved(id)?.let { return it }
        val snap = Conditions.parse(get(Conditions.weatherUrl(lat, lon)), get(Conditions.airUrl(lat, lon)), startMs) ?: return null
        runCatching { file(id).also { it.parentFile?.mkdirs() }.writeText(Conditions.encode(snap)) }
        return snap
    }

    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try { c.setRequestProperty("User-Agent", "UltimateAndroid/2"); c.connectTimeout = 8_000; c.readTimeout = 10_000; if (c.responseCode == 200) c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8) else null } finally { c.disconnect() }
    }.getOrNull()
}
