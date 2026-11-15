package app.ultimate.movement

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.LruCache
import app.ultimate.fusion.WebMercator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Street-map pictures under your route, from OpenStreetMap (© OpenStreetMap contributors). Only after the owner allows it
 * once, since fetching them tells OpenStreetMap's server which area of the map is being looked at. Each tile is kept on the
 * phone for a month, so the same streets are fetched once, and drawn darkened to sit in the app's night colours.
 */
class TileStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("movement", Context.MODE_PRIVATE)
    private val dir = File(app.cacheDir, "tiles").apply { mkdirs() }
    private val mem = object : LruCache<WebMercator.Tile, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(key: WebMercator.Tile, value: Bitmap) = value.byteCount }
    private val inFlight = HashSet<WebMercator.Tile>()
    private val failed = HashMap<WebMercator.Tile, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2) // OpenStreetMap asks apps to keep it to a couple of downloads at a time
    /** Goes up each time a tile arrives, so the map redraws. */
    val arrived = MutableStateFlow(0)

    /** null: not asked yet; true/false: the owner's answer. */
    var allowed: Boolean?
        get() = if (prefs.contains("tiles")) prefs.getBoolean("tiles", false) else null
        set(v) { if (v == null) prefs.edit().remove("tiles").apply() else prefs.edit().putBoolean("tiles", v).apply(); arrived.value++ }

    /** The tile if it is ready; otherwise starts fetching it (when allowed) and returns null for now. */
    fun get(t: WebMercator.Tile): Bitmap? {
        mem.get(t)?.let { return it }
        if (allowed != true) return null
        synchronized(inFlight) {
            if (t in inFlight) return null
            failed[t]?.let { if (System.currentTimeMillis() - it < 60_000) return null }
            inFlight += t
        }
        scope.launch {
            try {
                val f = File(dir, "${t.z}_${t.x}_${t.y}.png")
                val bytes = if (f.exists() && System.currentTimeMillis() - f.lastModified() < 30L * 86_400_000) f.readBytes() else gate.withPermit { download(t) }?.also { f.writeBytes(it) }
                val raw = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (raw != null) { mem.put(t, night(raw)); arrived.value++ } else synchronized(inFlight) { failed[t] = System.currentTimeMillis() }
            } catch (_: Exception) { synchronized(inFlight) { failed[t] = System.currentTimeMillis() } }
            finally { synchronized(inFlight) { inFlight -= t } }
        }
        return null
    }

    private fun download(t: WebMercator.Tile): ByteArray? {
        val c = URL("https://tile.openstreetmap.org/${t.z}/${t.x}/${t.y}.png").openConnection() as HttpURLConnection
        return try {
            c.setRequestProperty("User-Agent", "UltimateAndroid/2 (personal fitness map; https://github.com/mosrvs2025/ultimate-android)")
            c.connectTimeout = 8_000; c.readTimeout = 10_000
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        } finally { c.disconnect() }
    }

    /** The standard map turned into a night map: inverted, rotated back to natural hues, dimmed and cooled. */
    private fun night(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val invert = ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
        val tone = ColorMatrix(floatArrayOf(0.55f, 0f, 0f, 0f, 4f, 0f, 0.62f, 0f, 0f, 8f, 0f, 0f, 0.75f, 0f, 16f, 0f, 0f, 0f, 1f, 0f))
        val sat = ColorMatrix().apply { setSaturation(0.55f) }
        val m = ColorMatrix().apply { postConcat(invert); postConcat(sat); postConcat(tone) }
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) })
        src.recycle()
        return out
    }

    companion object {
        @Volatile private var instance: TileStore? = null
        fun get(c: Context) = instance ?: synchronized(this) { instance ?: TileStore(c).also { instance = it } }
    }
}
