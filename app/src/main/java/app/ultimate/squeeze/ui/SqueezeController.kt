package app.ultimate.squeeze.ui

import android.net.Uri
import app.ultimate.UltimateApp
import app.ultimate.squeeze.engine.CompressionManager
import app.ultimate.squeeze.engine.Probed
import app.ultimate.squeeze.engine.VideoProbe
import app.ultimate.squeeze.model.Plan
import app.ultimate.squeeze.model.Planner
import app.ultimate.squeeze.model.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A picked video with its plan under the current settings. [probed].source is null when the file cannot be read. */
data class Entry(val probed: Probed, val plan: Plan?)

/** App-lifetime state for the Squeeze tool. It outlives the screen so a library item can open straight into a detail view. */
class SqueezeController(private val app: UltimateApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = app.squeezeManager
    val capabilities = app.capabilities

    private val picked = MutableStateFlow<List<Probed>>(emptyList())
    private val probing = MutableStateFlow(0)
    val settings = MutableStateFlow(Settings())
    val queue = manager.state
    private val store = app.squeezeLibrary
    val library = store.entries
    val detailId = MutableStateFlow<String?>(null)
    val isProbing: StateFlow<Boolean> = probing.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)

    val entries: StateFlow<List<Entry>> = combine(picked, settings) { list, s ->
        list.map { p -> Entry(p, p.source?.let { Planner.plan(it, s, capabilities) }) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun add(uris: List<Uri>) {
        val fresh = uris.distinct().filter { u -> picked.value.none { it.uri == u } }
        if (fresh.isEmpty()) return
        probing.update { it + fresh.size }
        scope.launch {
            for (u in fresh) {
                val probed = withContext(Dispatchers.IO) { VideoProbe.probe(app, u) }
                picked.update { it + probed }
                probing.update { it - 1 }
            }
        }
    }

    fun remove(uri: Uri) = picked.update { list -> list.filterNot { it.uri == uri } }

    fun clear() {
        picked.value = emptyList()
    }

    fun update(change: (Settings) -> Settings) = settings.update(change)

    fun start() {
        val pending = entries.value.mapNotNull { e ->
            val src = e.probed.source ?: return@mapNotNull null
            val plan = e.plan ?: return@mapNotNull null
            CompressionManager.Pending(e.probed.uri, e.probed.name, src, plan)
        }
        manager.submit(pending, settings.value)
        picked.value = emptyList()
    }

    fun frameFile(id: String, before: Boolean) = if (before) store.beforeFrame(id) else store.afterFrame(id)

    fun clipFile(id: String) = store.clipFile(id)

    private var returnToLibrary = false

    fun openDetail(id: String, fromLibrary: Boolean = false) {
        returnToLibrary = fromLibrary
        detailId.value = id
    }

    /** Closes the detail view; if it was opened from the shared Library, leaves the tool so Back lands there. */
    fun closeDetail(onExit: () -> Unit) {
        detailId.value = null
        if (returnToLibrary) {
            returnToLibrary = false
            onExit()
        }
    }

    suspend fun refreshLibrary() = store.refresh()

    suspend fun removeFromLibrary(id: String, deleteFile: Boolean) = store.remove(id, deleteFile)

    val log get() = manager.log

    fun cancel() = manager.cancel()

    fun dismissResults() = manager.dismiss()
}
