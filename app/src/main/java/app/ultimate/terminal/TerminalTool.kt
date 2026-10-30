package app.ultimate.terminal

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Button
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.ShellActions
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.pressScale
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TerminalTool(private val app: UltimateApp, val manager: TerminalManager) : Tool {
    override val id = "terminal"
    override val title = "Terminal"
    override val tagline = "Run commands on your phone"
    override val icon = Icons.Rounded.Terminal
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Shell and phone commands").asStateFlow()
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = TerminalScreen(app, manager, onExit)
}

private val EXTRA_KEYS = listOf("ESC", "TAB", "CTRL", "ALT", "PASTE", "ENTER")
private val NAV_KEYS = listOf("←", "↓", "↑", "→", "HOME", "END", "/", "-", "|", "~")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalScreen(app: UltimateApp, m: TerminalManager, onExit: () -> Unit) {
    val context = LocalContext.current
    val sessions by m.sessions.collectAsStateWithLifecycle()
    val current by m.current.collectAsStateWithLifecycle()
    m.changes.collectAsStateWithLifecycle().value
    val ctrl by m.ctrl.collectAsStateWithLifecycle()
    val alt by m.alt.collectAsStateWithLifecycle()
    val error by m.error.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf(false) }
    var fontPx by remember { mutableStateOf(context.getSharedPreferences("terminal", Context.MODE_PRIVATE).getInt("font", (13 * context.resources.displayMetrics.scaledDensity).toInt()).coerceIn(18, 80)) }
    LaunchedEffect(Unit) { if (m.sessions.value.isEmpty()) m.open() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Terminal") }, navigationIcon = { ToolBackButton(onExit) }, actions = {
            IconButton({ m.view?.let { showKeyboard(it) } }, enabled = current?.isRunning == true) { Icon(Icons.Rounded.Keyboard, "Show keyboard") }
            IconButton({ asking = true }) { Icon(Icons.Rounded.AutoAwesome, "Command suggestions") }
            ShellActions()
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF1B1B1F)), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    sessions.forEachIndexed { i, s ->
                        val on = s == current
                        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFF3B3B44) else Color.Transparent)
                            .clickable(role = Role.Tab) { m.select(s) }.semantics { selected = on }, verticalAlignment = Alignment.CenterVertically) {
                            Text(s.title?.takeIf { it.isNotBlank() }?.take(18) ?: "Shell ${i + 1}", Modifier.padding(start = 12.dp), color = Color.White)
                            IconButton({ m.close(s) }) { Icon(Icons.Rounded.Close, "Close shell ${i + 1}", tint = Color.White) }
                        }
                    }
                }
                IconButton({ m.open() }) { Icon(Icons.Rounded.Add, "New shell", tint = Color.White) }
            }
            error?.let { message ->
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                    Row { TextButton({ m.open() }) { Text("Retry") }; TextButton({ m.dismissError() }) { Text("Dismiss") } }
                }
            }
            if (current == null) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.Center) {
                    Text("Run Android shell commands here. Type help for phone commands.", color = Color.White)
                    Button({ m.open() }) { Text("Start shell") }
                }
            } else {
            if (current?.isRunning == false) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Shell exited (${current?.exitStatus})", Modifier.weight(1f))
                    TextButton({ m.open() }) { Text("New shell") }
                }
            }
            AndroidView(
                factory = { ctx ->
                    createTerminalView(ctx, fontPx).apply {
                        setTerminalViewClient(viewClient(m, this, fontPx) { px -> fontPx = px; ctx.getSharedPreferences("terminal", Context.MODE_PRIVATE).edit().putInt("font", px).apply() })
                        isFocusable = true; isFocusableInTouchMode = true
                        setBackgroundColor(android.graphics.Color.BLACK)
                        m.view = this
                    }
                },
                update = { v ->
                    v.setTextSize(fontPx)
                    current?.let { s -> if (v.currentSession != s) { v.attachSession(s); v.requestFocus() } }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            DisposableEffect(Unit) { onDispose { m.view = null } }
            }
            DisposableEffect(Unit) { onDispose { m.ctrl.value = false; m.alt.value = false } }
            listOf(EXTRA_KEYS, NAV_KEYS).forEach { keys ->
            Row(Modifier.fillMaxWidth().background(Color(0xFF1B1B1F)).horizontalScroll(rememberScrollState()).padding(vertical = 4.dp, horizontal = 4.dp)) {
                keys.forEach { k ->
                    val lit = (k == "CTRL" && ctrl) || (k == "ALT" && alt)
                    Text(k, Modifier.padding(horizontal = 2.dp).pressScale(0.9f).clip(RoundedCornerShape(8.dp)).background(if (lit) MaterialTheme.colorScheme.primary else Color(0xFF2B2B31))
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { selected = lit }
                        .clickable(enabled = current?.isRunning == true, role = Role.Button) { extraKey(m, k) }.padding(horizontal = 8.dp, vertical = 14.dp),
                        color = if (lit) MaterialTheme.colorScheme.onPrimary else Color.White, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
                }
            }
            }
        }
    }

    if (asking) AskSheet(onPick = { cmd, run -> asking = false; m.type(cmd, run); m.view?.let { showKeyboard(it) } }) { asking = false }
}

/** Termux requires a text size before any typeface changes; the constructor leaves its renderer null. */
internal fun createTerminalView(context: Context, fontPx: Int): TerminalView = TerminalView(context, null).apply {
    setTextSize(fontPx.coerceIn(18, 80))
    setTypeface(Typeface.MONOSPACE)
}

private fun showKeyboard(v: TerminalView) {
    v.requestFocus()
    v.context.getSystemService(InputMethodManager::class.java).showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
}

private fun extraKey(m: TerminalManager, k: String) {
    val v = m.view ?: return
    val s = v.currentSession ?: return
    when (k) {
        "PASTE" -> m.client.onPasteTextFromClipboard(s)
        "ENTER" -> s.write("\r")
        "CTRL" -> m.ctrl.value = !m.ctrl.value
        "ALT" -> m.alt.value = !m.alt.value
        "ESC" -> v.handleKeyCode(KeyEvent.KEYCODE_ESCAPE, 0)
        "TAB" -> v.handleKeyCode(KeyEvent.KEYCODE_TAB, 0)
        "←" -> v.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        "→" -> v.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        "↑" -> v.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0)
        "↓" -> v.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        "HOME" -> v.handleKeyCode(KeyEvent.KEYCODE_MOVE_HOME, 0)
        "END" -> v.handleKeyCode(KeyEvent.KEYCODE_MOVE_END, 0)
        else -> s.write(k)
    }
}

private fun viewClient(m: TerminalManager, v: TerminalView, startPx: Int, onFont: (Int) -> Unit) = object : TerminalViewClient {
    private var size = startPx.toFloat()
    /** Pinch to resize the text, in 10% steps. */
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            size = (size * if (scale > 1f) 1.1f else 0.9f).coerceIn(18f, 80f)
            onFont(size.toInt())
            return 1f
        }
        return scale
    }
    override fun onSingleTapUp(e: MotionEvent?) = showKeyboard(v)
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey(): Boolean = m.ctrl.value
    override fun readAltKey(): Boolean = m.alt.value
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        // A tapped CTRL or ALT applies to one key, like on a hardware keyboard's sticky keys.
        if (m.ctrl.value || m.alt.value) v.post { m.ctrl.value = false; m.alt.value = false }
        return false
    }
    override fun onEmulatorSet() { v.setTerminalCursorBlinkerRate(500); v.setTerminalCursorBlinkerState(true, true) }
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}

/** Describe what you want; pick a suggested command to type it (and edit), or run it straight away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskSheet(onPick: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val found = remember(q) { Suggest.english(q) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("What do you want to do?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("e.g. find my biggest videos") }, singleLine = true)
            if (q.isBlank()) Text("Try: free space · battery · find invoice · biggest files · clipboard · flashlight on", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (found.isEmpty()) Text("No idea yet. Type help in the terminal to see phone commands.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            found.forEach { s ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp)) {
                    Text(s.explain, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    Row { Spacer(Modifier.weight(1f)); TextButton({ onPick(s.command, false) }) { Text("Type it") }; TextButton({ onPick(s.command, true) }) { Text("Run") } }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
