package app.ultimate.launcher

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The installed apps the home screen can open, their icons (drawn once and kept, so scrolling the letters never stutters), and
 * the favourites. Everything is read on a background thread; the screen only ever shows what is already loaded.
 */
class LauncherRepo(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val icons = object : LruCache<String, ImageBitmap>(160) {}

    private val _apps = MutableStateFlow<List<HomeApp>>(emptyList())
    val apps: StateFlow<List<HomeApp>> = _apps
    private val _favourites = MutableStateFlow<List<String>>(emptyList())
    val favourites: StateFlow<List<String>> = _favourites

    suspend fun load() = withContext(Dispatchers.IO) {
        val pm = app.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { HomeApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        _apps.value = list
        val saved = prefs.getString("favs", null)
        _favourites.value = if (saved != null) saved.split(',').filter { it.isNotBlank() && list.any { a -> a.pkg == it } }
            else LauncherMath.defaultFavourites(usage(), phoneDefaults(), list.map { it.pkg }.toSet(), app.packageName)
    }

    /** For previews and tests: a fixed set of apps instead of the phone's. */
    internal fun seed(apps: List<HomeApp>, favourites: List<String>) { _apps.value = apps; _favourites.value = favourites }

    fun setFavourites(pkgs: List<String>) { _favourites.value = pkgs; prefs.edit().putString("favs", pkgs.joinToString(",")).apply() }
    fun toggleFavourite(pkg: String) = setFavourites(if (pkg in _favourites.value) _favourites.value - pkg else (_favourites.value + pkg).take(8))

    /** The icon, drawn at [px] once and cached. Null while it has not been drawn yet (call [loadIcon] off the main thread). */
    fun icon(pkg: String): ImageBitmap? = icons.get(pkg)
    suspend fun loadIcon(pkg: String, px: Int = 108): ImageBitmap? = icons.get(pkg) ?: withContext(Dispatchers.IO) {
        runCatching { app.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap() }.getOrNull()?.also { icons.put(pkg, it) }
    }

    fun launch(pkg: String): Boolean {
        val i = app.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)); true }.getOrDefault(false)
    }

    fun appInfo(pkg: String) { runCatching { app.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    /** Minutes of use over the last 14 days, when Usage access is allowed; empty otherwise (it is never asked for here). */
    private fun usage(): List<Pair<String, Long>> = runCatching {
        val um = app.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        um.queryAndAggregateUsageStats(now - 14L * 86_400_000, now).map { (k, v) -> k to v.totalTimeInForeground / 60_000 }
    }.getOrDefault(emptyList())

    /** The phone's own dialer, messages, camera and browser, whichever apps those are on this phone. */
    private fun phoneDefaults(): List<String> {
        val pm = app.packageManager
        fun of(i: Intent) = runCatching { pm.resolveActivity(i, 0)?.activityInfo?.packageName }.getOrNull()?.takeIf { it != "android" }
        return listOfNotNull(
            of(Intent(Intent.ACTION_DIAL)),
            runCatching { Telephony.Sms.getDefaultSmsPackage(app) }.getOrNull(),
            of(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
            of(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
            of(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)),
        )
    }
}
