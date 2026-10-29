package app.ultimate.recipes

import android.net.Uri
import app.ultimate.UltimateApp
import app.ultimate.audio.engine.AudioProbe
import app.ultimate.audio.engine.AudioStatus
import app.ultimate.audio.model.AudioSettings
import app.ultimate.photos.PhotoSettings
import app.ultimate.pluck.engine.JobStatus
import app.ultimate.speech.Captions
import app.ultimate.speech.Transcriber
import app.ultimate.squeeze.engine.CompressionManager
import app.ultimate.squeeze.engine.Status
import app.ultimate.squeeze.engine.VideoProbe
import app.ultimate.squeeze.model.Planner
import app.ultimate.squeeze.model.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What one tool run produced: the new file (if any) and a line saying what happened. */
data class ActionResult(val output: Uri?, val line: String)

/**
 * Runs one tool on one file through that tool's own manager, waiting until it finishes, so recipes and the computer dashboard
 * get the same progress, notifications and Library entries as doing it by hand on the phone.
 */
class ToolActions(private val app: UltimateApp) {
    suspend fun shrink(uri: Uri, settings: Settings = Settings()): ActionResult {
        val m = app.squeezeManager
        val probed = withContext(Dispatchers.IO) { VideoProbe.probe(app, uri) }
        val src = probed.source ?: error("can't read this video")
        check(!m.state.value.active) { "Squeeze is busy with something else" }
        m.submit(listOf(CompressionManager.Pending(uri, probed.name, src, Planner.plan(src, settings, app.capabilities))), settings)
        val done = m.state.first { !it.active && it.jobs.isNotEmpty() }
        return when (val st = done.jobs.firstOrNull()?.status) {
            is Status.Done -> ActionResult(st.output, "Made smaller: ${probed.name}")
            is Status.Skipped -> ActionResult(null, "Already small: ${probed.name}")
            else -> error("Squeeze didn't finish")
        }
    }

    suspend fun audio(uri: Uri, settings: AudioSettings): ActionResult {
        val m = app.audioManager
        val picked = withContext(Dispatchers.IO) { AudioProbe.probe(app, uri) }
        if (picked.audio == null) error("no sound in ${picked.name}")
        check(!m.state.value.active) { "Video to audio is busy" }
        m.submit(listOf(picked), settings)
        val done = m.state.first { !it.active && it.jobs.isNotEmpty() }
        return when (val st = done.jobs.firstOrNull()?.status) {
            is AudioStatus.Done -> ActionResult(Uri.parse(st.entry.uri), "${settings.format.label}: ${st.entry.displayName}")
            is AudioStatus.Failed -> error(st.message)
            else -> error("conversion didn't finish")
        }
    }

    suspend fun transcribe(uri: Uri): ActionResult {
        val segments = Transcriber(app).transcribe(uri)
        app.transcripts.put(uri.toString(), Captions.transcript(segments))
        return ActionResult(null, if (segments.isEmpty()) "No speech found" else "Transcript: ${segments.size} lines (in the Library)")
    }

    suspend fun photo(uri: Uri, settings: PhotoSettings): ActionResult {
        val m = app.photos
        val picked = withContext(Dispatchers.IO) { m.probe(uri) } ?: error("can't read this photo")
        check(!m.run.value.active) { "Photos is busy" }
        m.process(listOf(picked), settings)
        val done = m.run.first { !it.active && it.total > 0 }
        val r = done.results.firstOrNull() ?: error("photo couldn't be saved")
        m.dismiss()
        return ActionResult(Uri.parse(r.uri), "Photo: ${r.name}")
    }

    /** Saves an installed app as an APK (or .apks for split apps) in Downloads/Pluck. */
    suspend fun pluck(packageName: String): ActionResult {
        val m = app.pluckManager
        val item = app.pluckRepository.loadAll().firstOrNull { it.packageName == packageName } ?: error("$packageName isn't installed")
        check(!m.state.value.active) { "Pluck is busy" }
        m.submit(listOf(item), compact = false)
        val done = m.state.first { !it.active && it.jobs.isNotEmpty() }
        return when (val st = done.jobs.firstOrNull()?.status) {
            is JobStatus.Done -> ActionResult(Uri.parse(st.entry.uri), "Saved ${item.label} ${item.versionName}")
            is JobStatus.Failed -> error(st.message)
            else -> error("Pluck didn't finish")
        }
    }
}
