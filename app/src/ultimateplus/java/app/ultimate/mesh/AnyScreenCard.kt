package app.ultimate.mesh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.UltimateApp
import app.ultimate.core.GlassCard
import app.ultimate.core.Liquid
import app.ultimate.core.LiquidButton
import app.ultimate.send.QrCode
import app.ultimate.web.WebNodes
import kotlinx.coroutines.delay

/**
 * "Add any screen": a QR code that any browser can open (a laptop, an iPhone, a TV, the Quest) to join with nothing
 * installed. Joined screens show here and as drops when flinging.
 */
@Composable
fun AnyScreenCard() {
    val app = LocalContext.current.applicationContext as UltimateApp
    val send = app.sendServer
    val st by send.state.collectAsState()
    var screens by remember { mutableStateOf(emptyList<WebNodes.Node>()) }
    LaunchedEffect(st.running) { while (true) { screens = send.web.list(); delay(1_500) } }
    GlassCard(title = "Add any screen", live = screens.isNotEmpty(), accent = Liquid.teal) {
        Text("A laptop, an iPhone or iPad, a TV or the Quest joins by opening a page: no app, no account. Then fling apps, links and files to it like to your phones, and send files back.",
            color = Liquid.dim, fontSize = 14.sp, lineHeight = 20.sp)
        val join = st.address?.let { "$it/join" }
        if (join == null) {
            LiquidButton("Show the code to scan", { send.start() }, Modifier.fillMaxWidth())
            st.error?.let { Text(it, color = Color(0xFFFFB4A9), fontSize = 13.sp) }
            Text("Uses Send to computer on this Wi-Fi; it stays on until you turn it off there.", color = Liquid.dim, fontSize = 12.sp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(132.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp)) { QrCode(join, Modifier.size(116.dp)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Scan with the other screen's camera, or type:", color = Liquid.dim, fontSize = 12.sp)
                    Text(join, color = Color.White, fontSize = 13.sp)
                    Text("Same Wi-Fi as this phone. Keep the page open.", color = Liquid.dim, fontSize = 12.sp)
                }
            }
        }
        screens.forEach { n ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(n.name + "  ·  " + when (n.kind) { "headset" -> "headset"; "tv" -> "TV"; "phone" -> "phone"; "tab" -> "tablet"; else -> "computer" },
                    color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("Say hi", color = Color(0xFF0A0E14), fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Liquid.teal)
                    .clickable { send.web.send(n.id, WebNodes.chime(android.os.Build.MODEL)) }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
    }
}
