package app.ultimate.home

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.ultimate.UltimateApp
import app.ultimate.core.Tool

internal val InstrumentInk = Color(0xFF10191E)
internal val InstrumentLight = Color(0xFFDFE9E8)
internal fun instrumentAccent(id: String?) = if (id in setOf("studio", "squeeze", "audio", "recorder", "photos", "prompter")) Color(0xFFF2B981) else Color(0xFF8DD7CB)
/** The small orb that carries the current tool's icon and accent; tap to switch tools, hold to go Home. */
/** Native shallow-depth scene: objects stay legible and every gesture also has a labeled tap target. */

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InstrumentOrb(tool: Tool?, busy: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit = onClick) {
    val accent = instrumentAccent(tool?.id)
    val haptics = LocalHapticFeedback.current
    Box(modifier.semantics { contentDescription = "Orbit for ${tool?.title ?: "Ultimate"}" }.clip(CircleShape)
        .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.30f), InstrumentInk)))
        .combinedClickable(role = Role.Button, onClickLabel = "Open orbit", onLongClickLabel = "Orb settings", onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }, onLongClick = onLongClick)
        .padding(10.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(accent, accent.copy(alpha = 0.42f), Color(0xFF1F454C)), Offset(size.width * 0.32f, size.height * 0.24f), size.width))
            drawCircle(accent.copy(alpha = 0.55f), style = Stroke(1.dp.toPx()))
            if (busy) drawArc(InstrumentLight, -90f, 245f, false, style = Stroke(3.dp.toPx()))
        }
        Icon(tool?.icon ?: Icons.Rounded.AutoAwesome, null, Modifier.size(24.dp), tint = InstrumentInk)
    }
}
