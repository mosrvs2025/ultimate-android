package app.ultimate.traffic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.AppIcon
import app.ultimate.core.formatBytes
import app.ultimate.pluck.model.SdkKnowledge
import app.ultimate.traffic.model.TrackerDomains

/** Everything known about one host: what it is, who talked to it, and a switch to block it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSheet(host: String, c: TrafficController) {
    val st by c.state.collectAsStateWithLifecycle()
    val blockedNow by c.userBlocked.collectAsStateWithLifecycle()
    val adsBlocked by c.blockAds.collectAsStateWithLifecycle()
    val snap = st.snapshot.takeIf { st.running } ?: st.finished?.snapshot ?: st.snapshot
    val view = snap.hosts.firstOrNull { it.host == host }
    val flows = snap.flows.filter { it.name == host }
    val hit = TrackerDomains.classify(host)
    val info = hit?.let { SdkKnowledge.info(it.sdk) }
    val rule = TrackerDomains.suffixFor(host) ?: host
    val isBlocked = blockedNow.contains(rule) || (adsBlocked && hit?.kind == app.ultimate.traffic.model.DomainKind.Ads)

    ModalBottomSheet(onDismissRequest = { c.explain(null) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(host, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            val color = kindColor(hit?.kind ?: app.ultimate.traffic.model.DomainKind.Other, MaterialTheme.colorScheme)
            Text(
                hit?.let { "${it.sdk} · ${it.kind.label}" } ?: "Not a known ad or tracking service",
                Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
                color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            )
            if (info != null) {
                Section("What it is", info.what)
                Section("What it typically collects", info.collects)
                Section("Why apps contact it", info.why)
                Text("General description of the service. It does not say what these apps sent, which stays hidden by encryption.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            } else if (hit == null) {
                Text("Most hosts are the app's own servers or content networks. The list of known trackers is not complete, so an unknown name is not proof it is harmless.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (view != null) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Fact("Sent", formatBytes(view.up)); Fact("Received", formatBytes(view.down)); Fact("Connections", "${view.flows}")
                }
                if (view.cleartext) Text("Some of this traffic is not encrypted. The request was readable by anyone on the network.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Text("Contacted by", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                view.pkgs.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) { AppIcon(p, size = 28.dp); Spacer(Modifier.width(10.dp)); Text(snap.apps.firstOrNull { it.pkg == p }?.label ?: p) }
                }
            }
            if (flows.any { it.cleartext != null }) flows.mapNotNull { it.cleartext }.distinct().take(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Block $rule", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "Refuses its DNS lookups and connections for every inspected app, now and in future sessions. Apps that use a hard-coded address or encrypted DNS can still get through.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(isBlocked, { c.setBlocked(rule, it) }, enabled = !(adsBlocked && hit?.kind == app.ultimate.traffic.model.DomainKind.Ads))
            }
        }
    }
}

@Composable
private fun Section(title: String, body: String) = Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(body, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Fact(label: String, value: String) = Column {
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
