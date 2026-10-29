package app.ultimate.checkup.engine

import app.ultimate.R
import android.content.Context
import app.ultimate.BuildConfig
import app.ultimate.UltimateApp
import app.ultimate.checkup.model.Checkup
import app.ultimate.checkup.model.Scan
import app.ultimate.checkup.model.Guardian
import app.ultimate.checkup.model.Change
import app.ultimate.checkup.model.Report
import app.ultimate.checkup.model.ReportOptions
import app.ultimate.checkup.model.Severity
import app.ultimate.core.JobLog
import app.ultimate.core.relativeTime
import app.ultimate.core.WorkSnapshot
import app.ultimate.core.WorkSource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import app.ultimate.pluck.engine.SdkKind
import app.ultimate.pluck.engine.TrackerScanner
import app.ultimate.pluck.model.Replacements
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs the checkup and keeps the latest result for as long as the app is alive; the score is remembered between launches. */
class CheckupManager(private val app: UltimateApp) : WorkSource {
    sealed interface State {
        data object Idle : State
        data class Scanning(val step: String, val done: Int, val total: Int) : State
        data class Done(val scan: Scan) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val collector = Collector(app)
    private val prefs = app.getSharedPreferences("checkup", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _last = MutableStateFlow(prefs.getInt("score", -1).takeIf { it >= 0 }?.let { it to prefs.getLong("at", 0) })
    /** Last score and when it was taken, for the Home tile. */
    val last: StateFlow<Pair<Int, Long>?> = _last.asStateFlow()
    private var job: Job? = null
    val archive = ReportArchive(app)

    // Runs on the app's own scope, so leaving the screen never stops a scan; the shared work hub shows it on Home and in
    // Activity, keeps it alive with a notification and announces the score when it is done.
    override val notifyIcon = R.drawable.ic_stat_checkup
    override val notifyColor = 0xFF1565C0.toInt()
    override val workId = "checkup"
    override val workTitle = "Phone checkup"
    override val log = JobLog()
    override fun cancelWork() { job?.cancel(); _state.value = State.Idle }
    override val work: StateFlow<WorkSnapshot> = _state.map { s ->
        when (s) {
            is State.Scanning -> WorkSnapshot(true, "Checking your phone", s.step + if (s.total > 0) " · ${s.done}/${s.total}" else "", if (s.total > 0) s.done * 100 / s.total else null)
            is State.Done -> WorkSnapshot(
                doneTitle = "Checkup: ${s.scan.score}/100 · ${Checkup.verdict(s.scan.score).substringBefore(',')}",
                doneText = s.scan.findings.count { it.severity == Severity.Critical }.let { c -> if (c > 0) "$c thing${if (c == 1) "" else "s"} need attention. Tap to see." else "Nothing urgent. Tap for the details." },
            )
            is State.Failed -> WorkSnapshot(failed = true, doneTitle = "Checkup couldn't finish", doneText = s.message)
            State.Idle -> WorkSnapshot()
        }
    }.stateIn(scope, SharingStarted.Eagerly, WorkSnapshot())

    /** Our own editions are not judged as third-party apps. */
    val own: Set<String> = Replacements.ours + app.packageName

    fun usageAccess() = collector.hasUsageAccess()

    private val snapshotFile = java.io.File(app.filesDir, "guardian.json")
    /** What changed since the checkup before the latest one, and when that one ran; null before there are two checkups. */
    val changes = MutableStateFlow<Pair<Long, List<Change>>?>(null)

    fun scan() {
        if (_state.value is State.Scanning) return
        log.reset()
        log.info("Checkup started")
        _state.value = State.Scanning("Reading this phone", 0, 0)
        app.work.start()
        job = scope.launch {
            try { runScan() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log.error(e.message ?: e.javaClass.simpleName)
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * The checkup itself, also run by the weekly background check (which has no screen or foreground service). Returns what
     * changed since the previous checkup.
     */
    suspend fun runScan(): List<Change> {
        _state.value = State.Scanning("Reading this phone", 0, 0)
        val usage = collector.hasUsageAccess()
        val device = withContext(Dispatchers.IO) { collector.device() }
        val ads = if (BuildConfig.HAS_PLUCK) adSdks() else null
        val apps = withContext(Dispatchers.IO) {
            collector.apps(ads, usage) { done, total -> _state.value = State.Scanning("Checking apps", done, total) }
        }
        _state.value = State.Scanning("Weighing it up", apps.size, apps.size)
        val now = System.currentTimeMillis()
        val scan = Scan(now, device, apps, usage, Checkup.findings(device, apps, now, usage, own))
        log.ok("Score ${scan.score}/100 · ${apps.size} apps · ${scan.findings.count { it.severity == Severity.Critical }} critical, ${scan.findings.count { it.severity == Severity.Warning }} warnings")
        archive.save(
            now, scan.score, scan.findings.count { it.severity == Severity.Critical }, scan.findings.count { it.severity == Severity.Warning }, apps.size,
            Report.PROMPT + "\n\n" + Report.markdown(scan, ReportOptions(), own),
        )?.let { log.detail("  saved   Documents/Ultimate (in the Library)") }
        val snap = Guardian.snapshot(scan, own)
        val diff = withContext(Dispatchers.IO) {
            val previous = runCatching { Guardian.decode(snapshotFile.readText()) }.getOrNull()
            snapshotFile.writeText(Guardian.encode(snap))
            previous?.let { it.at to Guardian.diff(it, snap) }
        }
        changes.value = diff
        diff?.second?.takeIf { it.isNotEmpty() }?.let { log.detail("  changes ${it.size} since ${relativeTime(diff.first)}") }
        _state.value = State.Done(scan)
        _last.value = scan.score to now
        prefs.edit().putInt("score", scan.score).putLong("at", now).apply()
        return diff?.second.orEmpty()
    }

    /** Ad SDKs per app from Pluck's code scan, or null when it has never run (unknown, not "none"). */
    private fun adSdks(): Map<String, List<String>>? {
        val results = app.pluckScans.results.value
        if (results.isEmpty()) return null
        val ads = TrackerScanner.catalog.filter { it.kind == SdkKind.Ads }.map { it.name }.toSet()
        return results.mapValues { (_, r) -> r.sdks.filter { it in ads } }
    }
}
