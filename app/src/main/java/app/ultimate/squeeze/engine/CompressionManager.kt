package app.ultimate.squeeze.engine

import app.ultimate.R
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import app.ultimate.core.JobLog
import app.ultimate.core.ProgressTicker
import app.ultimate.core.WorkHub
import app.ultimate.core.formatDuration
import app.ultimate.core.formatEta
import app.ultimate.core.formatMbps
import app.ultimate.core.percentSaved
import java.util.Locale
import app.ultimate.core.WorkSnapshot
import app.ultimate.core.WorkSource
import app.ultimate.core.formatBytes
import app.ultimate.squeeze.model.LibraryEntry
import app.ultimate.squeeze.model.CropShape
import app.ultimate.squeeze.model.Mode
import app.ultimate.squeeze.model.Plan
import app.ultimate.squeeze.model.Planner
import app.ultimate.squeeze.model.Settings
import app.ultimate.squeeze.model.Source
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Status {
    data object Queued : Status
    data class Running(val progress: Float, val etaMs: Long?, val speedX: Float?) : Status
    data class Done(val output: Uri, val outputBytes: Long, val libraryId: String?) : Status
    data class Skipped(val reason: String) : Status
    data class Failed(val message: String) : Status
    data object Cancelled : Status
}

data class Item(val id: Int, val uri: Uri, val name: String, val source: Source, val plan: Plan, val status: Status = Status.Queued)

data class QueueState(val jobs: List<Item> = emptyList(), val active: Boolean = false) {
    val finished get() = !active && jobs.isNotEmpty()
    val savedBytes get() = jobs.sumOf { (it.status as? Status.Done)?.let { d -> it.source.sizeBytes - d.outputBytes } ?: 0L }
}

/** Owns the compression queue. The foreground service only keeps the process alive and mirrors this state. */
class CompressionManager(private val app: Context, private val library: LibraryStore, private val hub: WorkHub) : WorkSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val compressor = VideoCompressor(app)
    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    override val notifyIcon = R.drawable.ic_stat_squeeze
    override val notifyColor = 0xFF00897B.toInt()
    override val workId = "squeeze"
    override val workTitle = "Squeeze"
    override val log = JobLog()
    private val ticker = ProgressTicker(log)
    override val work: StateFlow<WorkSnapshot> = _state.map(::snapshot).stateIn(scope, SharingStarted.Eagerly, WorkSnapshot())
    override fun cancelWork() = cancel()

    private fun snapshot(s: QueueState): WorkSnapshot {
        if (s.active) {
            val total = s.jobs.size.coerceAtLeast(1)
            val finished = s.jobs.count { it.status !is Status.Queued && it.status !is Status.Running }
            val current = s.jobs.firstOrNull { it.status is Status.Running }
            val p = (current?.status as? Status.Running)?.progress ?: 0f
            return WorkSnapshot(
                active = true, title = "Compressing ${minOf(finished + 1, total)} of $total", detail = current?.name,
                percent = ((finished + p) / total * 100).toInt().coerceIn(0, 100),
            )
        }
        if (s.jobs.isEmpty()) return WorkSnapshot()
        val done = s.jobs.count { it.status is Status.Done }
        return WorkSnapshot(
            doneTitle = if (done == 0) "Squeeze finished" else "${formatBytes(s.savedBytes)} freed",
            doneText = if (done == 0) "Nothing needed compressing" else s.jobs.filter { it.status is Status.Done }.joinToString(limit = 3) { it.name } + " · $done video${if (done == 1) "" else "s"} smaller",
        )
    }
    private var runner: Job? = null

    private val workDir get() = File(app.cacheDir, "work").apply { mkdirs() }

    fun submit(items: List<Pending>, settings: Settings) {
        if (_state.value.active || items.isEmpty()) return
        workDir.listFiles()?.forEach { it.delete() }
        val jobs = items.mapIndexed { i, p ->
            val skip = p.plan.skipReason
            Item(i, p.uri, p.name, p.source, p.plan, if (skip != null) Status.Skipped(skip) else Status.Queued)
        }
        _state.value = QueueState(jobs, active = true)
        log.reset()
        log.info("Squeeze · ${jobs.size} video${if (jobs.size == 1) "" else "s"} · ${settingsLine(settings)}")
        val caps = DeviceCodecs.detect()
        log.detail("Hardware encoders: HEVC ${if (caps.hevc) "yes" else "no"} · AV1 ${if (caps.av1) "yes" else "no"}")
        hub.start()
        runner = scope.launch { runAll(settings) }
    }

    fun cancel() {
        runner?.cancel()
    }

    /** Leaves the results screen. No-op while work is running. */
    fun dismiss() {
        if (!_state.value.active) _state.value = QueueState()
    }

    private suspend fun runAll(settings: Settings) {
        try {
            for (job in _state.value.jobs) {
                log.info("▶ ${job.name}")
                log.detail("  source  ${sourceLine(job.source)}")
                val skipped = job.status as? Status.Skipped
                if (skipped != null) {
                    log.warn("  skip    ${skipped.reason}")
                    continue
                }
                log.detail("  plan    ${planLine(job.plan)}")
                job.plan.warning?.let { log.warn("  note    $it") }
                val result = try {
                    runOne(job, settings)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Status.Failed(e.message?.take(160) ?: "Could not compress this video")
                }
                when (result) {
                    is Status.Done -> log.ok("  done    ${formatBytes(job.source.sizeBytes)} → ${formatBytes(result.outputBytes)} (−${percentSaved(job.source.sizeBytes, result.outputBytes)}%)")
                    is Status.Skipped -> log.warn("  kept    ${result.reason}")
                    is Status.Failed -> log.error("  failed  ${result.message}")
                    else -> Unit
                }
                setStatus(job.id, result)
            }
            val s = _state.value
            log.ok("Finished · ${s.jobs.count { it.status is Status.Done }} saved · ${formatBytes(s.savedBytes)} freed")
        } catch (e: CancellationException) {
            log.warn("Cancelled")
            _state.update { s ->
                s.copy(jobs = s.jobs.map { if (it.status is Status.Queued || it.status is Status.Running) it.copy(status = Status.Cancelled) else it })
            }
            throw e
        } finally {
            _state.update { it.copy(active = false) }
        }
    }

    private suspend fun runOne(job: Item, settings: Settings): Status {
        val tmp = File(workDir, "job${job.id}.mp4")
        val started = SystemClock.elapsedRealtime()
        ticker.reset()
        try {
            val facts = compressor.compress(job.uri, tmp, job.plan, job.source.isHdr, settings.muteAudio) { p ->
                val elapsed = SystemClock.elapsedRealtime() - started
                val eta = if (p > 0.03f) (elapsed / p * (1 - p)).toLong() else null
                val speed = if (p > 0.03f && elapsed > 0) job.source.durationMs * p / elapsed else null
                setStatus(job.id, Status.Running(p, eta, speed))
                ticker.tick(p) { f -> "  ${(f * 100).toInt()}%".padEnd(10) + (speed?.let { String.format(Locale.US, "%.1f× real time", it) } ?: "") + (eta?.let { " · ${formatEta(it)}" } ?: "") }
            }
            val took = SystemClock.elapsedRealtime() - started
            log.detail("  encoder ${facts.videoEncoder ?: "?"} · audio ${facts.audioEncoder ?: "passthrough"}")
            log.detail(
                "  output  ${facts.width}×${facts.height} · ${facts.frames} frames · video " +
                    (if (facts.averageVideoBitrate > 0) formatMbps(facts.averageVideoBitrate) else "?") +
                    " (asked ${formatMbps(job.plan.videoBitrate)}) · took ${formatDuration(took)}",
            )
            val outBytes = tmp.length()
            if (outBytes <= 0) throw IOException("Encoder produced no output")
            // Guard: a lossy re-encode that is not clearly smaller is worse than the original.
            if (outBytes > job.source.sizeBytes * (1 - MIN_ACTUAL_SAVING)) {
                return Status.Skipped("Result was not smaller, so your original was kept")
            }
            val encodeMs = SystemClock.elapsedRealtime() - started
            val uri = saveToGallery(tmp, outputName(job.name))
            val id = recordInLibrary(job, settings, uri, outBytes, encodeMs)
            return Status.Done(uri, outBytes, id)
        } finally {
            withContext(NonCancellable) { tmp.delete() }
        }
    }

    /** Library bookkeeping must never turn a successful compression into a failure. */
    private suspend fun recordInLibrary(job: Item, settings: Settings, output: Uri, outBytes: Long, encodeMs: Long): String? = try {
        val id = UUID.randomUUID().toString()
        val measured = withContext(Dispatchers.IO) { VideoProbe.probe(app, output).source }
        val after = measured?.copy(sizeBytes = outBytes) ?: job.source.copy(
            sizeBytes = outBytes, width = job.plan.width, height = job.plan.height,
            videoBitrate = job.plan.videoBitrate, mime = job.plan.codec.mime,
        )
        // Before/after views line the two videos up in time and space; after a speed change or crop they would not match, so skip them.
        val hasFrames = job.plan.speed == 1f && library.saveFrames(id, job.uri, output, job.source.durationMs)
        val clipStart = if (job.plan.speed == 1f && job.plan.crop == null) library.saveClip(id, job.uri, job.source.durationMs) else -1L
        val mode = when (settings.mode) {
            Mode.Preset -> settings.preset.label
            Mode.TargetSize -> "≤ ${settings.targetMb} MB"
            Mode.Exact -> "${settings.exactKbps} kbps"
        } + (if (job.plan.crop != null) " · ${settings.crop.label}" else "") + if (job.plan.speed != 1f) " · ${job.plan.speed}×" else ""
        library.add(
            LibraryEntry(id, System.currentTimeMillis(), job.name, output.toString(), job.source, after, encodeMs, job.plan.estimatedBytes, "$mode · ${job.plan.codec.label}", hasFrames, clipStart),
        )
        id
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun settingsLine(s: Settings) =
        when (s.mode) {
            Mode.Preset -> s.preset.label
            Mode.TargetSize -> "target ≤ ${s.targetMb} MB"
            Mode.Exact -> "exact ${s.exactKbps} kbps" + (s.exactShortSide?.let { " at ${it}p" } ?: "")
        } + " · codec ${s.codec.label}" + (if (s.crop != CropShape.Original) " · crop ${s.crop.label}" else "") + (if (s.speed != 1f) " · ${s.speed}×" else "") +
            (s.maxShortSide?.let { " · ≤${it}p" } ?: "") + (s.maxFps?.let { " · ≤$it fps" } ?: "") + if (s.muteAudio) " · no audio" else ""

    private fun sourceLine(s: Source) =
        "${s.width}×${s.height} · ${String.format(Locale.US, "%.2f", s.fps)} fps · ${s.mime.substringAfter('/')} · ${formatMbps(s.videoBitrate)} · " +
            "${formatDuration(s.durationMs)} · ${formatBytes(s.sizeBytes)}" + if (s.isHdr) " · HDR" else ""

    private fun planLine(p: Plan) =
        "${p.codec.label} ${p.width}×${p.height}" + (p.fps?.let { " · $it fps" } ?: "") +
            " · ${formatMbps(p.videoBitrate)} VBR · est ${formatBytes(p.estimatedBytes)}"

    private fun setStatus(id: Int, status: Status) = _state.update { s ->
        s.copy(jobs = s.jobs.map { if (it.id == id) it.copy(status = status) else it })
    }

    private suspend fun saveToGallery(file: File, displayName: String): Uri = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Squeeze")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Could not create the output file")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Could not open the output file")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        uri
    }

    private fun outputName(original: String): String = original.substringBeforeLast('.', original).ifBlank { "video" } + "_squeezed.mp4"

    data class Pending(val uri: Uri, val name: String, val source: Source, val plan: Plan)

    private companion object {
        const val MIN_ACTUAL_SAVING = 0.03
    }
}
