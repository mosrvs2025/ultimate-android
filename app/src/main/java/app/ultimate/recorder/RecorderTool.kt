package app.ultimate.recorder

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.runtime.Composable
import app.ultimate.core.LibraryItem
import app.ultimate.core.Thumb
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A voice and music recorder that keeps going with the screen off. */
class RecorderTool(val manager: RecorderManager) : Tool {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val id = "recorder"
    override val title = "Recorder"
    override val tagline = "Record voice or music, even with the screen off"
    override val icon = Icons.Rounded.KeyboardVoice
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = combine(manager.state, manager.recordings) { s, list ->
        when (s) {
            is RecorderManager.State.Recording -> if (s.paused) "Paused · ${formatDuration(s.elapsedMs)}" else "● Recording · ${formatDuration(s.elapsedMs)}"
            RecorderManager.State.Saving -> "Saving…"
            RecorderManager.State.Idle -> if (list.isEmpty()) "Tap and talk" else "${list.size} recording${if (list.size == 1) "" else "s"}"
        }
    }.stateIn(scope, SharingStarted.Eagerly, "Tap and talk")
    override val library: StateFlow<List<LibraryItem>> = manager.recordings.map { list ->
        list.map { r ->
            LibraryItem(
                id = r.id, toolId = id, title = r.name, subtitle = formatDuration(r.durationMs), createdAt = r.createdAt, bytes = r.bytes, savedBytes = 0,
                uri = r.uri, thumb = Thumb.Waveform(r.peaks), details = listOf("Length" to formatDuration(r.durationMs), "Saved in" to "Music/Ultimate/Recordings"),
                mimeType = "audio/mp4",
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = manager.remove(id, deleteFile)
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = RecorderScreen(this, onExit)
}
