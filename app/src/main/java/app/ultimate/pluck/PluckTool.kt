package app.ultimate.pluck

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.ui.graphics.vector.ImageVector
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.Thumb
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.colonHex
import app.ultimate.core.formatBytes
import app.ultimate.pluck.engine.SavedEntry
import app.ultimate.pluck.ui.PluckController
import app.ultimate.pluck.ui.PluckScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Extracts installed apps as APK files and shows what is inside them. */
class PluckTool(private val app: UltimateApp) : Tool {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller by lazy { PluckController(app) }
    private val history get() = app.pluckHistory

    override val id = "pluck"
    override val title = "Pluck"
    override val tagline = "Extract any app's APK and see what's inside"
    override val icon: ImageVector = Icons.Rounded.Android
    override val acceptedMimePrefixes = emptyList<String>()

    override val headline: StateFlow<String> = history.entries.map { list ->
        if (list.isEmpty()) "Back up your apps" else "${list.size} app${if (list.size == 1) "" else "s"} extracted"
    }.stateIn(scope, SharingStarted.Eagerly, "Back up your apps")

    override val library: StateFlow<List<LibraryItem>> = history.entries.map { list -> list.map(::toItem) }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun toItem(e: SavedEntry) = LibraryItem(
        id = e.id, toolId = id, title = e.label,
        subtitle = "v${e.versionName} · ${formatBytes(e.bytes)}" + if (e.splitCount > 1) " · bundle of ${e.splitCount}" else "",
        createdAt = e.createdAt, bytes = e.bytes, savedBytes = 0, uri = e.uri,
        thumb = Thumb.AppIcon(e.packageName),
        details = listOf("Package" to e.packageName, "File" to e.displayName, "Size" to formatBytes(e.bytes), "SHA-256" to colonHex(e.sha256)),
        mimeType = "application/octet-stream",
    )

    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = history.remove(id, deleteFile)
    override suspend fun refresh() = history.refresh()

    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = PluckScreen(remember { controller }, onExit)
}
