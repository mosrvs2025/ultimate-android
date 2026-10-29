package app.ultimate.home

import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ultimate.core.LibraryItem
import app.ultimate.core.LibraryRow
import app.ultimate.core.Tool
import app.ultimate.core.formatBytes

/** Where the space went: phone storage, what each tool made and saved, and the biggest wins. Opened from Home's saved badge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavingsSheet(tools: List<Tool>, all: List<LibraryItem>, onOpen: (Tool, LibraryItem) -> Unit, onDismiss: () -> Unit) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val stat = remember { StatFs(Environment.getDataDirectory().path) }
    val total = stat.totalBytes
    val free = stat.availableBytes
    val saved = all.sumOf { it.savedBytes }
    val perTool = tools.map { t -> Triple(t, all.filter { it.toolId == t.id }, all.filter { it.toolId == t.id }.sumOf { it.savedBytes }) }.filter { it.second.isNotEmpty() }
    val biggestMade = perTool.maxOfOrNull { it.second.sumOf { i -> i.bytes } }?.coerceAtLeast(1) ?: 1

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Your space", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val used = MaterialTheme.colorScheme.primary
                val savedColor = MaterialTheme.colorScheme.tertiary
                val track = MaterialTheme.colorScheme.surfaceVariant
                Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                        val w = 16.dp.toPx()
                        val inset = w / 2
                        val arc = Size(size.width - w, size.height - w)
                        drawArc(track, -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
                        val usedSweep = 360f * (total - free) / total.coerceAtLeast(1) * grow.value
                        drawArc(used, -90f, usedSweep, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
                        // Saved space is drawn as the slice it would have taken, just past the used part.
                        val savedSweep = (360f * saved / total.coerceAtLeast(1)).coerceAtLeast(if (saved > 0) 4f else 0f) * grow.value
                        drawArc(savedColor, -90f + usedSweep, savedSweep, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(formatBytes(free), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("free", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Legend(MaterialTheme.colorScheme.primary, "Used", formatBytes(total - free))
                    Legend(MaterialTheme.colorScheme.tertiary, "Saved by Ultimate", formatBytes((saved * grow.value).toLong()))
                    Legend(MaterialTheme.colorScheme.surfaceVariant, "Phone total", formatBytes(total))
                }
            }
            if (perTool.isNotEmpty()) {
                Text("By tool", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                perTool.sortedByDescending { it.second.sumOf { i -> i.bytes } }.forEach { (tool, items, toolSaved) ->
                    val made = items.sumOf { it.bytes }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(tool.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(tool.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "${items.size} made · ${formatBytes(made)}" + if (toolSaved > 0) " · ${formatBytes(toolSaved)} saved" else "",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth((made.toFloat() / biggestMade * grow.value).coerceIn(0.02f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
            val wins = all.filter { it.savedBytes > 0 }.sortedByDescending { it.savedBytes }.take(5)
            if (wins.isNotEmpty()) {
                Text("Biggest savings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                wins.forEach { item -> tools.firstOrNull { it.id == item.toolId }?.let { t -> LibraryRow(item, t, onClick = { onOpen(t, item) }) } }
            }
            if (all.isEmpty()) Text("Nothing made yet. Squeeze a video and the space it frees shows up here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
