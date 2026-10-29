package app.ultimate.core

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val TerminalBackground = Color(0xFF0E1116)
private val TerminalText = Color(0xFFC9D1D9)
private val TerminalDim = Color(0xFF6E7781)
private val TerminalOk = Color(0xFF56D364)
private val TerminalWarn = Color(0xFFE3B341)
private val TerminalError = Color(0xFFFF7B72)
private val TerminalInfo = Color(0xFF79C0FF)

/**
 * A dark, monospace log panel. Collapsed it hides the body; expanded it scrolls the whole run and follows new output.
 * Same look in light and dark themes, like a real terminal.
 */
@Composable
fun Terminal(lines: List<LogLine>, live: Boolean, modifier: Modifier = Modifier, title: String = "Details", startExpanded: Boolean = false) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")

    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(TerminalBackground).animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Terminal, null, Modifier.size(18.dp), tint = TerminalDim)
            Spacer(Modifier.width(8.dp))
            Text(title, color = TerminalText, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            if (live) {
                Box(Modifier.size(8.dp).alpha(pulse).background(TerminalOk, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("LIVE", color = TerminalOk, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("${lines.size} lines", color = TerminalDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            IconButton({ copyText(context, "Log", lines.joinToString("\n") { format(it) }) }) {
                Icon(Icons.Rounded.ContentCopy, "Copy log", Modifier.size(18.dp), tint = TerminalDim)
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Collapse log" else "Expand log", tint = TerminalDim)
            }
        }
        if (!expanded) return@Column
        if (lines.isEmpty()) {
            Text("waiting for output…", Modifier.padding(start = 14.dp, bottom = 14.dp), color = TerminalDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            return@Column
        }
        val state = rememberLazyListState()
        LaunchedEffect(lines.size) { state.animateScrollToItem(lines.lastIndex) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(horizontal = 14.dp).padding(bottom = 12.dp), state = state) {
            itemsIndexed(lines) { _, line -> Line(line) }
        }
    }
}

@Composable
private fun Line(line: LogLine) {
    Row {
        Text(stamp(line.elapsedMs), color = TerminalDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            line.text,
            color = when (line.level) {
                LogLevel.Info -> TerminalInfo
                LogLevel.Detail -> TerminalText
                LogLevel.Ok -> TerminalOk
                LogLevel.Warn -> TerminalWarn
                LogLevel.Error -> TerminalError
            },
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
        )
    }
}

private fun stamp(ms: Long) = String.format(Locale.US, "%02d:%02d.%d", ms / 60_000, (ms / 1000) % 60, (ms / 100) % 10)

private fun format(line: LogLine) = "${stamp(line.elapsedMs)}  ${line.level.name.uppercase().padEnd(6)} ${line.text}"
