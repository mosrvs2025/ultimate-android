package app.ultimate.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What press-and-hold on an empty part of the home screen brings up: a panel at the bottom with the three things you most often want
 * to change (the wallpaper, the colours, the rest of the settings). Tap anywhere above it, or press back, to put it away.
 */
@Composable
internal fun EditHomePanel(accent: Accent, onWallpaper: () -> Unit, onColours: () -> Unit, onSettings: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose).semantics { contentDescription = "Edit home screen. Tap to close" }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).navigationBarsPadding().clip(RoundedCornerShape(28.dp))
                .background(Color(0xF0101620)).clickable(enabled = false) {}.padding(vertical = 20.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Edit home screen", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PanelAction(Icons.Rounded.Image, "Change wallpaper", accent, onWallpaper)
                PanelAction(Icons.Rounded.Palette, "Colours", accent, onColours)
                PanelAction(Icons.Rounded.Tune, "Launcher settings", accent, onSettings)
            }
        }
    }
}

@Composable
private fun PanelAction(icon: ImageVector, label: String, accent: Accent, onClick: () -> Unit) {
    Column(Modifier.width(96.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(listOf(accent.a.copy(alpha = 0.28f), accent.b.copy(alpha = 0.22f)))), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Text(label, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}
