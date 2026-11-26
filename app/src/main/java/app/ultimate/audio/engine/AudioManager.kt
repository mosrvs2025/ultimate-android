package app.ultimate.audio.engine

import app.ultimate.core.MediaName
import app.ultimate.core.renameMedia
import app.ultimate.R
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import app.ultimate.audio.model.AudioPlanner
import app.ultimate.audio.model.AudioSettings
import app.ultimate.audio.model.AudioSource
import app.ultimate.audio.model.OutFormat
import app.ultimate.audio.model.SplitPlan
import app.ultimate.core.JobLog
import app.ultimate.core.ProgressTicker
import app.ultimate.core.WorkHub
import app.ultimate.core.formatDuration
import java.util.Locale
import app.ultimate.core.WorkSnapshot
import app.ultimate.core.WorkSource
import app.ultimate.core.formatBytes
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AudioStatus {
    data object Queued : AudioStatus
    data class Running(val progress: Float, val peaks: List<Float>, val speedX: Float?) : AudioStatus
    data class Done(val entry: AudioEntry) : AudioStatus
    data class Failed(val message: String) : AudioStatus
    data object Cancelled : AudioStatus
}

/**
 * One output file: a single input, or several joined in order, or one part of a long input that is being split. [source] describes
 * the first input, for thumbnails. A part has its own [startMs] to [endMs] on the original recording ([endMs] below zero: not a part).
 */
data class AudioJob(
    val id: Int, val inputs: List<ConvertInput>, val name: String, val status: AudioStatus = AudioStatus.Queued,
    val startMs: Long = 0, val endMs: Long = -1,
) {
    val isPart get() = endMs > 0
    val uri: Uri get() = inputs.first().uri
    val source: AudioSource get() = inputs.first().source
}

data class AudioQueue(val jobs: List<AudioJob> = emptyList(), val active: Boolean = false) {
    val done: List<AudioEntry> get() = jobs.mapNotNull { (it.status as? AudioStatus.Done)?.entry }
}

/** Owns the conversion queue; the shared work service only keeps the process alive and mirrors [work]. */
class AudioManager(private val app: Context, private val store: AudioStore, private val hub: WorkHub) : WorkSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val converter = AudioConverter(app)
    private val _state = MutableStateFlow(AudioQueue())
    val state: StateFlow<AudioQueue> = _state.asStateFlow()
    private var runner: Job? = null

    override val notifyIcon = R.drawable.ic_stat_audio
    override val notifyColor = 0xFFE65100.toInt()
    override val workId = "audio"
    override val workTitle = "Video to audio"
    override val log = JobLog()
    private val ticker = ProgressTicker(log)
    override val work: StateFlow<WorkSnapshot> = _state.map(::snapshot).stateIn(scope, SharingStarted.Eagerly, WorkSnapshot())
    override fun cancelWork() = cancel()

    private fun snapshot(s: AudioQueue): WorkSnapshot {
        if (s.active) {
            val total = s.jobs.size.coerceAtLeast(1)
            val finished = s.jobs.count { it.status !is AudioStatus.Queued && it.status !is AudioStatus.Running }
            val current = s.jobs.firstOrNull { it.status is AudioStatus.Running }
            val p = (current?.status as? AudioStatus.Running)?.progress ?: 0f
            return WorkSnapshot(
                active = true, title = "Converting ${minOf(finished + 1, total)} of $total", detail = current?.name,
                percent = ((finished + p) / total * 100).toInt().coerceIn(0, 100),
            )
        }
        if (s.jobs.isEmpty()) return WorkSnapshot()
        val n = s.done.size
        return WorkSnapshot(
            doneTitle = if (n == 1) "${s.jobs.first().name} is ready" else "Audio ready",
            doneText = if (n == 0) "Nothing was converted" else "$n file${if (n == 1) "" else "s"} · ${formatBytes(s.done.sumOf { it.bytes })} in Music/Ultimate",
        )
    }

    fun submit(videos: List<PickedVideo>, requested: AudioSettings) {
        if (_state.value.active) return
        // Only phones with an Opus encoder can make Opus; the rest get a small MP3 (and the log says so).
        val noOpus = requested.format == OutFormat.Opus && !OpusSupport.available
        val settings = if (noOpus) AudioPlanner.withoutOpus(requested) else requested
        val inputs = videos.mapNotNull { v -> v.audio?.let { ConvertInput(v.uri, v.name, it) } }
        if (inputs.isEmpty()) return
        val join = settings.join && inputs.size > 1
        // Trimming only makes sense for a single file, so it is ignored for batches and joins.
        val effective = if (inputs.size == 1) settings else settings.copy(trimStartMs = 0, trimEndMs = -1)
        val jobs = when {
            join -> listOf(AudioJob(0, inputs, AudioPlanner.joinedName(inputs[0].name, inputs.size)))
            effective.splitMin > 0 -> {
                // A long file becomes one job per part, so the queue, the progress and the results already work for them.
                var id = 0
                inputs.flatMap { input ->
                    val parts = AudioPlanner.parts(input.source, effective)
                    if (parts.size < 2) listOf(AudioJob(id++, listOf(input), input.name))
                    else parts.map { p ->
                        val name = SplitPlan.partName(AudioConverter.baseName(input.name), p.index, parts.size)
                        AudioJob(id++, listOf(input.copy(name = name)), name, startMs = p.startMs, endMs = p.endMs)
                    }
                }
            }
            else -> inputs.mapIndexed { i, input -> AudioJob(i, listOf(input), input.name) }
        }
        _state.value = AudioQueue(jobs, active = true)
        log.reset()
        log.info(
            "Audio · ${inputs.size} file${if (inputs.size == 1) "" else "s"}${if (join) " joined into one" else ""} · ${settings.format.label}" +
                (if (settings.format == OutFormat.Mp3 && settings.vbr >= 0) " variable rate, level ${settings.vbr}" else if (settings.format != OutFormat.Wav) " ${settings.kbps} kbps" else "") +
                (if (settings.mono) " · mono" else "") + (if (settings.speed != 1f) " · ${settings.speed}×" else "") +
                (if (jobs.size > inputs.size && !join) " · ${jobs.size} parts of about ${SplitPlan.minutesLabel(settings.splitMin)}" else ""),
        )
        if (noOpus) log.warn("This phone has no Opus encoder, so these are small MP3 files instead")
        videos.filter { it.audio == null }.forEach { log.warn("skip ${it.name}: no audio track") }
        hub.start()
        runner = scope.launch { runAll(effective) }
    }

    fun cancel() {
        runner?.cancel()
    }

    /** Renames a finished file and everything that remembers its name. Returns the name used, or why it failed. */
    suspend fun rename(entry: AudioEntry, typed: String): Result<String> {
        val wanted = MediaName.clean(typed, entry.displayName) ?: return Result.failure(IllegalArgumentException("Type a name"))
        val result = renameMedia(app, Uri.parse(entry.uri), wanted)
        result.onSuccess { final ->
            store.rename(entry.id, final)
            _state.update { s -> s.copy(jobs = s.jobs.map { j -> (j.status as? AudioStatus.Done)?.takeIf { it.entry.id == entry.id }?.let { j.copy(status = AudioStatus.Done(it.entry.copy(displayName = final))) } ?: j }) }
            log.ok("  renamed ${entry.displayName} to $final")
        }
        return result
    }

    /** Leaves the results screen. No-op while work is running. */
    fun dismiss() {
        if (!_state.value.active) _state.value = AudioQueue()
    }

    /**
     * Moves each cut between the parts of a split file to the quietest moment close to it, so a part ends in a pause instead of in
     * the middle of a word. A cut whose stretch can't be read keeps its planned place.
     */
    private suspend fun nudgeCuts() {
        val jobs = _state.value.jobs
        val byInput = jobs.withIndex().filter { it.value.isPart }.groupBy { it.value.inputs.first().uri }
        for ((_, group) in byInput) {
            if (group.size < 2) continue
            val input = group.first().value.inputs.first()
            val parts = group.map { SplitPlan.Part(it.index, it.value.startMs, it.value.endMs) }
            log.info("▶ Looking for quiet moments to cut at (${parts.size - 1})")
            val cuts = parts.drop(1).mapIndexed { n, p ->
                val found = runCatching { converter.quietMoment(input, p.startMs) }.getOrNull()
                if (found == null) log.warn("  cut ${n + 1}  couldn't look near ${formatDuration(p.startMs)}; cutting there")
                else log.detail("  cut ${n + 1}  ${formatDuration(found)} (planned ${formatDuration(p.startMs)})")
                found
            }
            val moved = SplitPlan.nudge(parts, cuts)
            _state.update { s ->
                s.copy(jobs = s.jobs.mapIndexed { i, j ->
                    val k = group.indexOfFirst { it.index == i }
                    if (k < 0) j else j.copy(startMs = moved[k].startMs, endMs = moved[k].endMs)
                })
            }
        }
    }

    private suspend fun runAll(base: AudioSettings) {
        try {
            if (base.splitMin > 0 && base.splitQuiet) nudgeCuts()
            for (i in _state.value.jobs.indices) {
                val job = _state.value.jobs[i]
                // A part is its own trim of the original; everything else about the sound is the same for every part.
                val settings = (if (job.isPart) base.copy(trimStartMs = job.startMs, trimEndMs = job.endMs) else base).copy(splitMin = 0)
                val started = SystemClock.elapsedRealtime()
                ticker.reset()
                log.info("▶ ${job.name}")
                val status = try {
                    update(i, AudioStatus.Running(0f, emptyList(), null))
                    val kept = job.inputs.sumOf { AudioPlanner.keptMs(it.source, settings) }
                    val out = converter.convert(job.inputs, settings, log) { fraction, peaks ->
                        val elapsed = SystemClock.elapsedRealtime() - started
                        val speed = if (fraction > 0.03f && elapsed > 0) kept * fraction / elapsed else null
                        update(i, AudioStatus.Running(fraction, peaks, speed))
                        ticker.tick(fraction) { f -> "  ${(f * 100).toInt()}%".padEnd(10) + (speed?.let { String.format(Locale.US, "%.1f× real time", it) } ?: "") }
                    }
                    log.ok("  done    ${out.displayName} · ${formatBytes(out.bytes)} · took ${formatDuration(SystemClock.elapsedRealtime() - started)}")
                    val entry = AudioEntry(
                        UUID.randomUUID().toString(), System.currentTimeMillis(), job.name, out.displayName, out.uri.toString(), out.bytes,
                        out.durationMs, out.format, out.bitrate, out.sampleRate, out.channels, out.peaks,
                    )
                    store.add(entry)
                    AudioStatus.Done(entry)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("  failed  ${e.javaClass.simpleName}: ${e.message}")
                    AudioStatus.Failed(e.message?.take(160) ?: "Could not convert this video")
                }
                update(i, status)
            }
            log.ok("Finished · ${_state.value.done.size} file${if (_state.value.done.size == 1) "" else "s"} in Music/Ultimate")
        } catch (e: CancellationException) {
            log.warn("Cancelled")
            _state.update { s ->
                s.copy(jobs = s.jobs.map { if (it.status is AudioStatus.Queued || it.status is AudioStatus.Running) it.copy(status = AudioStatus.Cancelled) else it })
            }
            throw e
        } finally {
            _state.update { it.copy(active = false) }
        }
    }

    private fun update(index: Int, status: AudioStatus) = _state.update { s ->
        s.copy(jobs = s.jobs.mapIndexed { i, j -> if (i == index) j.copy(status = status) else j })
    }
}
