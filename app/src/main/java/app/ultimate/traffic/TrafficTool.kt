package app.ultimate.traffic

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.Thumb
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.formatBytes
import app.ultimate.core.formatDuration
import app.ultimate.traffic.engine.TrafficReport
import app.ultimate.traffic.ui.TrafficController
import app.ultimate.traffic.ui.TrafficScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Shows which servers chosen apps talk to, through a private tunnel on the phone. Needs the internet permission, so it only ships in editions that say so. */
class TrafficTool(private val app: UltimateApp) : Tool {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller by lazy { TrafficController(app) }
    private val manager get() = app.trafficManager

    override val id = "traffic"
    override val title = "Traffic inspector"
    override val tagline = "See who your apps talk to, live"
    override val icon: ImageVector = Icons.Rounded.Radar
    override val acceptedMimePrefixes = emptyList<String>()

    override val headline: StateFlow<String> = combine(manager.state, manager.savedReports) { st, saved ->
        when {
            st.running -> "Inspecting ${st.captured.size} app${if (st.captured.size == 1) "" else "s"} · ${st.snapshot.trackerHosts.size} trackers so far"
            saved.isNotEmpty() -> "${saved.size} report${if (saved.size == 1) "" else "s"} saved"
            else -> "Watch ad networks and trackers in action"
        }
    }.stateIn(scope, SharingStarted.Eagerly, "Watch ad networks and trackers in action")

    override val library: StateFlow<List<LibraryItem>> = manager.savedReports.map { list -> list.map(::toItem) }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun toItem(r: TrafficReport): LibraryItem {
        val details = buildList {
            add("Apps" to r.apps.joinToString(", "))
            add("Duration" to formatDuration(r.durationMs))
            add("Sent" to formatBytes(r.up))
            add("Received" to formatBytes(r.down))
            add("Hosts contacted" to "${r.hosts}")
            add("Known trackers" to "${r.trackerHosts}")
            r.top.forEach { entry ->
                val parts = entry.split('|')
                val sdk = parts.getOrElse(1) { "" }.ifEmpty { "other" }
                add(parts[0] to sdk + " · " + formatBytes(parts.getOrElse(2) { "0" }.toLongOrNull() ?: 0L))
            }
        }
        return LibraryItem(
            id = r.id, toolId = id, title = "Traffic: " + r.apps.joinToString(", ").take(48),
            subtitle = "${r.trackerHosts} tracker host${if (r.trackerHosts == 1) "" else "s"} · ${r.hosts} hosts · ${formatBytes(r.up + r.down)}",
            createdAt = r.createdAt, bytes = r.up + r.down, savedBytes = 0, uri = r.csvUri, thumb = Thumb.Generic,
            details = details, mimeType = "text/csv",
        )
    }

    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = manager.removeReport(id, deleteFile)
    override suspend fun refresh() = Unit

    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = TrafficScreen(remember { controller }, onExit)
}
