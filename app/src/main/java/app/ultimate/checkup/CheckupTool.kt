package app.ultimate.checkup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.runtime.Composable
import app.ultimate.checkup.engine.CheckupManager
import app.ultimate.checkup.model.Checkup
import app.ultimate.checkup.ui.CheckupScreen
import app.ultimate.core.LibraryItem
import app.ultimate.core.Thumb
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.relativeTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Scans the phone's security, privacy and apps, on the phone, and explains what it finds. */
class CheckupTool(private val manager: CheckupManager) : Tool {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val id = "checkup"
    override val title = "Phone checkup"
    override val tagline = "Security, privacy and what your apps can reach"
    override val icon = Icons.Rounded.HealthAndSafety
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = manager.last.map { last ->
        last?.let { (score, at) -> "$score/100 · ${Checkup.verdict(score).substringBefore(',')} · ${relativeTime(at)}" } ?: "See what's really on your phone"
    }.stateIn(scope, SharingStarted.Eagerly, "See what's really on your phone")
    override val library: StateFlow<List<LibraryItem>> = manager.archive.reports.map { list ->
        list.map { r ->
            LibraryItem(
                id = "checkup-${r.at}", toolId = id, title = "Checkup · ${r.score}/100",
                subtitle = when {
                    r.critical > 0 -> "${r.critical} need attention · ${r.warnings} to review"
                    r.warnings > 0 -> "Nothing urgent · ${r.warnings} to review"
                    else -> "All clear"
                },
                createdAt = r.at, bytes = r.bytes, savedBytes = 0, uri = r.uri, thumb = Thumb.Generic,
                details = listOf("Score" to "${r.score}/100", "Needs attention" to "${r.critical}", "To review" to "${r.warnings}", "Apps checked" to "${r.apps}", "Saved in" to "Documents/Ultimate"),
                mimeType = "text/markdown",
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = manager.archive.remove(id.removePrefix("checkup-").toLong(), deleteFile)
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = CheckupScreen(manager, onExit)
}
