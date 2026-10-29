package app.ultimate.clean

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.ultimate.core.UltimateTheme
import app.ultimate.insights.track
import app.ultimate.insights.trackProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Appears in every share menu as "Share clean". It makes copies without location, camera and time details, then opens the
 * share menu again with the copies, so the user picks where they actually wanted to send them.
 */
class ShareCleanActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = incoming(intent)
        if (uris.isEmpty()) { finish(); return }
        val cleaner = MetadataCleaner(this)
        setContent {
            UltimateTheme {
                var result by remember { mutableStateOf<List<Cleaned>?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    runCatching { withContext(Dispatchers.IO) { cleaner.reset(); uris.map { cleaner.clean(it, intent.type) } } }
                        .onSuccess { result = it; share(it); track("shareclean.${if (it.any { c -> c.mime.startsWith("video/") }) "video" else "photo"}") }
                        .onFailure { error = it.message ?: "This file couldn't be cleaned"; trackProblem("Share clean", error) }
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable { finish() }, contentAlignment = Alignment.BottomCenter) {
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .clickable(enabled = false) {}.navigationBarsPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AnimatedContent(result to error, label = "clean") { (done, err) ->
                            when {
                                err != null -> Text(err, color = MaterialTheme.colorScheme.error)
                                done == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(14.dp))
                                    Text("Removing hidden details from ${uris.size} file${if (uris.size == 1) "" else "s"}…", style = MaterialTheme.typography.titleMedium)
                                }
                                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF2E9E5B)); Spacer(Modifier.width(10.dp))
                                        Text("Clean copy ready", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                    Text(CleanSummary.line(done.map { it.removed }), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button({ share(done) }) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") }
                                        TextButton({ finish() }) { Text("Done") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun share(files: List<Cleaned>) {
        val uris = files.map { FileProvider.getUriForFile(this, "$packageName.files", it.file) }
        val mime = files.map { it.mime }.distinct().singleOrNull() ?: "*/*"
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
        val chooser = Intent.createChooser(send, "Share clean copy").putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, ShareCleanActivity::class.java)))
        startActivity(chooser)
    }

    @Suppress("DEPRECATION")
    private fun incoming(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }
}

object CleanSummary {
    /** One sentence about what was taken out of a batch, e.g. "Removed location and camera details from 2 files." */
    fun line(removed: List<List<String>>): String {
        val kinds = removed.flatten().distinct()
        val n = removed.size
        val what = if (n == 1) "the file" else "$n files"
        return if (kinds.isEmpty()) "Nothing hidden was found in $what. It's ready to send as is."
        else "Removed ${kinds.joinToString(limit = 3).replace(Regex(", (?=[^,]*$)"), " and ")} from $what."
    }
}
