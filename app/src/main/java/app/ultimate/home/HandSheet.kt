package app.ultimate.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ultimate.UltimateApp
import app.ultimate.recipes.RecipeRules
import app.ultimate.core.ToolLaunch
import app.ultimate.core.appear
import app.ultimate.core.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether this edition ships Share clean (it is declared only in the full editions' manifests). */
fun hasShareClean(context: Context) =
    runCatching { context.packageManager.getActivityInfo(ComponentName(context, "app.ultimate.clean.ShareCleanActivity"), 0); true }.getOrDefault(false)

fun openShareClean(context: Context, uris: List<Uri>, mime: String) {
    val i = (if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)))
        .setType(mime).setClassName(context, "app.ultimate.clean.ShareCleanActivity").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(i)
}

/** Whatever was handed to the app, with what it can do for it, most useful first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandSheet(app: UltimateApp, pending: Incoming) {
    val context = LocalContext.current
    val shell = app.shell
    val actions = remember(pending) {
        Commands.available(Commands.forMime(pending.mime), app.tools.map { it.id }.toSet(), hasShareClean(context))
    }
    val seen = remember { mutableSetOf<Any>() }
    val kind = when { pending.mime.startsWith("video/") -> "video"; pending.mime.startsWith("image/") -> "photo"; pending.mime.startsWith("audio/") -> "audio file"; else -> "file" }
    ModalBottomSheet(onDismissRequest = shell::dismissIncoming, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(pending.uris.take(8)) { Preview(it) } }
            Text(
                if (pending.uris.size == 1) "What should happen to this $kind?" else "What should happen to these ${pending.uris.size} ${kind}s?",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
            actions.forEachIndexed { i, a ->
                val tool = app.tools.firstOrNull { it.id == a.toolId }
                Row(
                    Modifier.fillMaxWidth().appear(a.id, seen, i).pressScale(0.97f).clip(RoundedCornerShape(18.dp)).background(if (i == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable {
                            shell.dismissIncoming()
                            app.insights.count("hand.$kind.${a.id}")
                            if (a.shareClean) openShareClean(context, pending.uris, pending.mime) else tool?.let { shell.open(it, ToolLaunch(pending.uris)) }
                        }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(tool?.icon ?: Icons.Rounded.LocationOff, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(a.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                }
            }
            val kindOf = RecipeRules.kindOf(pending.mime)
            val recipes = if (kindOf == null || app.tools.none { it.id == "recipes" }) emptyList()
                else RecipeRules.forKind(app.recipes.all, kindOf, app.tools.map { it.id }.toSet())
            if (recipes.isNotEmpty()) {
                Text("Recipes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recipes) { r ->
                        Column(
                            Modifier.pressScale(0.96f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable { app.insights.count("hand.recipe.${r.id}"); shell.dismissIncoming(); app.recipes.run(r, pending.uris); app.tools.firstOrNull { it.id == "recipes" }?.let(shell::open) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(r.steps.joinToString(" → ") { it.label }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Preview(uri: Uri) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) { bitmap = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(uri, android.util.Size(300, 300), null) }.getOrNull() } }
    Box(Modifier.size(84.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
