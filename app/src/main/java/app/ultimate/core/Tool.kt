package app.ultimate.core

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.StateFlow

/** What a library row shows on its left. */
sealed interface Thumb {
    data class Frame(val path: String) : Thumb
    data class AppIcon(val packageName: String) : Thumb
    data class Waveform(val peaks: List<Float>) : Thumb
    /** A picture saved through MediaStore, shown with the system thumbnail. */
    data class Image(val uri: String) : Thumb
    data object Generic : Thumb
}

/** One finished result from any tool, in the shape the shared Library needs. */
data class LibraryItem(
    val id: String,
    val toolId: String,
    val title: String,
    val subtitle: String,
    val createdAt: Long,
    val bytes: Long,
    /** Space this result freed, if the tool is about saving space; otherwise 0. */
    val savedBytes: Long,
    val uri: String?,
    val thumb: Thumb,
    /** Label/value pairs for the generic detail sheet. */
    val details: List<Pair<String, String>>,
    val mimeType: String,
)

/** Hands a tool what it should start with: videos shared into the app, or a library item to open. */
class ToolLaunch(val uris: List<Uri> = emptyList(), val openItemId: String? = null) {
    var consumed: Boolean = false
}

/**
 * A self-contained feature of the app. Add a new tool by implementing this and listing it in [UltimateApp.tools];
 * Home, the Library, share-routing and background work pick it up from there.
 */
interface Tool {
    val id: String
    val title: String
    val tagline: String
    val icon: ImageVector

    /** Short live line for the Home card, e.g. "3.2 GB saved". */
    val headline: StateFlow<String>

    /** Results from this tool, newest first. */
    val library: StateFlow<List<LibraryItem>>

    /** MIME prefixes this tool accepts when something is shared to the app, e.g. "video/". */
    val acceptedMimePrefixes: List<String>

    /** True when tapping [item] in the Library should open this tool's own screen instead of the generic sheet. */
    fun opensOwnScreen(item: LibraryItem): Boolean

    /** Removes a result; with [deleteFile] also the file. Returns false if the file could not be deleted. */
    suspend fun remove(id: String, deleteFile: Boolean): Boolean

    /** Called when the Library is shown so stale entries (files deleted elsewhere) can be dropped. */
    suspend fun refresh()

    @Composable
    fun Screen(onExit: () -> Unit, launch: ToolLaunch)
}
