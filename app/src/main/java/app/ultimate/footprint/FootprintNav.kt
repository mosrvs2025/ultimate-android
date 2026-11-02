package app.ultimate.footprint

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ultimate.core.AppIcon
import app.ultimate.core.formatBytes
import app.ultimate.core.pressScale

/** Chips along the top that jump to a part of the page. A pill slides to the one you are on and stretches on the way, like liquid. */
@Composable
internal fun SectionNav(labels: List<String>, selected: Int, onPick: (Int) -> Unit) = app.ultimate.core.GooeyTabsBar(labels, selected, onPick)

/** What one app can do on this phone, how much you use it, and a way to take access away. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AppSheet(pkg: String, d: Loaded, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ap = d.apps.firstOrNull { it.pkg == pkg }
    val label = ap?.label ?: FootprintReaders.label(context, pkg)
    val net = d.net?.firstOrNull { it.pkg == pkg }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(pkg, size = 52.dp); Spacer(Modifier.width(14.dp))
                Column { Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); if (ap?.system == true) Text("Built into your phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            d.usage?.let { u ->
                val opens = u.opens[pkg]; val ms = u.foregroundMs[pkg]
                if (opens != null || ms != null) Text("This week: opened ${opens ?: 0} times, ${hm(ms ?: 0)} on screen.", style = MaterialTheme.typography.bodyMedium)
            }
            net?.let { Text("Data: ${formatBytes(it.total)} (sent ${formatBytes(it.sent)}).", style = MaterialTheme.typography.bodyMedium) }
            if (ap != null && ap.access.isNotEmpty()) {
                Text("It can see", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ap.access.sortedByDescending { it.sensitive }.forEach { a ->
                        Text(a.label, Modifier.clip(RoundedCornerShape(50)).background(if (a.sensitive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = if (a.sensitive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
                    }
                }
            } else Text("Nothing sensitive.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ app.ultimate.core.openAppSettings(context, pkg) }, Modifier.weight(1f)) { Text("Change what it can see") }
                OutlinedButton({ app.ultimate.core.openApp(context, pkg) }) { Text("Open") }
            }
        }
    }
}

/** The apps that add most to the exposure score, as bars that grow in. Tap one for its detail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GaugeSheet(d: Loaded, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val top = ExposureMath.contributors(d.apps)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What adds to this number", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Each sensitive thing an app you installed can see adds a little: your location all the time and your texts count most. Built-in apps are left out. Take access away from the top ones and the number drops.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (top.isEmpty()) Text("No app you installed holds a sensitive permission.")
            top.forEach { (a, pts) ->
                Box(Modifier.pressScale(0.98f).clip(RoundedCornerShape(14.dp)).clickable { onPick(a.pkg) }) {
                    AppBar(a.pkg, a.label, (pts / top.first().second).toFloat(), ExposureMath.risks(a).take(3).joinToString(", ") { it.label.lowercase() })
                }
            }
        }
    }
}
