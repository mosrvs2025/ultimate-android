package app.ultimate.footprint

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private fun hm(ms: Long): String { val m = ms / 60_000; return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }
private val card = @Composable { Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp) }

/** What you watched and listened to. Off until switched on, and it needs notification access to see other apps' players. */
@Composable
internal fun MediaCard() {
    val context = LocalContext.current
    val log = remember { MediaLog(context) }
    var on by remember { mutableStateOf(log.enabled) }
    var tick by remember { mutableIntStateOf(0) }
    val access = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    var list by remember { mutableStateOf<List<Played>>(emptyList()) }
    LaunchedEffect(on, tick) { list = if (on) withContext(Dispatchers.IO) { log.since(System.currentTimeMillis() - 30 * 86_400_000L) } else emptyList() }
    Column(card(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep what I watch and listen to", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Switch(on, { on = it; log.enabled = it })
        }
        if (!on) Text("Every video and song shows its title to the phone, so any app with notification access can write it all down. Switch this on and I will do the same, on this phone only, for 4 months.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (!access) {
            Text("I need notification access to see what other apps are playing.", style = MaterialTheme.typography.bodyMedium)
            Button({ context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Allow notification access") }
        } else if (list.isEmpty()) Text("Listening. Play something for ten seconds and it shows up here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (on && list.isNotEmpty()) {
            val total = list.sumOf { it.ms }
            Text("${hm(total)} of watching and listening in the last 30 days", fontWeight = FontWeight.SemiBold)
            MediaStats.byApp(list).take(3).forEach { (pkg, ms) -> Text("•  ${FootprintReaders.label(context, pkg)}: ${hm(ms)}", style = MaterialTheme.typography.bodyMedium) }
            MediaStats.topArtists(list, 3).takeIf { it.isNotEmpty() }?.let { Text("Most with: " + it.joinToString { (a, ms) -> "$a (${hm(ms)})" }, style = MaterialTheme.typography.bodyMedium) }
            val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            list.take(5).forEach { p ->
                Column { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${FootprintReaders.label(context, p.pkg)} · ${fmt.format(Date(p.startedAt))} · ${hm(p.ms)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            TextButton({ log.clear(); tick++ }) { Text("Erase it all") }
        }
    }
}

/** Where photos were taken, read from the location each photo carries. Nothing is looked up online. */
internal object PhotoPlaces {
    fun permissions() = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
    fun allowed(c: Context) = permissions().all { c.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    fun read(c: Context, limit: Int = 1500): List<Shot> {
        val out = ArrayList<Shot>()
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
        runCatching {
            c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT $limit")?.use { cur ->
                while (cur.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getLong(0))
                    val at = cur.getLong(1)
                    runCatching {
                        c.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))?.use { s ->
                            val ll = ExifInterface(s).latLong
                            if (ll != null && at > 0) out += Shot(at, ll[0], ll[1])
                        }
                    }
                }
            }
        }
        return out
    }
}

@Composable
internal fun PlacesCard() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(PhotoPlaces.allowed(context)) }
    var shots by remember { mutableStateOf<List<Shot>?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> on = r.values.all { it } }
    LaunchedEffect(on) { if (on) shots = withContext(Dispatchers.IO) { PhotoPlaces.read(context) } }
    Column(card(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val s = shots
        if (!on) {
            Text("Most phones stamp every photo with exactly where it was taken. Any app that can see your photos can map your life from that. Let me read the stamps, on this phone, to show you what that looks like. Nothing is looked up online.", style = MaterialTheme.typography.bodyMedium)
            Button({ ask.launch(PhotoPlaces.permissions()) }) { Text("Show where my photos were taken") }
        } else if (s == null) Text("Reading your photos…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (s.isEmpty()) Text("None of your recent photos carry a location. Either the camera's location tag is off, or this is a good sign.", style = MaterialTheme.typography.bodyMedium)
        else {
            val places = remember(s) { PlaceMath.cluster(s) }
            PlaceMath.sentences(s, places).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
            val minLat = places.minOf { it.lat }; val maxLat = places.maxOf { it.lat }; val minLon = places.minOf { it.lon }; val maxLon = places.maxOf { it.lon }
            val dot = MaterialTheme.colorScheme.primary; val home = MaterialTheme.colorScheme.tertiary
            val homePlace = remember(places) { PlaceMath.home(places) }
            Canvas(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                val pad = 18.dp.toPx(); val max = places.maxOf { it.count }.toFloat()
                places.forEach { p ->
                    val x = pad + (if (maxLon > minLon) ((p.lon - minLon) / (maxLon - minLon)).toFloat() else 0.5f) * (size.width - 2 * pad)
                    val y = pad + (if (maxLat > minLat) (1 - ((p.lat - minLat) / (maxLat - minLat))).toFloat() else 0.5f) * (size.height - 2 * pad)
                    drawCircle((if (p === homePlace) home else dot).copy(alpha = 0.75f), (4 + 10 * p.count / max).dp.toPx(), Offset(x, y))
                }
            }
            places.take(4).forEach { p ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.padding(vertical = 6.dp)) {
                    Text(PlaceMath.degrees(p.lat, p.lon) + if (p === homePlace) "  (home?)" else "", Modifier.weight(1f)); Text("${p.count} photos ›", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Steps, sleep and heart rate that other apps wrote into Health Connect. Read once you allow it. */
@Composable
internal fun HealthCard() {
    val context = LocalContext.current
    var status by remember { mutableIntStateOf(HealthConnectReader.status(context)) }
    var granted by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf<List<HealthDay>?>(null) }
    LaunchedEffect(status) { if (HealthConnectReader.available(context)) { granted = HealthConnectReader.granted(context); if (granted) days = withContext(Dispatchers.IO) { HealthConnectReader.days(context) } } }
    val ask = rememberLauncherForActivityResult(androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()) { got -> granted = got.containsAll(HealthConnectReader.permissions); status = HealthConnectReader.status(context) + 0 }
    LaunchedEffect(granted) { if (granted && days == null) days = withContext(Dispatchers.IO) { HealthConnectReader.days(context) } }
    Column(card(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !HealthConnectReader.available(context) -> {
                Text("Health apps like Samsung Health, Fitbit and Google Fit can store your steps, sleep and heart rate in Health Connect. It isn't set up on this phone.", style = MaterialTheme.typography.bodyMedium)
                TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Get Health Connect") }
            }
            !granted -> {
                Text("Your health apps keep years of steps, sleep and heart rate in Health Connect, and any app you allow can read it. Allow it here to see yours. Read only, and it stays on this phone.", style = MaterialTheme.typography.bodyMedium)
                Button({ ask.launch(HealthConnectReader.permissions) }) { Text("Show my health data") }
            }
            days == null -> Text("Reading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            days!!.isEmpty() -> Text("Health Connect is on, but no app has written anything in the last 30 days.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                HealthMath.sentences(days!!).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
                val steps = days!!.takeLast(30); val max = steps.maxOf { it.steps }.coerceAtLeast(1)
                val bar = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                    val w = size.width / steps.size
                    steps.forEachIndexed { i, e -> val h = size.height * (e.steps / max.toFloat()).coerceAtLeast(0.03f); drawRoundRect(bar, Offset(i * w + 2.dp.toPx(), size.height - h), androidx.compose.ui.geometry.Size(w - 4.dp.toPx(), h), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())) }
                }
                TextButton({ context.startActivity(Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Change what Ultimate can read") }
            }
        }
    }
}
