package app.ultimate.home

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ultimate.BuildConfig
import app.ultimate.UltimateApp

/**
 * After an update, what changed since the version you last opened; on a fresh install, what Ultimate is. Shown once
 * each, in a plain dialog that fits the Fold's cover screen and scrolls when the list is long.
 */
@Composable
fun WhatsNewOnUpdate(app: UltimateApp) {
    val prefs = remember { app.getSharedPreferences("whats_new", Context.MODE_PRIVATE) }
    val last = remember { prefs.getString("seen", null) }
    val edition = AboutInfo.editionName(BuildConfig.FLAVOR)
    // First install: introduce the app. After an update: what changed.
    var show by remember { mutableStateOf(last != BuildConfig.VERSION_NAME) }
    if (!show) return
    val changes = remember { WhatsNew.since(last, BuildConfig.VERSION_NAME) }
    fun done() { prefs.edit().putString("seen", BuildConfig.VERSION_NAME).apply(); show = false }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = ::done,
        title = { Text(if (changes != null) "What's new in ${BuildConfig.VERSION_NAME}" else "Welcome to $edition") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (changes != null) changes.forEach { e ->
                    Text(e.version, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    e.notes.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp)) }
                } else Text(Pitch.text(edition, app.tools.size), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(::done) { Text("Got it") } },
        dismissButton = {
            TextButton({ shareWhatItIs(context, edition, app.tools.size) }) { Text("Share what it is") }
        },
    )
}

fun shareWhatItIs(context: Context, edition: String, tools: Int) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Pitch.text(edition, tools)), "Share what $edition is"))
}
