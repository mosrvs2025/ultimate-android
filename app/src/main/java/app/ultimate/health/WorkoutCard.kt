package app.ultimate.health

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * Workouts in the Health screen: this week at a glance, today's sets, a way to log a set by hand, and (in builds that carry body tracking) a
 * button that counts your reps from the camera. Kept on this phone only.
 */
@Composable
internal fun WorkoutCard(store: WorkoutStore, sets: List<WorkoutSet>, onChanged: (List<WorkoutSet>) -> Unit, onCamera: (() -> Unit)?) {
    val today = remember { LocalDate.now().toEpochDay() }
    var adding by remember { mutableStateOf(false) }
    if (adding) LogSetDialog({ adding = false }) { s -> onChanged(store.add(s)); adding = false }
    val week = WorkoutLog.totals(sets, today - 6, today); val now = WorkoutLog.totals(sets, today, today)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Workouts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        WorkoutLog.summary(sets, today)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        if (now.isEmpty()) Text(if (sets.isEmpty()) "Count squats, push-ups, sit-ups and more from the camera, or log a set by hand." else "Nothing yet today.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            now.forEach { t -> Column(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("${t.exercise.emoji} ${t.exercise.label}", style = MaterialTheme.typography.labelMedium)
                Text(if (t.exercise.timed) WorkoutLog.mmss(t.seconds) else "${t.reps}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${t.sets} set${if (t.sets == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
        sets.sortedByDescending { it.at }.take(5).forEach { s ->
            val ex = s.kind ?: return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                val best = WorkoutLog.best(sets, ex, s.at); val v = if (ex.timed) s.seconds else s.reps
                Column(Modifier.weight(1f)) {
                    Text("${ex.emoji} ${ex.label}: " + if (ex.timed) WorkoutLog.mmss(s.seconds) else "${s.reps} reps", style = MaterialTheme.typography.bodyMedium)
                    Text(buildString { append(if (s.day == today) "Today" else if (s.day == today - 1) "Yesterday" else "${today - s.day} days ago"); append(if (s.camera) " · counted by the camera" else " · logged by hand"); s.quality?.let { append(" · depth ${(it * 100).toInt()}%") }; if (v > best && best > 0) append(" · 🏅 new best") },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ onChanged(store.remove(s.id)) }) { Text("✕") }
            }
        }
        if (onCamera != null) Button(onCamera, Modifier.fillMaxWidth()) { Text("Count a set with the camera") }
        FilledTonalButton({ adding = true }, Modifier.fillMaxWidth()) { Text("Log a set by hand") }
        Text("Kept on this phone only. The camera picture is used to find your joints and then dropped; nothing is saved or sent.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LogSetDialog(onDismiss: () -> Unit, onAdd: (WorkoutSet) -> Unit) {
    var ex by remember { mutableStateOf(Exercise.SQUAT) }; var n by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Log a set") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Exercise.entries.forEach { e -> FilterChip(ex == e, { ex = e }, { Text("${e.emoji} ${e.label}", style = MaterialTheme.typography.labelMedium) }) } }
        OutlinedTextField(n, { n = it.filter { c -> c.isDigit() }.take(4) }, singleLine = true, label = { Text(if (ex.timed) "Seconds held" else "Reps") })
    } }, confirmButton = { TextButton({ val v = n.toIntOrNull() ?: 0; val now = System.currentTimeMillis(); onAdd(WorkoutSet(java.util.UUID.randomUUID().toString(), ex.name, now, LocalDate.now().toEpochDay(), if (ex.timed) 0 else v, if (ex.timed) v else 0, null, false)) }, enabled = (n.toIntOrNull() ?: 0) > 0) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
