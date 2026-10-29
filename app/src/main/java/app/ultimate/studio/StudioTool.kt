package app.ultimate.studio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.runtime.Composable
import app.ultimate.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class StudioTool(private val manager: StudioManager) : Tool {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val id = "studio"
    override val title = "Studio"
    override val tagline = "Turn your clips and music into a film. Offline."
    override val icon = Icons.Rounded.Movie
    // Picked files keep their access across restarts; files handed over by another tool keep it while the app is open.
    override val acceptedMimePrefixes = listOf("video/")
    override val headline: StateFlow<String> = manager.project.map { if (it.clips.isEmpty()) "Edit · score · export" else "${it.clips.size} clips · ${formatDuration(it.durationMs)} in progress" }.stateIn(scope, SharingStarted.Eagerly, "Edit · score · export")
    override val library: StateFlow<List<LibraryItem>> = manager.exports.map { entries -> entries.map { e ->
        LibraryItem(e.id, id, e.name, "${e.width}×${e.height} · ${formatDuration(e.durationMs)} · ${formatBytes(e.bytes)}", e.createdAt, e.bytes, 0, e.uri,
            Thumb.Generic, listOf("Format" to "H.264 / AAC MP4", "Canvas" to "${e.width}×${e.height}", "Duration" to formatDuration(e.durationMs)), "video/mp4")
    } }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = manager.removeExport(id, deleteFile)
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        androidx.compose.runtime.LaunchedEffect(launch) {
            if (!launch.consumed && launch.uris.isNotEmpty()) { launch.consumed = true; manager.addVideos(launch.uris) }
        }
        StudioScreen(manager, onExit)
    }
}
