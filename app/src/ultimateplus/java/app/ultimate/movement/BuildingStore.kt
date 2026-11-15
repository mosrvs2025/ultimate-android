package app.ultimate.movement

import android.content.Context
import app.ultimate.fusion.Buildings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Building outlines for the 3D map, from OpenStreetMap's Overpass service (© OpenStreetMap contributors), only after the owner
 * allows it (asking tells that server roughly where the map is looking). One request covers a snapped box around the route and is
 * kept on the phone for 90 days, so the same yard asks once. One request at a time, as the service asks of apps.
 */
class BuildingStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("movement", Context.MODE_PRIVATE)
    private val dir = File(app.cacheDir, "buildings").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mem = HashMap<String, List<Buildings.Building>>()
    private val inFlight = HashSet<String>()
    private val failed = HashMap<String, Long>()
    /** Goes up each time buildings arrive, so the map redraws. */
    val arrived = MutableStateFlow(0)

    /** null: not asked yet; true/false: the owner's answer. */
    var allowed: Boolean?
        get() = if (prefs.contains("buildings")) prefs.getBoolean("buildings", false) else null
        set(v) { if (v == null) prefs.edit().remove("buildings").apply() else prefs.edit().putBoolean("buildings", v).apply(); arrived.value++ }

    /** The buildings in [box] (south, west, north, east) placed around ([lat0], [lon0]) if ready; otherwise starts fetching (when allowed) and returns null. */
    fun get(box: DoubleArray, lat0: Double, lon0: Double): List<Buildings.Building>? {
        val name = box.joinToString("_") { "%.3f".format(java.util.Locale.US, it) }
        val key = "$name@%.5f,%.5f".format(java.util.Locale.US, lat0, lon0)
        synchronized(mem) { mem[key]?.let { return it } }
        if (allowed != true) return null
        synchronized(inFlight) {
            if (key in inFlight) return null
            failed[key]?.let { if (System.currentTimeMillis() - it < 120_000) return null }
            inFlight += key
        }
        scope.launch {
            try {
                val f = File(dir, "$name.json")
                val json = if (f.exists() && System.currentTimeMillis() - f.lastModified() < 90L * 86_400_000) f.readText() else download(box)?.also { f.writeText(it) }
                if (json != null) { synchronized(mem) { mem[key] = Buildings.parse(json, lat0, lon0) }; arrived.value++ } else synchronized(inFlight) { failed[key] = System.currentTimeMillis() }
            } catch (_: Exception) { synchronized(inFlight) { failed[key] = System.currentTimeMillis() } }
            finally { synchronized(inFlight) { inFlight -= key } }
        }
        return null
    }

    private fun download(box: DoubleArray): String? = synchronized(NETWORK) {
        val q = URLEncoder.encode(Buildings.query(box[0], box[1], box[2], box[3]), "UTF-8")
        val c = URL("https://overpass-api.de/api/interpreter?data=$q").openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("User-Agent", "UltimateAndroid/2 (personal fitness map; https://github.com/mosrvs2025/ultimate-android)")
            c.connectTimeout = 8_000; c.readTimeout = 25_000
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8) else null
        } finally { c.disconnect() }
    }

    companion object {
        private val NETWORK = Any()
        @Volatile private var instance: BuildingStore? = null
        fun get(c: Context) = instance ?: synchronized(this) { instance ?: BuildingStore(c).also { instance = it } }
    }
}
