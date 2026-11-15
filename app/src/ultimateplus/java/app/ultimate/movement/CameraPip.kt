package app.ultimate.movement

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import app.ultimate.send.SendServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A small live window with the picture from another device's camera (a second phone, a tablet or a laptop propped up to watch you
 * walk), shared through the phone's own Wi-Fi server. It sits over the recording screen so a screen recording captures you and the
 * numbers together. Drag it anywhere, tap to make it bigger or smaller, the × hides it until the picture is shared again. The
 * picture is only shown, never saved, and it disappears a few seconds after the other device stops sharing.
 */
@Composable
fun CameraPip(server: SendServer, modifier: Modifier = Modifier) {
    val shot by server.actions.screen.collectAsState()
    var hidden by remember { mutableStateOf(false) }
    var big by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val s = shot
    LaunchedEffect(s == null) { if (s == null) hidden = false }
    if (s == null || hidden) return
    // Pictures arrive several times a second; they are shrunk as they are read, which is plenty for a window this size.
    val bmp: ImageBitmap? by produceState<ImageBitmap?>(null, s.at) {
        value = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(s.frame, 0, s.frame.size, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() } ?: value
    }
    val b = bmp ?: return
    Box(modifier.offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }.width(if (big) 230.dp else 128.dp)
        .clip(RoundedCornerShape(14.dp)).border(1.5.dp, Color(0x883DDCD0), RoundedCornerShape(14.dp)).background(Color.Black)
        .pointerInput(Unit) { detectDragGestures { c, d -> c.consume(); drag += d } }
        .pointerInput(Unit) { detectTapGestures(onTap = { big = !big }) }) {
        Image(b, "Camera from ${s.from}", Modifier.width(if (big) 230.dp else 128.dp), contentScale = ContentScale.FillWidth)
        Text("×", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 8.dp).pointerInput(Unit) { detectTapGestures(onTap = { hidden = true }) })
        Text(s.from, color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
    }
}
