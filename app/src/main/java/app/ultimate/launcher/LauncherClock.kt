package app.ultimate.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the clock part of the home screen shows, laid out the way the look asks for. */
internal object ClockText {
    /** "14:05" or "2:05", in the style asked for. */
    fun time(nowMs: Long, style: ClockStyle, system24: Boolean, locale: Locale = Locale.getDefault(), tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val h24 = when (style) { ClockStyle.H24 -> true; ClockStyle.H12 -> false; ClockStyle.System -> system24 }
        return SimpleDateFormat(if (h24) "HH:mm" else "h:mm", locale).apply { timeZone = tz }.format(Date(nowMs))
    }

    fun format(pattern: String, nowMs: Long, locale: Locale = Locale.getDefault(), tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String =
        SimpleDateFormat(pattern, locale).apply { timeZone = tz }.format(Date(nowMs))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ClockBlock(onOpenUltimate: () -> Unit, settings: LauncherSettings) {
    val th = LocalLauncherTheme.current
    val look = LocalLook.current
    val system24 = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(60_000 - now % 60_000 + 50) } }
    val time = ClockText.time(now, settings.clock, system24)
    val family = look.face
    when (settings.clockLayout) {
        ClockLayout.Day -> {
            val day = ClockText.format("EEE", now).uppercase().take(3)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(day, style = TextStyle(brush = Brush.linearGradient(listOf(look.fg, th.a, th.b)), fontSize = 64.sp, fontWeight = FontWeight.Thin, letterSpacing = 10.sp, fontFamily = family))
            }
            Text(LauncherMath.clockLine(now, settings.clock, settings.showDate, system24), color = look.fg.copy(alpha = 0.75f), fontSize = 16.sp, letterSpacing = 1.sp, fontFamily = family)
        }
        ClockLayout.Digital -> {
            Text(time, color = look.fg, fontSize = 76.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = 2.sp, fontFamily = family)
            if (settings.showDate) Text(ClockText.format("EEEE, d MMMM", now), color = look.dim, fontSize = 16.sp, letterSpacing = 0.5.sp, fontFamily = family)
        }
        ClockLayout.Terminal -> {
            Text("ultimate@phone:~\$ date", color = th.a, fontSize = 13.sp, fontFamily = family)
            Text(if (settings.showDate) ClockText.format("EEE d MMM yyyy", now) + "  " + time else time, color = look.fg, fontSize = 20.sp, fontFamily = family)
            Text("ultimate@phone:~\$ ▌", color = th.a, fontSize = 13.sp, fontFamily = family)
        }
        ClockLayout.Editorial -> {
            Text(ClockText.format("EEEE", now).uppercase(), color = look.dim, fontSize = 12.sp, letterSpacing = 4.sp, fontFamily = family)
            Text(ClockText.format("d", now), color = look.fg, fontSize = 92.sp, fontWeight = FontWeight.Light, fontFamily = family)
            Text(ClockText.format("MMMM yyyy", now) + "  ·  " + time, color = look.fg.copy(alpha = 0.8f), fontSize = 16.sp, fontStyle = FontStyle.Italic, fontFamily = family)
            Box(Modifier.padding(top = 10.dp).width(44.dp).height(2.dp).background(th.a))
        }
        ClockLayout.Hidden -> Unit
    }
    // A quiet way into Ultimate itself.
    Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onOpenUltimate).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(Brush.linearGradient(listOf(th.a, th.b)), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("Ultimate", color = look.dim, fontSize = 13.sp, fontFamily = family)
    }
}
