package app.ultimate.ruler

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Physical size on screen. Pure, so the arithmetic is tested. */
object RulerMath {
    const val CARD_W_MM = 85.60
    const val CARD_H_MM = 53.98

    /** Pixels per millimetre from what the screen reports, corrected by the person's card check ([scale], 1 = as reported). */
    fun pxPerMm(dpi: Float, scale: Float = 1f) = dpi / 25.4f * scale

    /** Tick length class at a millimetre mark: 2 for whole centimetres, 1 for half, 0 otherwise. */
    fun tick(mm: Int) = when { mm % 10 == 0 -> 2; mm % 5 == 0 -> 1; else -> 0 }

    /** Tick class at a sixteenth-of-an-inch mark: 3 inch, 2 half, 1 quarter, 0 otherwise. */
    fun inchTick(sixteenth: Int) = when { sixteenth % 16 == 0 -> 3; sixteenth % 8 == 0 -> 2; sixteenth % 4 == 0 -> 1; else -> 0 }
}

/** The card check, remembered: how far this screen's reported density is off. */
object RulerScale {
    private fun prefs(c: Context) = c.getSharedPreferences("ruler", Context.MODE_PRIVATE)
    fun get(c: Context) = prefs(c).getFloat("scale", 1f)
    fun set(c: Context, v: Float) = prefs(c).edit().putFloat("scale", v).apply()
    fun checked(c: Context) = prefs(c).contains("scale")
}

/**
 * A real ruler, drawn at true size along the long edge of the screen, in centimetres and inches. Screens sometimes
 * report their density a little off, so a credit card (a standard 85.6 mm) checks it once.
 */
class RulerTool : Tool {
    override val id = "ruler"
    override val title = "Ruler"
    override val tagline = "A true-size ruler on your screen"
    override val icon = Icons.Rounded.Straighten
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Centimetres and inches, true to size")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = false
    override suspend fun refresh() = Unit

    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        BackHandler(onBack = onExit)
        RulerScreen(onExit)
    }
}

@Composable
fun RulerScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val dm = context.resources.displayMetrics
    var scale by remember { mutableFloatStateOf(RulerScale.get(context)) }
    var checking by rememberSaveable { mutableStateOf(!RulerScale.checked(context)) }
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Ruler", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            FilterChip(checking, { checking = !checking }, { Text(if (checking) "Done" else "Check with a card") })
        }
        if (checking) {
            // Card check: drag until the outline matches a real bank or ID card laid on the screen.
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Lay a bank card or ID card on the screen and drag until the outline matches its edges.", style = MaterialTheme.typography.bodyMedium)
                val pxMm = RulerMath.pxPerMm(dm.xdpi, scale)
                val cardH = with(androidx.compose.ui.platform.LocalDensity.current) { (RulerMath.CARD_H_MM * pxMm).toFloat().toDp() }
                Canvas(Modifier.fillMaxWidth().height(cardH + 4.dp)) {
                    val w = (RulerMath.CARD_W_MM * pxMm).toFloat(); val h = (RulerMath.CARD_H_MM * pxMm).toFloat()
                    drawRoundRect(accent.copy(alpha = 0.12f), Offset.Zero, Size(w, h), CornerRadius(3.2f * pxMm))
                    drawRoundRect(accent, Offset.Zero, Size(w, h), CornerRadius(3.2f * pxMm), style = Stroke(2.dp.toPx()))
                }
                Slider(scale, { scale = it; RulerScale.set(context, it) }, valueRange = 0.85f..1.15f)
                TextButton({ scale = 1f; RulerScale.set(context, 1f) }) { Text("Reset to what the screen reports") }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                // Centimetres down the left edge, inches down the right; 0 at the top of this area.
                Canvas(Modifier.fillMaxHeight().weight(1f)) {
                    val pxMm = RulerMath.pxPerMm(dm.ydpi, scale)
                    val paint = android.graphics.Paint().apply { color = android.graphics.Color.argb(255, (ink.red * 255).toInt(), (ink.green * 255).toInt(), (ink.blue * 255).toInt()); textSize = 14.dp.toPx(); isAntiAlias = true }
                    var mm = 0
                    while (mm * pxMm <= size.height) {
                        val y = mm * pxMm
                        val len = when (RulerMath.tick(mm)) { 2 -> 28.dp.toPx(); 1 -> 18.dp.toPx(); else -> 10.dp.toPx() }
                        drawLine(ink, Offset(0f, y), Offset(len, y), strokeWidth = if (mm % 10 == 0) 2f else 1f)
                        if (mm % 10 == 0 && mm > 0) drawContext.canvas.nativeCanvas.drawText("${mm / 10}", len + 6.dp.toPx(), y + 5.dp.toPx(), paint)
                        mm++
                    }
                    drawContext.canvas.nativeCanvas.drawText("cm", 34.dp.toPx(), 16.dp.toPx(), paint)
                    val pxIn = pxMm * 25.4f
                    var s = 0
                    while (s * pxIn / 16 <= size.height) {
                        val y = s * pxIn / 16
                        val len = when (RulerMath.inchTick(s)) { 3 -> 28.dp.toPx(); 2 -> 20.dp.toPx(); 1 -> 14.dp.toPx(); else -> 8.dp.toPx() }
                        drawLine(ink, Offset(size.width - len, y), Offset(size.width, y), strokeWidth = if (s % 16 == 0) 2f else 1f)
                        if (s % 16 == 0 && s > 0) drawContext.canvas.nativeCanvas.drawText("${s / 16}", size.width - len - 18.dp.toPx(), y + 5.dp.toPx(), paint)
                        s++
                    }
                    drawContext.canvas.nativeCanvas.drawText("in", size.width - 52.dp.toPx(), 16.dp.toPx(), paint)
                }
            }
        }
    }
}
