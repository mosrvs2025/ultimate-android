package app.ultimate.traffic.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.ultimate.BuildConfig
import app.ultimate.UltimateApp
import app.ultimate.pluck.engine.TrackerScanner
import app.ultimate.traffic.engine.CaptureConfig
import app.ultimate.traffic.model.DomainKind
import app.ultimate.traffic.model.TrackerDomains
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PickApp(val pkg: String, val label: String)

/** App-lifetime state for the traffic inspector: which apps to watch, what to block, and which sheet is open. */
class TrafficController(private val app: UltimateApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("traffic", Context.MODE_PRIVATE)
    val manager = app.trafficManager
    val state = manager.state

    val selected = MutableStateFlow(prefs.getStringSet("selected", emptySet()).orEmpty().toSet())
    val showNames = MutableStateFlow(prefs.getBoolean("showNames", true))
    val blockAds = MutableStateFlow(prefs.getBoolean("blockAds", false))
    /** Minutes until the inspector stops itself; 0 means it runs until stopped. */
    val autoStop = MutableStateFlow(prefs.getInt("autoStop", 30))
    val userBlocked = MutableStateFlow(prefs.getStringSet("blocked", emptySet()).orEmpty().toSet())
    val installed = MutableStateFlow<List<PickApp>>(emptyList())
    val hostSheet = MutableStateFlow<String?>(null)

    /** Apps the code scan found ad SDKs in, when this edition has that scanner. */
    val appsWithAds: Set<String>
        get() {
            if (!BuildConfig.HAS_PLUCK) return emptySet()
            val ads = TrackerScanner.catalog.filter { it.kind.name == "Ads" }.map { it.name }.toSet()
            return app.pluckScans.results.value.filter { (_, r) -> r.sdks.any { it in ads } }.keys
        }

    /** What the code scan found in an app, for comparing with what actually went over the network. */
    fun codeSdks(pkg: String): List<String>? = if (BuildConfig.HAS_PLUCK) app.pluckScans.results.value[pkg]?.sdks else null

    fun loadApps() {
        if (installed.value.isNotEmpty()) return
        scope.launch {
            installed.value = withContext(Dispatchers.IO) {
                val pm = app.packageManager
                val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                val found = if (android.os.Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(launch, PackageManager.ResolveInfoFlags.of(0)) else pm.queryIntentActivities(launch, 0)
                found
                    .map { PickApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                    .filter { it.pkg != app.packageName && !it.pkg.startsWith("app.ultimate") && it.pkg != "app.squeeze" && it.pkg != "app.pluck" }
                    .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
            }
        }
    }

    fun toggle(pkg: String) {
        selected.update { if (pkg in it) it - pkg else it + pkg }
        prefs.edit().putStringSet("selected", selected.value).apply()
    }

    fun select(pkgs: Collection<String>) {
        selected.update { it + pkgs }
        prefs.edit().putStringSet("selected", selected.value).apply()
    }

    fun deselect(pkgs: Collection<String>) {
        selected.update { it - pkgs.toSet() }
        prefs.edit().putStringSet("selected", selected.value).apply()
    }

    fun clearSelection() {
        selected.value = emptySet()
        prefs.edit().putStringSet("selected", emptySet()).apply()
    }

    fun setShowNames(on: Boolean) {
        showNames.value = on
        prefs.edit().putBoolean("showNames", on).apply()
    }

    fun setBlockAds(on: Boolean) {
        blockAds.value = on
        prefs.edit().putBoolean("blockAds", on).apply()
        manager.setBlocked(effectiveBlocked())
    }

    /** Blocks or unblocks a domain (and its subdomains) right away, even mid-capture. */
    fun setBlocked(domain: String, blocked: Boolean) {
        userBlocked.update { if (blocked) it + domain else it - domain }
        prefs.edit().putStringSet("blocked", userBlocked.value).apply()
        manager.setBlocked(effectiveBlocked())
    }

    fun isBlocked(host: String): Boolean = effectiveBlocked().any { host == it || host.endsWith(".$it") }

    private fun effectiveBlocked(): Set<String> = userBlocked.value + if (blockAds.value) TrackerDomains.suffixes(DomainKind.Ads) else emptySet()

    fun setAutoStop(minutes: Int) {
        autoStop.value = minutes
        prefs.edit().putInt("autoStop", minutes).apply()
    }

    fun config() = CaptureConfig(selected.value.toList(), showNames.value, effectiveBlocked(), autoStop.value)

    fun startNow() = manager.start(config())
    fun stop() = manager.stop()
    fun explain(host: String?) {
        hostSheet.value = host
    }
}
