package app.ultimate.lan

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Router
import androidx.compose.runtime.Composable
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class LanTool(private val manager: LanManager) : Tool {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val id = "network"
    override val title = "Network scanner"
    override val tagline = "Every device on your Wi-Fi and what it exposes"
    override val icon = Icons.Rounded.Router
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = manager.state.map { s ->
        when {
            s.scanning -> "Scanning · ${s.devices.size} found"
            s.finishedAt > 0 -> "${s.devices.size} devices · ${s.findings.count { it.risk == Risk.High }} risky"
            else -> "See who's on your Wi-Fi"
        }
    }.stateIn(scope, SharingStarted.Eagerly, "See who's on your Wi-Fi")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = LanScreen(manager, onExit)
}
