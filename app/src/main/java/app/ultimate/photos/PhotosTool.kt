package app.ultimate.photos

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.runtime.Composable
import app.ultimate.core.LibraryItem
import app.ultimate.core.Thumb
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Shrink, crop and clean photos: smaller files with location and camera details removed. */
class PhotosTool(val manager: PhotoManager) : Tool {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val id = "photos"
    override val title = "Photos"
    override val tagline = "Resize, crop and remove location from photos"
    override val icon = Icons.Rounded.PhotoSizeSelectLarge
    override val acceptedMimePrefixes = listOf("image/")
    override val library: StateFlow<List<LibraryItem>> = manager.results.map { list ->
        list.map { r ->
            val saved = (r.originalBytes - r.bytes).coerceAtLeast(0)
            LibraryItem(
                id = r.id, toolId = id, title = r.name,
                subtitle = "${r.width}×${r.height} · ${formatBytes(r.bytes)}" + if (saved > 0) " · ${formatBytes(saved)} smaller" else "",
                createdAt = r.createdAt, bytes = r.bytes, savedBytes = saved, uri = r.uri, thumb = Thumb.Image(r.uri),
                details = listOf("Size" to "${r.width} × ${r.height}", "Original" to formatBytes(r.originalBytes), "Now" to formatBytes(r.bytes), "Location" to "Removed", "Saved in" to "Pictures/Ultimate"),
                mimeType = if (r.name.endsWith(".webp")) "image/webp" else "image/jpeg",
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val headline: StateFlow<String> = manager.results.map { list ->
        val saved = list.sumOf { (it.originalBytes - it.bytes).coerceAtLeast(0) }
        if (list.isEmpty()) "Smaller photos, no location" else "${list.size} photos · ${formatBytes(saved)} saved"
    }.stateIn(scope, SharingStarted.Eagerly, "Smaller photos, no location")
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = manager.remove(id, deleteFile)
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = PhotosScreen(this, onExit, launch)
}
