package app.ultimate.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.ui.graphics.vector.ImageVector
import app.ultimate.UltimateApp
import app.ultimate.audio.engine.AudioEntry
import app.ultimate.audio.ui.AudioController
import app.ultimate.audio.ui.AudioScreen
import app.ultimate.core.LibraryItem
import app.ultimate.core.Thumb
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.formatBytes
import app.ultimate.core.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

/** Turns the sound of any video into MP3, M4A or WAV. */
class AudioTool(private val app: UltimateApp) : Tool {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller by lazy { AudioController(app) }
    private val store get() = app.audioStore

    override val id = "audio"
    override val title = "Video to audio"
    override val tagline = "Video to MP3, plus audio cutting, converting, speed and joining"
    override val icon: ImageVector = Icons.Rounded.GraphicEq
    override val acceptedMimePrefixes = listOf("video/", "audio/")

    override val headline: StateFlow<String> = store.entries.map { list ->
        if (list.isEmpty()) "MP3, M4A and WAV" else "${list.size} file${if (list.size == 1) "" else "s"} · ${formatDuration(list.sumOf { it.durationMs })} of audio"
    }.stateIn(scope, SharingStarted.Eagerly, "MP3, M4A and WAV")

    override val library: StateFlow<List<LibraryItem>> = store.entries.map { list -> list.map(::toItem) }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun toItem(e: AudioEntry) = LibraryItem(
        id = e.id, toolId = id, title = e.displayName,
        subtitle = "${e.format.label} · ${formatDuration(e.durationMs)} · ${formatBytes(e.bytes)}",
        createdAt = e.createdAt, bytes = e.bytes, savedBytes = 0, uri = e.uri, thumb = Thumb.Waveform(e.peaks),
        details = listOf(
            "From" to e.sourceName, "Format" to e.format.label, "Bitrate" to "${e.bitrate / 1000} kbps",
            "Sample rate" to String.format(Locale.US, "%.1f kHz", e.sampleRate / 1000f),
            "Channels" to if (e.channels == 1) "Mono" else "Stereo", "Duration" to formatDuration(e.durationMs), "Size" to formatBytes(e.bytes),
        ),
        mimeType = e.format.mime,
    )

    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = store.remove(id, deleteFile)
    override suspend fun refresh() = store.refresh()

    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = AudioScreen(remember { controller }, onExit, launch, this)
}
