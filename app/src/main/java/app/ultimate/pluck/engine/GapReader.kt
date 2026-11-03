package app.ultimate.pluck.engine

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.ultimate.pluck.model.ArscStrings
import app.ultimate.pluck.model.FeatureGap
import app.ultimate.pluck.model.GapMark
import app.ultimate.pluck.model.GapScan
import app.ultimate.pluck.model.Replacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.zip.ZipFile

/** Reads an installed app for features our replacement may lack. Offline: only the app's own files and its manifest. */
object GapReader {
    suspend fun scan(context: Context, pkg: String, r: Replacement): GapScan? = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull() ?: return@withContext null
        val ai = info.applicationInfo ?: return@withContext null
        val strings = runCatching {
            ZipFile(ai.sourceDir).use { z -> z.getEntry("resources.arsc")?.let { e -> z.getInputStream(e).use { ArscStrings.read(it.readBytes()) } } }
        }.getOrNull().orEmpty()
        val widgets = runCatching {
            AppWidgetManager.getInstance(context).getInstalledProvidersForPackage(pkg, null).map { it.loadLabel(pm) }
        }.getOrDefault(emptyList())
        val tiles = runCatching { pm.queryIntentServices(Intent("android.service.quicksettings.action.QS_TILE").setPackage(pkg), 0).size }.getOrDefault(0)
        val shares = listOf("video/*" to "videos", "audio/*" to "audio", "image/*" to "images", "text/plain" to "text", "application/pdf" to "PDFs").filter { (mime, _) ->
            runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType(mime).setPackage(pkg), 0).isNotEmpty() }.getOrDefault(false)
        }.map { it.second }
        val name = ai.loadLabel(pm).toString()
        FeatureGap.scan(pkg, name, info.versionName.orEmpty(), r.toolId, strings, FeatureGap.declared(info.requestedPermissions.orEmpty().toList(), widgets, tiles, shares))
    }
}

/** Your answers per found feature: needed in Ultimate, or not. Kept on the phone. */
class GapMarks(context: Context) {
    private val prefs = context.getSharedPreferences("pluck_gaps", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val marks: StateFlow<Map<String, GapMark>> = state

    private fun load(): Map<String, GapMark> = prefs.all.mapNotNull { (k, v) -> runCatching { k to GapMark.valueOf(v as String) }.getOrNull() }.toMap()

    fun set(pkg: String, text: String, mark: GapMark?) {
        val k = FeatureGap.key(pkg, text)
        prefs.edit().apply { if (mark == null) remove(k) else putString(k, mark.name) }.apply()
        state.update { if (mark == null) it - k else it + (k to mark) }
    }
}
