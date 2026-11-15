package app.ultimate.mesh

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.MainActivity
import app.ultimate.core.Liquid
import app.ultimate.core.LiquidButton
import app.ultimate.launcher.FlingDrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * "Send to my other phone" in every app's Share menu. A sheet rises from the bottom with your connected devices as liquid
 * drops; tap one and the link, text or file goes there over the mesh (files up to 25 MB, checked on arrival).
 */
class FlingShareActivity : ComponentActivity() {
    private sealed interface Payload {
        data class Text(val text: String) : Payload
        data class File(val uri: Uri, val name: String, val mime: String, val size: Long) : Payload
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = MeshManager.get(this)
        runCatching { manager.resumeIfWasOn() }
        val payload = read(intent)
        setContent { Sheet(manager, payload) }
    }

    private fun read(i: Intent): Payload? {
        if (i.action != Intent.ACTION_SEND) return null
        @Suppress("DEPRECATION") val uri = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri != null) {
            var name = "file"; var size = -1L
            runCatching { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) } } }
            return Payload.File(uri, Fling.safeName(name), i.type ?: contentResolver.getType(uri) ?: "application/octet-stream", size)
        }
        val text = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()).joinToString("\n").trim()
        return if (text.isNotEmpty()) Payload.Text(text) else null
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun Sheet(manager: MeshManager, payload: Payload?) {
        val scope = rememberCoroutineScope()
        var targets by remember { mutableStateOf(manager.targets()) }
        var status by remember { mutableStateOf<String?>(null) }
        var progress by remember { mutableStateOf<Float?>(null) }
        var busy by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { while (true) { targets = manager.targets(); delay(700) } }
        val rise = remember { Animatable(0f) }
        LaunchedEffect(Unit) { rise.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
        val tooBig = (payload as? Payload.File)?.size?.let { Fling.refuse(it) }

        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(enabled = !busy) { finish() }) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().offset { IntOffset(0, ((1f - rise.value) * 900).roundToInt()) }
                .background(Brush.verticalGradient(listOf(Color(0xFF141B26), Color(0xFF0A0E14))), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .clickable(enabled = false) {}.navigationBarsPadding().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Send to my other device", color = Color.White, fontSize = 22.sp)
                Text(when (payload) {
                    is Payload.Text -> Fling.link(payload.text) ?: payload.text.take(140)
                    is Payload.File -> payload.name + if (payload.size >= 0) " · ${"%.1f".format(payload.size / 1_048_576.0)} MB" else ""
                    null -> "Nothing to send came with this."
                }, color = Liquid.dim, fontSize = 14.sp, maxLines = 3)
                tooBig?.let { Text("Too big: $it.", color = Color(0xFFFF8A80), fontSize = 14.sp) }
                Spacer(Modifier.height(6.dp))
                if (!manager.state.value.running) {
                    Text("Octopus Mesh is off on this phone. Turn it on here and on the other phone, with the same code.", color = Color.White, fontSize = 15.sp)
                    LiquidButton("Open Octopus Mesh", { startActivity(Intent(this@FlingShareActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "mesh")); finish() }, Modifier.fillMaxWidth())
                } else if (targets.isEmpty()) {
                    Text("Looking for your other devices… make sure Octopus Mesh is on there too.", color = Color.White, fontSize = 15.sp)
                } else FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    targets.forEachIndexed { i, t ->
                        FlingDrop(t, hot = false, index = i, onClick = if (payload == null || tooBig != null || busy) null else ({
                            busy = true; status = "Sending to ${t.name}…"
                            scope.launch {
                                val err = when (payload) {
                                    is Payload.Text -> manager.text(t.id, payload.text)
                                    is Payload.File -> {
                                        val bytes = withContext(Dispatchers.IO) { runCatching { contentResolver.openInputStream(payload.uri)?.use { s ->
                                            val b = s.readNBytesCompat(Fling.MAX_BYTES + 1); b } }.getOrNull() }
                                        when {
                                            bytes == null -> "that file could not be read"
                                            Fling.refuse(bytes.size.toLong()) != null -> Fling.refuse(bytes.size.toLong())
                                            else -> manager.file(t.id, payload.name, payload.mime, bytes) { progress = it }
                                        }
                                    }
                                }
                                status = if (err == null) "Sent to ${t.name}" else "Couldn't send: $err"
                                busy = false; progress = null
                                if (err == null) { delay(900); finish() }
                            }
                        }))
                    }
                }
                progress?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth(), color = Liquid.teal) }
                status?.let { Text(it, color = Liquid.teal, fontSize = 15.sp) }
            }
        }
    }
}

/** Reads at most [limit] bytes (so an enormous stream is never loaded whole just to be refused). */
private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024); var total = 0L
    while (total < limit) { val n = read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt()); if (n < 0) break; out.write(buf, 0, n); total += n }
    return out.toByteArray()
}
