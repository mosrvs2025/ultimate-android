package app.ultimate.studio

import app.ultimate.insights.track
import app.ultimate.insights.trackProblem

import app.ultimate.R
import android.content.Context
import android.net.Uri
import app.ultimate.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class StudioManager(private val context: Context, private val hub: WorkHub) : WorkSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = StudioStore(context)
    private val history = StudioHistory()
    private val mutableProject = MutableStateFlow(StudioProject())
    val project = mutableProject.asStateFlow()
    val projects = MutableStateFlow<List<StudioProject>>(emptyList())
    val exports = MutableStateFlow<List<StudioExport>>(emptyList())
    val selected = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(true)
    val error = MutableStateFlow<String?>(null)
    val status = MutableStateFlow("All edits stay on your device")
    val revision = MutableStateFlow(0)
    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo
    private var saveJob: Job? = null
    private var exportJob: Job? = null
    override val notifyIcon = R.drawable.ic_stat_studio
    override val notifyColor = 0xFFAD1457.toInt()
    override val workId = "studio"
    override val workTitle = "Studio"
    override val log = JobLog()
    private val mutableWork = MutableStateFlow(WorkSnapshot())
    override val work = mutableWork.asStateFlow()
    init { scope.launch {
        try {
            projects.value = store.projects(); exports.value = store.exports()
            projects.value.firstOrNull()?.let { mutableProject.value = it; selected.value = it.clips.firstOrNull()?.id }
        } catch (e: Exception) { error.value = "Could not load Studio: ${e.message}" }
        finally { loading.value = false }
    } }
    /** Progress line while speech is being turned into captions, or null. */
    val captioning = MutableStateFlow<String?>(null)
    private var captionJob: kotlinx.coroutines.Job? = null

    /** Writes timed captions from what is said in [clipIds] (each clip's trimmed part), on the phone. */
    fun autoCaption(clipIds: List<String>) {
        if (captioning.value != null) return
        val transcriber = app.ultimate.speech.Transcriber(context)
        transcriber.unavailableReason()?.let { error.value = it; return }
        captionJob = scope.launch {
            try {
                clipIds.forEachIndexed { i, id ->
                    val clip = project.value.clips.firstOrNull { it.id == id } ?: return@forEachIndexed
                    captioning.value = "Listening to clip ${i + 1} of ${clipIds.size}…"
                    val segments = transcriber.transcribe(android.net.Uri.parse(clip.uri), clip.startMs, clip.endMs) { f ->
                        captioning.value = "Listening to clip ${i + 1} of ${clipIds.size} · ${(f * 100).toInt()}%"
                    }
                    edit { p -> p.copy(clips = p.clips.map { if (it.id == id) it.copy(captions = segments) else it }) }
                }
                status.value = "Captions added. Style them in Text."
                context.track("studio.autocaptions")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { error.value = e.message ?: "Captions couldn't be made"; context.trackProblem("Studio captions", error.value)
            } finally { captioning.value = null }
        }
    }

    fun cancelCaptions() { captionJob?.cancel() }

    fun edit(change: (StudioProject) -> StudioProject) {
        if (loading.value || work.value.active) return
        try {
            val before = project.value; val after = change(before)
            if (before == after) return
            history.record(before); apply(after.copy(updatedAt = System.currentTimeMillis()))
        } catch (e: Exception) { error.value = e.message ?: "Could not change project" }
    }
    private fun apply(p: StudioProject) {
        mutableProject.value = p
        if (p.clips.none { it.id == selected.value }) selected.value = p.clips.firstOrNull()?.id
        revision.value++
        saveJob?.cancel(); status.value = "Saving…"
        saveJob = scope.launch { delay(400); save(p) }
    }
    private suspend fun save(p: StudioProject) {
        try { store.save(p); projects.update { (listOf(p) + it.filterNot { old -> old.id == p.id }).sortedByDescending { it.updatedAt } }; status.value = "Saved on device" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error.value = "Could not save project: ${e.message}"; status.value = "Save failed — retry" }
    }
    fun retrySave() { saveJob?.cancel(); saveJob = scope.launch { save(project.value) } }
    fun updateClip(id: String, change: (StudioClip) -> StudioClip) = edit { p -> p.copy(clips = p.clips.map { if (it.id == id) change(it) else it }) }
    fun undo() { if (!work.value.active) history.undo(project.value)?.let(::apply) }
    fun redo() { if (!work.value.active) history.redo(project.value)?.let(::apply) }
    fun open(p: StudioProject) {
        if (work.value.active || loading.value || p.id == project.value.id) return
        val current = project.value
        saveJob?.cancel()
        loading.value = true
        scope.launch {
            try {
                store.save(current)
                projects.update { listOf(current) + it.filterNot { old -> old.id == current.id } }
                history.clear(); apply(p)
            } catch(e: Exception) { error.value = "Could not save current project: ${e.message}" }
            finally { loading.value = false }
        }
    }
    fun newProject() = open(StudioProject())
    fun addVideos(uris: List<Uri>) {
        if (loading.value || work.value.active || uris.isEmpty()) return
        loading.value = true; error.value = null
        scope.launch {
            val clips = mutableListOf<StudioClip>(); val failures = mutableListOf<String>()
            for (uri in uris) try { clips += store.video(uri) } catch(e: Exception) { failures += e.message ?: "File could not be imported" }
            loading.value = false
            if (clips.isNotEmpty()) { edit { it.copy(clips = it.clips + clips) }; selected.value = clips.first().id }
            if (failures.isNotEmpty()) error.value = failures.joinToString("\n")
        }
    }
    fun addMusic(uri: Uri) {
        if (loading.value || work.value.active) return
        loading.value = true; error.value = null
        scope.launch {
            try { val music = store.music(uri); loading.value = false; edit { it.copy(music = music) } }
            catch(e: Exception) { error.value = "Music import failed: ${e.message}" }
            finally { loading.value = false }
        }
    }
    fun move(id: String, delta: Int) = edit { p ->
        val list = p.clips.toMutableList(); val from = list.indexOfFirst { it.id == id }; val to = from + delta
        if (from >= 0 && to in list.indices) { val c = list.removeAt(from); list.add(to, c) }; p.copy(clips = list)
    }
    fun moveTo(id: String, index: Int) = edit { p -> p.copy(clips = Timeline.reorder(p.clips, p.clips.indexOfFirst { it.id == id }, index)) }
    fun split(id: String, atMs: Long) = edit { p -> p.copy(clips = p.clips.flatMap { if (it.id == id) Timeline.split(it, atMs) else listOf(it) }) }
    fun export() {
        if (work.value.active || loading.value) return
        val p = project.value
        try { Timeline.validate(p) } catch(e: Exception) { error.value = e.message; return }
        log.reset(); log.info("${p.name} · ${p.clips.size} clips · ${p.width}×${p.height} · H.264 / AAC")
        mutableWork.value = WorkSnapshot(active = true, title = "Rendering ${p.name}", percent = 0)
        try { hub.start() } catch(e: Exception) { mutableWork.value = WorkSnapshot(); error.value = "Cannot start export service: ${e.message}"; return }
        exportJob = scope.launch {
            var committed = false
            try {
                save(p)
                StudioRenderer(context).export(p, { percent -> mutableWork.update { it.copy(percent = percent) } }, log::detail) { result ->
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        val updated = listOf(result) + exports.value
                        exports.value = updated
                        try { store.exports(updated) }
                        catch(e: Exception) { error.value = "Film saved to Movies/Ultimate Studio, but its Library entry could not be saved: ${e.message}" }
                        log.ok("Saved ${result.name} · ${formatBytes(result.bytes)}")
                        committed = true
                        mutableWork.value = WorkSnapshot(doneTitle = "${result.name} is ready", doneText = "Saved to Movies/Ultimate Studio. Tap to watch or share it.")
                    }
                }
            } catch(e: CancellationException) {
                if (!committed) { log.warn("Export cancelled; incomplete output removed"); mutableWork.value = WorkSnapshot() }
                throw e
            }
            catch(e: Exception) {
                log.error("${e.javaClass.simpleName}: ${e.message ?: "Export failed"}")
                log.detail(e.stackTraceToString())
                if (committed) {
                    error.value = "Your video was saved to Movies/Ultimate Studio, but a follow-up step failed. See Details for the diagnostic log."
                    log.warn("Output was already saved. Do not export again just to recover this file.")
                } else {
                    val cause = e.cause?.message ?: e.message ?: "Unknown error"
                    error.value = "Export could not finish: $cause. Your project is kept; open Details and copy the log for diagnosis."
                    mutableWork.value = WorkSnapshot()
                }
            }
        }
    }
    override fun cancelWork() { exportJob?.cancel() }
    suspend fun removeExport(id: String, deleteFile: Boolean): Boolean {
        val item = exports.value.firstOrNull { it.id == id } ?: return true
        if (deleteFile) {
            val removed = withContext(Dispatchers.IO) { context.contentResolver.delete(Uri.parse(item.uri), null, null) > 0 }
            if (!removed) return false
        }
        val updated = exports.value.filterNot { it.id == id }; store.exports(updated); exports.value = updated
        return true
    }
}
