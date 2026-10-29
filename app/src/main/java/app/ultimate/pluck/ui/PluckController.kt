package app.ultimate.pluck.ui

import app.ultimate.core.*
import app.ultimate.UltimateApp
import app.ultimate.pluck.engine.ScanStore
import app.ultimate.pluck.engine.TrackerScanner
import app.ultimate.pluck.model.AppDetail
import app.ultimate.pluck.model.AppItem
import app.ultimate.pluck.model.FileNames
import app.ultimate.pluck.engine.ApkComposition
import app.ultimate.pluck.engine.Extractor
import app.ultimate.pluck.engine.SdkKind
import app.ultimate.pluck.model.Cleanup
import app.ultimate.pluck.model.Filter
import app.ultimate.pluck.model.Mediation
import app.ultimate.pluck.model.Replacements
import app.ultimate.pluck.model.ListOptions
import app.ultimate.pluck.model.QuickFilter
import app.ultimate.pluck.model.Sort
import app.ultimate.pluck.model.TrackerFacts
import app.ultimate.pluck.model.arrange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

enum class Tab { Apps, Insights }

/** Full-screen pages layered over the tabs. */
enum class Page { Main, Trackers, Cleanup }

data class ScanProgress(val done: Int, val total: Int)

/** App-lifetime state for the Pluck tool. Created the first time the tool opens, so the app list loads only when it is wanted. */
class PluckController(private val app: UltimateApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = app.pluckRepository

    val apps = MutableStateFlow<List<AppItem>>(emptyList())
    val loading = MutableStateFlow(true)
    val query = MutableStateFlow("")
    val options = MutableStateFlow(ListOptions())
    val selection = MutableStateFlow<Set<String>>(emptySet())
    val tab = MutableStateFlow(Tab.Apps)
    val page = MutableStateFlow(Page.Main)

    val detailPackage = MutableStateFlow<String?>(null)
    val detail = MutableStateFlow<AppDetail?>(null)

    val extraction = app.pluckManager.state
    val saved = app.pluckHistory.entries
    val scans = app.pluckScans.results

    /** Scan results that still match the installed version of each app. */
    val trackers: StateFlow<Map<String, TrackerFacts>> = combine(apps, scans) { all, records ->
        val kinds = TrackerScanner.catalog.associate { it.name to it.kind }
        all.mapNotNull { a ->
            records[a.packageName]?.takeIf { it.versionCode == a.versionCode && it.updateTime == a.updateTime }
                ?.let { r ->
                    a.packageName to TrackerFacts(r.sdks, r.sdks.count { kinds[it] == SdkKind.Ads }, r.mediation?.mapNotNull(Mediation::decode))
                }
        }.toMap()
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Tools usable right now (built in, or in an installed edition), by tool id. */
    private val available: StateFlow<Map<String, String>> = apps.map { all ->
        Replacements.availability(app.tools.map { it.id }.toSet(), all.map { it.packageName }.toSet())
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val marks = app.pluckReplacements.marks

    /** Installed apps one of our tools can stand in for, newest decisions applied. */
    val cleanup: StateFlow<List<Cleanup>> = combine(apps, available, marks) { all, av, m -> Replacements.find(all, av, m.custom, m.accepted) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Tools a user can pick when marking an app as replaced. */
    val markTargets: StateFlow<Map<String, String>> = available

    val visible: StateFlow<List<AppItem>> = combine(apps, query, options, trackers, cleanup) { all, q, o, t, c ->
        all.arrange(q, o, t, c.map { it.app.packageName }.toSet())
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun markReplaced(item: AppItem, toolId: String) { scope.launch { app.pluckReplacements.mark(item, toolId) } }
    fun unmarkReplaced(pkg: String) { scope.launch { app.pluckReplacements.unmark(pkg) } }
    fun acceptMissing(pkg: String) { scope.launch { app.pluckReplacements.accept(pkg) } }
    fun revokeAccepted(pkg: String) { scope.launch { app.pluckReplacements.revoke(pkg) } }
    val scanProgress = MutableStateFlow<ScanProgress?>(null)
    private var scanJob: Job? = null
    val shareWhenDone = MutableStateFlow(false)

    fun reload() {
        scope.launch {
            apps.value = repository.loadAll()
            loading.value = false
            val present = apps.value.map { it.packageName }.toSet()
            selection.update { it.filter(present::contains).toSet() }
            if (detailPackage.value != null && detailPackage.value !in present) closeDetail()
        }
    }

    fun toggleSelected(pkg: String) = selection.update { if (pkg in it) it - pkg else it + pkg }
    fun selectAllVisible() = selection.update { visible.value.map { a -> a.packageName }.toSet() }
    fun clearSelection() {
        selection.value = emptySet()
    }

    fun openDetail(pkg: String) {
        detailPackage.value = pkg
        detail.value = null
        val item = apps.value.firstOrNull { it.packageName == pkg } ?: return
        scope.launch {
            // The app can vanish mid-load (uninstalled elsewhere); fall back to the list rather than crash.
            runCatching { repository.detail(item) }.onSuccess { detail.value = it }.onFailure { closeDetail() }
        }
    }

    fun closeDetail() {
        detailPackage.value = null
        detail.value = null
    }

    private val prefs = app.getSharedPreferences("pluck", android.content.Context.MODE_PRIVATE)

    /** How saved files are named; remembered between runs. */
    val nameTemplate = MutableStateFlow(prefs.getString("nameTemplate", null) ?: FileNames.DEFAULT)

    fun setNameTemplate(t: String) {
        nameTemplate.value = t
        prefs.edit().putString("nameTemplate", t).apply()
    }

    fun extract(items: List<AppItem>, compact: Boolean, share: Boolean) {
        shareWhenDone.value = share
        app.pluckManager.submit(items, compact, nameTemplate.value.ifBlank { FileNames.DEFAULT })
        clearSelection()
    }

    suspend fun composition(items: List<AppItem>): ApkComposition = withContext(Dispatchers.IO) {
        items.map { Extractor.composition(it.apkPaths) }.fold(ApkComposition(0, 0)) { a, b -> ApkComposition(a.storedBytes + b.storedBytes, a.compressedBytes + b.compressedBytes) }
    }

    fun updateOptions(change: (ListOptions) -> ListOptions) = options.update(change)

    /** Jumps to the Apps tab showing every app in a given order, e.g. from an Insights "See all". */
    fun showApps(sort: Sort, reversed: Boolean = false, quick: Set<QuickFilter> = emptySet()) {
        options.value = ListOptions(Filter.User, sort, reversed, quick)
        query.value = ""
        tab.value = Tab.Apps
        page.value = Page.Main
    }

    /** The SDK whose explainer sheet is open, from any screen. */
    val sdkSheet = MutableStateFlow<String?>(null)
    fun explainSdk(name: String?) {
        sdkSheet.value = name
    }

    /** Which SDK the tracker report should be filtered to when it opens. */
    val reportSdk = MutableStateFlow<String?>(null)
    fun openReport(sdk: String?) {
        reportSdk.value = sdk
        sdkSheet.value = null
        detailPackage.value = null
        page.value = Page.Trackers
    }

    fun openPage(p: Page) {
        page.value = p
    }

    val log get() = app.pluckManager.log

    fun cancelExtraction() = app.pluckManager.cancel()
    fun dismissResults() {
        shareWhenDone.value = false
        app.pluckManager.dismiss()
    }

    suspend fun refreshSaved() = app.pluckHistory.refresh()
    suspend fun removeSaved(id: String, deleteFile: Boolean) = app.pluckHistory.remove(id, deleteFile)

    /** Scans one app again even if a result exists, e.g. to add the mediation analysis to an older result. */
    fun rescan(item: AppItem) {
        scope.launch {
            val analysis = withContext(Dispatchers.IO) { TrackerScanner.analyze(item.apkPaths) }
            app.pluckScans.put(item.packageName, ScanStore.Record(item.versionCode, item.updateTime, analysis.sdks.map { it.name }, analysis.mediation.map { it.encode() }))
            app.pluckScans.flush()
        }
    }

    /** Scans one app, reusing a cached result for the same version. */
    fun scanOne(item: AppItem) {
        scope.launch { scanCached(item, app.pluckScans) ; app.pluckScans.flush() }
    }

    /** Scans every user app with two workers; results are cached per app version. */
    fun scanAll() {
        if (scanJob?.isActive == true) return
        val targets = apps.value.filter { !it.isSystem }
        scanProgress.value = ScanProgress(0, targets.size)
        scanJob = scope.launch {
            val done = AtomicInteger()
            val gate = Semaphore(2)
            targets.map { item ->
                async(Dispatchers.Default) {
                    gate.withPermit { scanCached(item, app.pluckScans) }
                    scanProgress.value = ScanProgress(done.incrementAndGet(), targets.size)
                }
            }.awaitAll()
            app.pluckScans.flush()
            scanProgress.value = null
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanProgress.value = null
        scope.launch { app.pluckScans.flush() }
    }

    private suspend fun scanCached(item: AppItem, store: ScanStore) {
        // A result without mediation info predates that analysis, so it is redone once.
        if (store.cached(item.packageName, item.versionCode, item.updateTime)?.mediation != null) return
        val analysis = withContext(Dispatchers.IO) { TrackerScanner.analyze(item.apkPaths) }
        store.put(item.packageName, ScanStore.Record(item.versionCode, item.updateTime, analysis.sdks.map { it.name }, analysis.mediation.map { it.encode() }))
    }
}
