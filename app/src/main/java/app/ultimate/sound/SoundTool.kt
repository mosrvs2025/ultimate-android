package app.ultimate.sound

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.runtime.Composable
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.Thumb
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SoundTool(private val app: UltimateApp) : Tool {
    override val id = "sound-meter"
    override val title = "Sound meter"
    override val tagline = "See your sound. Save and compare your space."
    override val icon = Icons.Rounded.Mic
    override val acceptedMimePrefixes = emptyList<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val meter = SoundMeter(app)
    private val store = SoundStore(app)
    private val mutableSessions = MutableStateFlow<List<SoundSession>>(emptyList())
    val sessions = mutableSessions.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    private val mutableLibrary = MutableStateFlow<List<LibraryItem>>(emptyList())
    override val library = mutableLibrary.asStateFlow()
    private val mutableHeadline = MutableStateFlow("Live levels · private sessions")
    override val headline = mutableHeadline.asStateFlow()

    init {
        scope.launch { refresh() }
        scope.launch {
            var lastSaved: String? = null
            meter.state.collect { state ->
                val session = state.finished
                if (session != null && session.id != lastSaved) {
                    if (save(session)) lastSaved = session.id
                }
            }
        }
    }
    suspend fun save(session: SoundSession): Boolean = try {
        store.save(session)
        refresh()
        true
    } catch (e: Exception) {
        error.value = "Session could not be saved: ${e.message}. Keep this session open and retry."
        false
    }
    override suspend fun refresh() {
        try {
            val list = store.load()
            mutableSessions.value = list
            mutableLibrary.value = list.map { s -> LibraryItem(
                id = s.id, toolId = id, title = s.title,
                subtitle = "${s.durationMs / 1000}s · ${String.format(Locale.US, "%.1f", s.display(s.averageDb))} ${s.unit} average",
                createdAt = s.createdAt, bytes = 0, savedBytes = 0, uri = null, thumb = Thumb.Generic,
                details = listOf("Units" to s.unit, "Input" to s.route, "Ended" to s.endReason), mimeType = "text/csv",
            ) }
            error.value = null
            mutableHeadline.value = if (list.isEmpty()) "Live levels · private sessions" else "${list.size} saved sound sessions"
        } catch (e: Exception) {
            error.value = "Saved sessions could not be loaded: ${e.message}"
        }
    }
    override fun opensOwnScreen(item: LibraryItem) = true
    override suspend fun remove(id: String, deleteFile: Boolean): Boolean {
        val removed = store.delete(id)
        refresh()
        return removed
    }
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = SoundScreen(this, onExit, launch)
}
