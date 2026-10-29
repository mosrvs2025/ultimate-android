package app.ultimate.squeeze

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.ui.graphics.vector.ImageVector
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.Thumb
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.formatBytes
import app.ultimate.core.formatDuration
import app.ultimate.core.percentSaved
import app.ultimate.squeeze.model.LibraryEntry
import app.ultimate.squeeze.ui.SqueezeController
import app.ultimate.squeeze.ui.SqueezeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Shrinks videos with hardware encoders, with live before/after comparison. */
class SqueezeTool(private val app: UltimateApp) : Tool {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller by lazy { SqueezeController(app) }
    private val store get() = app.squeezeLibrary

    override val id = "squeeze"
    override val title = "Squeeze"
    override val tagline = "Shrink videos to a fraction of the size"
    override val icon: ImageVector = Icons.Rounded.Compress
    override val acceptedMimePrefixes = listOf("video/")

    override val headline: StateFlow<String> = store.entries.map { list ->
        val saved = list.sumOf { it.savedBytes }
        if (list.isEmpty()) "Ready when you are" else "${formatBytes(saved)} saved so far"
    }.stateIn(scope, SharingStarted.Eagerly, "Ready when you are")

    override val library: StateFlow<List<LibraryItem>> = store.entries.map { list -> list.map(::toItem) }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun toItem(e: LibraryEntry) = LibraryItem(
        id = e.id, toolId = id, title = e.name,
        subtitle = "${formatBytes(e.before.sizeBytes)} → ${formatBytes(e.after.sizeBytes)} (−${percentSaved(e.before.sizeBytes, e.after.sizeBytes)}%) · ${e.label}",
        createdAt = e.createdAt, bytes = e.after.sizeBytes, savedBytes = e.savedBytes, uri = e.outputUri,
        thumb = if (e.hasFrames) Thumb.Frame(store.afterFrame(e.id).path) else Thumb.Generic,
        details = listOf(
            "Original" to formatBytes(e.before.sizeBytes), "Result" to formatBytes(e.after.sizeBytes), "Settings" to e.label,
            "Duration" to formatDuration(e.after.durationMs),
        ),
        mimeType = "video/mp4",
    )

    override fun opensOwnScreen(item: LibraryItem) = true
    override suspend fun remove(id: String, deleteFile: Boolean) = store.remove(id, deleteFile)
    override suspend fun refresh() = store.refresh()

    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = SqueezeScreen(remember { controller }, onExit, launch, this)
}
