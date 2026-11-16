package app.ultimate.movement

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.send.CamRegistry
import app.ultimate.send.SendServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Small live windows with the pictures from other devices' cameras (a phone, tablet or laptop propped up to watch you walk, or a
 * device of yours reached through Octopus Mesh), stacked and draggable, up to three at once. They sit over the recording screen so
 * a screen recording captures you and the numbers together. Tap one to make it bigger or smaller, × to hide it until that camera
 * stops and starts again. Pictures are only shown, never saved, and a camera disappears a few seconds after it stops.
 */
@Composable
fun CameraPip(server: SendServer, modifier: Modifier = Modifier) {
    val tick by server.camTick.collectAsState()
    var clock by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); clock++ } }          // so a camera that went quiet drops off
    val hidden = remember { mutableStateMapOf<String, Boolean>() }
    val live = remember(tick, clock) { server.cams.list() }
    LaunchedEffect(clock) { val ids = server.cams.list().map { it.id }.toSet(); hidden.keys.removeAll { it !in ids } }
    val shown = live.filter { hidden[it.id] != true }.takeLast(3)
    if (shown.isEmpty()) return
    var drag by remember { mutableStateOf(Offset.Zero) }
    Column(modifier.offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        shown.forEach { cam -> PipTile(cam, onDrag = { drag += it }, onHide = { hidden[cam.id] = true }) }
    }
}

@Composable
private fun PipTile(cam: CamRegistry.Cam, onDrag: (Offset) -> Unit, onHide: () -> Unit) {
    var big by remember(cam.id) { mutableStateOf(false) }
    var bmp by remember(cam.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cam.at) {
        // Pictures arrive several times a second; they are shrunk as they are read, which is plenty for a window this size.
        val decoded = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(cam.frame, 0, cam.frame.size, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }
        if (decoded != null) bmp = decoded          // a picture that fails to read leaves the last good one showing
    }
    val b = bmp ?: return
    val w = if (big) 230.dp else 128.dp
    Box(Modifier.width(w).clip(RoundedCornerShape(14.dp)).border(1.5.dp, Color(0x883DDCD0), RoundedCornerShape(14.dp)).background(Color.Black)
        .pointerInput(Unit) { detectDragGestures { c, d -> c.consume(); onDrag(d) } }
        .pointerInput(Unit) { detectTapGestures(onTap = { big = !big }) }) {
        Image(b, "Camera from ${cam.name}", Modifier.width(w), contentScale = ContentScale.FillWidth)
        Text("×", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 8.dp).pointerInput(Unit) { detectTapGestures(onTap = { onHide() }) })
        Text(cam.name, color = Color.White, fontSize = 10.sp, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
    }
}
