package app.ultimate.pluck.engine

import app.ultimate.R
import android.content.Context
import app.ultimate.core.JobLog
import app.ultimate.core.WorkHub
import app.ultimate.core.formatBytes
import app.ultimate.core.WorkSnapshot
import app.ultimate.core.WorkSource
import app.ultimate.pluck.model.AppItem
import app.ultimate.pluck.model.FileNames
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface JobStatus {
    data object Queued : JobStatus
    data class Running(val done: Long, val total: Long) : JobStatus
    data class Done(val entry: SavedEntry) : JobStatus
    data class Failed(val message: String) : JobStatus
    data object Cancelled : JobStatus
}

data class ExtractJob(val app: AppItem, val status: JobStatus = JobStatus.Queued)

data class ExtractState(val jobs: List<ExtractJob> = emptyList(), val active: Boolean = false) {
    val saved: List<SavedEntry> get() = jobs.mapNotNull { (it.status as? JobStatus.Done)?.entry }
}

/** Owns the extraction queue. The foreground service only keeps the process alive and mirrors this state. */
class ExtractManager(private val app: Context, private val history: HistoryStore, private val hub: WorkHub) : WorkSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val extractor = Extractor(app)
    private val _state = MutableStateFlow(ExtractState())
    val state: StateFlow<ExtractState> = _state.asStateFlow()

    override val notifyIcon = R.drawable.ic_stat_pluck
    override val notifyColor = 0xFF2E7D32.toInt()
    override val workId = "pluck"
    override val workTitle = "Pluck"
    override val log = JobLog()
    override val work: StateFlow<WorkSnapshot> = _state.map(::snapshot).stateIn(scope, SharingStarted.Eagerly, WorkSnapshot())
    override fun cancelWork() = cancel()

    private fun snapshot(s: ExtractState): WorkSnapshot {
        if (s.active) {
            val total = s.jobs.size.coerceAtLeast(1)
            val finished = s.jobs.count { it.status !is JobStatus.Queued && it.status !is JobStatus.Running }
            val current = s.jobs.firstOrNull { it.status is JobStatus.Running }
            val run = current?.status as? JobStatus.Running
            val fraction = if (run != null && run.total > 0) run.done.toFloat() / run.total else 0f
            return WorkSnapshot(
                active = true, title = "Extracting ${minOf(finished + 1, total)} of $total", detail = current?.app?.label,
                percent = ((finished + fraction) / total * 100).toInt().coerceIn(0, 100),
            )
        }
        if (s.jobs.isEmpty()) return WorkSnapshot()
        val n = s.saved.size
        return WorkSnapshot(
            doneTitle = if (n == 1) "${s.jobs.first().app.label} saved" else "Pluck finished",
            doneText = if (n == 0) "Nothing was extracted" else "$n app${if (n == 1) "" else "s"} saved to Downloads/Pluck",
        )
    }
    private var runner: Job? = null

    fun submit(apps: List<AppItem>, compact: Boolean, nameTemplate: String = FileNames.DEFAULT) {
        if (_state.value.active || apps.isEmpty()) return
        _state.value = ExtractState(apps.map { ExtractJob(it) }, active = true)
        log.reset()
        log.info("Pluck · ${apps.size} app${if (apps.size == 1) "" else "s"} · ${formatBytes(apps.sumOf { it.apkBytes })} · ${if (compact) "compact" else "APK"}")
        hub.start()
        runner = scope.launch { runAll(compact, nameTemplate) }
    }

    fun cancel() {
        runner?.cancel()
    }

    /** Leaves the results screen. No-op while work is running. */
    fun dismiss() {
        if (!_state.value.active) _state.value = ExtractState()
    }

    private suspend fun runAll(compact: Boolean, nameTemplate: String) {
        try {
            for (i in _state.value.jobs.indices) {
                val job = _state.value.jobs[i]
                log.info("▶ ${job.app.label} ${job.app.versionName} (${job.app.packageName})")
                val status = try {
                    update(i, JobStatus.Running(0, job.app.apkBytes))
                    val out = extractor.extract(job.app, compact, nameTemplate, log) { done, total -> update(i, JobStatus.Running(done, total)) }
                    log.ok("  saved   Download/Pluck/${out.displayName} · ${formatBytes(out.bytes)}")
                    val entry = SavedEntry(
                        UUID.randomUUID().toString(), System.currentTimeMillis(), job.app.packageName, job.app.label, job.app.versionName,
                        out.uri.toString(), out.displayName, out.bytes, out.splitCount, out.sha256,
                    )
                    history.add(entry)
                    JobStatus.Done(entry)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("  failed  ${e.javaClass.simpleName}: ${e.message}")
                    JobStatus.Failed(e.message?.take(160) ?: "Could not extract this app")
                }
                update(i, status)
            }
            log.ok("Finished · ${_state.value.saved.size} saved · ${formatBytes(_state.value.saved.sumOf { it.bytes })}")
        } catch (e: CancellationException) {
            log.warn("Cancelled")
            _state.update { s ->
                s.copy(jobs = s.jobs.map { if (it.status is JobStatus.Queued || it.status is JobStatus.Running) it.copy(status = JobStatus.Cancelled) else it })
            }
            throw e
        } finally {
            _state.update { it.copy(active = false) }
        }
    }

    private fun update(index: Int, status: JobStatus) = _state.update { s ->
        s.copy(jobs = s.jobs.mapIndexed { i, j -> if (i == index) j.copy(status = status) else j })
    }
}
