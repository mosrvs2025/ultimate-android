package app.ultimate.home

import android.net.Uri
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EntryOrigin { SURFACE, ORB_LEFT, ORB_RIGHT, OTHER }

sealed interface Route {
    data object Home : Route
    data object Library : Route
    data object Activity : Route
    data class InTool(val tool: Tool, val launch: ToolLaunch, val origin: EntryOrigin = EntryOrigin.OTHER) : Route
}

/** Files shared into the app that more than one tool could handle; the user picks. */
data class Incoming(val uris: List<Uri>, val mime: String, val candidates: List<Tool>)

/** Where the user is: Home, the Library, or inside a tool. Lives for the whole process so a share never loses its place. */
class Shell(private val tools: List<Tool>, private val insights: app.ultimate.insights.InsightsStore? = null) {
    private val _route = MutableStateFlow<Route>(Route.Home)
    val route: StateFlow<Route> = _route.asStateFlow()
    private val _incoming = MutableStateFlow<Incoming?>(null)
    val incoming: StateFlow<Incoming?> = _incoming.asStateFlow()
    private var openedAt = 0L
    var lastVisitMs: Long = Long.MAX_VALUE
        private set
    private fun finishVisit() {
        if (_route.value is Route.InTool) lastVisitMs = (android.os.SystemClock.elapsedRealtime() - openedAt).coerceAtLeast(0L)
    }
    private var lastTopLevel: Route = Route.Home

    fun showHome() = top(Route.Home)
    /** Which tool the Library is filtered to; null shows everything. Lives here so a tool's own shortcut can open it pre-filtered. */
    private val _libraryFilter = MutableStateFlow<String?>(null)
    val libraryFilter: StateFlow<String?> = _libraryFilter.asStateFlow()

    fun setLibraryFilter(toolId: String?) {
        _libraryFilter.value = toolId
    }

    fun showLibrary() {
        _libraryFilter.value = null
        top(Route.Library)
    }

    fun showLibraryOf(toolId: String) {
        _libraryFilter.value = toolId
        top(Route.Library)
    }

    /** The Library shortcut inside a tool: that tool's results first. */
    fun showLibraryForCurrentTool() {
        _libraryFilter.value = (_route.value as? Route.InTool)?.tool?.id
        top(Route.Library)
    }
    fun showActivity() = top(Route.Activity)

    private fun top(route: Route) {
        finishVisit()
        insights?.left()
        lastTopLevel = route
        _route.value = route
    }

    fun open(tool: Tool, launch: ToolLaunch = ToolLaunch()) {
        openFrom(tool, EntryOrigin.OTHER, launch)
    }

    fun openFrom(tool: Tool, origin: EntryOrigin, launch: ToolLaunch = ToolLaunch()) {
        finishVisit()
        insights?.entered(tool.id)
        openedAt = android.os.SystemClock.elapsedRealtime()
        _route.value = Route.InTool(tool, launch, origin)
    }

    fun exitTool() {
        finishVisit()
        insights?.left()
        _route.value = lastTopLevel
    }

    /** Called when other apps share files here. One capable tool opens directly; several ask first. */
    fun offerShared(uris: List<Uri>, mime: String?) {
        if (uris.isEmpty()) return
        val type = mime.orEmpty()
        val candidates = tools.filter { t -> t.acceptedMimePrefixes.any { type.startsWith(it) } }
        when (candidates.size) {
            0 -> Unit
            // A one-tool edition has nothing to choose between; the full app offers what it can do with the file.
            1 -> if (tools.size == 1) open(candidates.first(), ToolLaunch(uris)) else _incoming.value = Incoming(uris, type, candidates)
            else -> _incoming.value = Incoming(uris, type, candidates)
        }
    }

    fun chooseForIncoming(tool: Tool) {
        val pending = _incoming.value ?: return
        _incoming.value = null
        open(tool, ToolLaunch(pending.uris))
    }

    fun dismissIncoming() {
        _incoming.value = null
    }
}
