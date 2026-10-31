package app.ultimate.core

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val iconCache = LruCache<String, ImageBitmap>(300)

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf(iconCache.get(packageName)) }
    LaunchedEffect(packageName) {
        if (icon == null) {
            icon = withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(128, 128).asImageBitmap() }.getOrNull()
                    ?.also { iconCache.put(packageName, it) }
            }
        }
    }
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.22f)), contentAlignment = Alignment.Center) {
        icon?.let { Image(it, null, Modifier.fillMaxSize()) }
            ?: Icon(Icons.Rounded.Android, null, Modifier.size(size * 0.6f), tint = MaterialTheme.colorScheme.outline)
    }
}

/** A circle that pops in, then a check mark that draws itself. */
@Composable
fun SuccessCheck(size: Dp, color: Color, modifier: Modifier = Modifier) {
    val circle = remember { Animatable(0f) }
    val tick = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        circle.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        tick.animateTo(1f, tween(320))
    }
    Canvas(modifier.size(size)) {
        drawCircle(color, radius = this.size.minDimension / 2 * circle.value)
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.28f, h * 0.52f)
            lineTo(w * 0.44f, h * 0.68f)
            lineTo(w * 0.74f, h * 0.36f)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val partial = Path()
        measure.getSegment(0f, measure.length * tick.value, partial, true)
        drawPath(partial, Color.White, style = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ───────────────────────────── Intents ───────────────────────────

fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

fun openApp(context: Context, pkg: String) {
    val launch = context.packageManager.getLaunchIntentForPackage(pkg)
    if (launch != null) context.startActivity(launch) else Toast.makeText(context, "This app has no launcher screen", Toast.LENGTH_SHORT).show()
}

fun openAppSettings(context: Context, pkg: String) =
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

fun uninstallApp(context: Context, pkg: String) =
    context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

fun openInPlayStore(context: Context, pkg: String) = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.onFailure { Toast.makeText(context, "No app store available", Toast.LENGTH_SHORT).show() }

fun shareFiles(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.setType("application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share"))
}

fun openDownloads(context: Context) = runCatching {
    context.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Returns a function that runs [action] after asking for notification consent on Android 13+; the action runs either way. */
@Composable
fun rememberNotificationConsent(): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    return { action ->
        val needs = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) {
            pending = action
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }
}

/** Shortcuts every tool screen can show. In a single-tool build there is no Home, so the tool's back arrow is hidden. */
data class ShellNav(
    val standalone: Boolean,
    val openLibrary: () -> Unit,
    val openActivity: () -> Unit,
    val openAbout: () -> Unit = {},
    /** Every tool in this edition, so results can be handed from one tool to another. */
    val tools: List<Tool> = emptyList(),
    val send: (Tool, List<android.net.Uri>) -> Unit = { _, _ -> },
)

val LocalShellNav = androidx.compose.runtime.staticCompositionLocalOf<ShellNav?> { null }

/** Library and Activity buttons for a tool's top bar: icons only, so tools with their own actions never run out of room. */
@Composable
fun ShellActions() {
    val nav = LocalShellNav.current ?: return
    androidx.compose.material3.IconButton(nav.openActivity) { Icon(Icons.Rounded.History, "Activity") }
    androidx.compose.material3.IconButton(nav.openLibrary) { Icon(Icons.Rounded.VideoLibrary, "Library") }
    // A one-tool app has no Home, so its version and settings open from here.
    if (nav.standalone) androidx.compose.material3.IconButton(nav.openAbout) { Icon(Icons.Rounded.Info, "About and settings") }
}

/** The tool's own back arrow, unless this build is the tool on its own. */
@Composable
fun ToolBackButton(onBack: () -> Unit) {
    if (LocalShellNav.current?.standalone == true) return
    androidx.compose.material3.IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
}

/** Plain top bar for a tool's first screen. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BackBar(onBack: () -> Unit, title: String = "") {
    androidx.compose.material3.TopAppBar(
        title = { androidx.compose.material3.Text(title) },
        navigationIcon = { ToolBackButton(onBack) },
        actions = { ShellActions() },
    )
}
