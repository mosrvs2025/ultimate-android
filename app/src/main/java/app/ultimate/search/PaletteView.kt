package app.ultimate.search

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ultimate.UltimateApp
import app.ultimate.core.AppIcon

private fun Kind.icon(): ImageVector = when (this) {
    Kind.App -> Icons.Rounded.Apps; Kind.Tool -> Icons.Rounded.AutoAwesome; Kind.Action -> Icons.Rounded.TouchApp
    Kind.Contact -> Icons.Rounded.Person; Kind.Message -> Icons.Rounded.Notifications; Kind.Copied -> Icons.Rounded.ContentPaste
    Kind.File -> Icons.Rounded.Description; Kind.Setting -> Icons.Rounded.Settings; Kind.Record -> Icons.Rounded.History
    Kind.Sensed -> Icons.Rounded.Waves; Kind.Rule -> Icons.Rounded.Rule
}

/**
 * The command palette's results for what is typed on Home: the top hit large, then each kind under its own heading with
 * a badge, so a contact, a message and an app with the same name are told apart at a glance.
 */
@Composable
fun PaletteResults(app: UltimateApp, query: String, onDone: () -> Unit) {
    val sources = remember { PaletteSources(app) }
    var arranged by remember { mutableStateOf<Pair<Hit?, List<Pair<Kind, List<Hit>>>>>(null to emptyList()) }
    var contactsOk by remember { mutableStateOf(sources.contactsAllowed) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsOk = it }
    val q = query.trim()
    // Only say "nothing matches" once the search has actually finished, not while it's still looking.
    var searched by remember { mutableStateOf("") }
    LaunchedEffect(q, contactsOk) {
        if (q.isEmpty()) { arranged = null to emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(120) // let typing settle
        // "Play YouTube" means YouTube: search without the verb too, so the app itself comes out on top.
        val bare = PaletteMath.withoutVerb(q)
        arranged = PaletteMath.arrange(if (bare != q) (sources.search(bare) + sources.search(q)).distinctBy { it.key } else sources.search(q))
        searched = q
    }
    if (q.isEmpty()) return
    val (top, groups) = arranged
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        top?.let { HitRow(it, big = true) { onDone(); it.run() } }
        groups.forEach { (kind, hits) ->
            Text(kind.section, Modifier.padding(start = 6.dp, top = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            hits.forEach { h -> HitRow(h, big = false) { onDone(); h.run() } }
        }
        if (top == null && groups.isEmpty() && q.length >= 2 && searched == q) Text("Nothing on this phone matches \"$q\" yet.", Modifier.padding(start = 12.dp, top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!contactsOk && q.length >= 2) {
            Text(
                "Search your contacts too",
                Modifier.padding(start = 6.dp, top = 6.dp).clip(RoundedCornerShape(12.dp)).clickable { ask.launch(Manifest.permission.READ_CONTACTS) }.padding(horizontal = 8.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun HitRow(h: Hit, big: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (big) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = if (big) 14.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(if (big) 40.dp else 32.dp), contentAlignment = Alignment.Center) {
            if (h.iconPackage != null && (h.kind == Kind.App || h.kind == Kind.Message)) AppIcon(h.iconPackage, size = if (big) 40.dp else 30.dp)
            else Box(Modifier.size(if (big) 40.dp else 30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(h.kind.icon(), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.title, style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h.subtitle.isNotBlank()) Text(h.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(h.kind.badge, Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
