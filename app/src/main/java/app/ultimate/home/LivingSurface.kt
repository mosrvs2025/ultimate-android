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

/** Native shallow-depth scene: objects stay legible and every gesture also has a labeled tap target. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LivingSurface(app: UltimateApp, tools: List<Tool>, running: Set<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val prefs = remember { app.getSharedPreferences("instrument", Context.MODE_PRIVATE) }
    var motion by rememberSaveable { mutableStateOf(prefs.getBoolean("motion", true)) }
    val tilt = rememberInstrumentTilt(motion)
    val fontSpace = LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    val hasHistory = remember(tools) { app.insights.recentOpens().isNotEmpty() }
    val haptic = LocalHapticFeedback.current
    BackHandler(expanded) { expanded = false }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(InstrumentInk).padding(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("IN YOUR ORBIT", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8DD7CB), letterSpacing = 2.sp)
                Text(if (running.isEmpty()) "Ready when you are" else "${running.size} in motion", color = InstrumentLight, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            }
            TextButton({ motion = !motion; prefs.edit().putBoolean("motion", motion).apply() }) {
                Text(if (motion) "Tilt on" else "Tilt off", color = InstrumentLight, style = MaterialTheme.typography.labelSmall)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 330.dp), contentAlignment = Alignment.Center) {
            // Narrow windows show three objects; wide windows show six. This is window adaptation, not a hinge claim.
            val compact = maxWidth < 340.dp
            val shown = tools.take(if (compact) 3 else 6)
            val sceneWidth = minOf(maxWidth, 580.dp)
            val sceneHeight = (if (compact) 330.dp else 370.dp) * fontSpace
            val focus by animateFloatAsState(if (expanded) 1f else 0f, spring(dampingRatio = 0.8f), label = "orbit-expand")
            Box(Modifier.width(sceneWidth).height(sceneHeight)
                .pointerInput(expanded) { detectTapGestures(onLongPress = { expanded = !expanded; haptic.performHapticFeedback(HapticFeedbackType.LongPress) }) }) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2, size.height / 2)
                    drawCircle(Brush.radialGradient(listOf(Color(0xFF8DD7CB).copy(alpha = 0.17f), Color.Transparent), c, size.minDimension * 0.48f), size.minDimension * 0.48f, c)
                    listOf(0.60f, 0.88f).forEach { f ->
                        val w = size.width * f
                        val h = size.height * f * 0.72f
                        drawOval(Color(0xFF8DD7CB).copy(alpha = 0.13f), Offset(c.x - w / 2, c.y - h / 2), Size(w, h), style = Stroke(1.dp.toPx()))
                    }
                }
                InstrumentOrb(null, running.isNotEmpty(), Modifier.align(Alignment.Center).size(88.dp).testTag("home-orb"),
                    onClick = { expanded = !expanded }, onLongClick = { app.tools.firstOrNull { it.id == "orb" }?.let(app.shell::open) })
                val positions = if (compact) listOf(-0.25f to -0.29f, 0.25f to -0.29f, 0f to 0.31f)
                    else listOf(-0.29f to -0.27f, 0.29f to -0.27f, -0.31f to 0.14f, 0.31f to 0.14f, -0.18f to 0.38f, 0.18f to 0.38f)
                shown.forEachIndexed { i, tool ->
                    key(tool.id) {
                        val (px, py) = positions[i]
                        val depth = if (i < 2) 1f else 0.90f
                        Column(Modifier.align(Alignment.Center)
                            .offset(x = sceneWidth * px, y = sceneHeight * py)
                            .width(88.dp).graphicsLayer {
                                translationX = tilt.x * (if (i < 2) 6f else 3f) * density
                                translationY = tilt.y * 4f * density
                                scaleX = depth + focus * 0.03f; scaleY = scaleX
                            }
                            .clip(RoundedCornerShape(18.dp))
                            .combinedClickable(role = Role.Button, onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); app.shell.openFrom(tool, EntryOrigin.SURFACE) },
                                onLongClick = { app.shell.showLibraryOf(tool.id) }, onLongClickLabel = "${tool.title} results")
                            .padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(48.dp).graphicsLayer { rotationZ = if (px < 0) -5f else 5f }
                                .clip(RoundedCornerShape(16.dp))
                                .background(Brush.linearGradient(listOf(instrumentAccent(tool.id).copy(alpha = 0.42f), Color(0xFF24363D)))), contentAlignment = Alignment.Center) {
                                Icon(tool.icon, null, Modifier.size(27.dp), tint = instrumentAccent(tool.id))
                            }
                            Text(tool.title, color = InstrumentLight, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (tool.id in running) Text("RUNNING", color = instrumentAccent(tool.id), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        Text(if (expanded) "Tap a tool to begin · hold for its results" else "Tap the orb for recent tools · hold for orb settings", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp), color = InstrumentLight.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        if (expanded) {
            HorizontalDivider(color = InstrumentLight.copy(alpha = 0.15f))
            Text(if (hasHistory) "From the last 48 hours" else "Your starting tools", Modifier.padding(8.dp), color = InstrumentLight, style = MaterialTheme.typography.labelMedium)
            tools.take(3).forEach { tool ->
                val headline by tool.headline.collectAsState()
                TextButton({ app.shell.openFrom(tool, EntryOrigin.SURFACE) }, Modifier.fillMaxWidth()) {
                    Icon(tool.icon, null, Modifier.size(20.dp), tint = instrumentAccent(tool.id))
                    Text("${tool.title} · $headline", Modifier.padding(start = 10.dp).weight(1f), color = InstrumentLight, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (tools.none { app.insights.lastAt(it.id) > 0 }) Text("Your first tools. This surface learns as you use it.", Modifier.padding(8.dp), color = InstrumentLight, style = MaterialTheme.typography.bodySmall)
        }
    }
}

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

/** No background sensor listener; honor the system animation switch and offer a local opt-out. */
@Composable
private fun rememberInstrumentTilt(enabled: Boolean): Offset {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var tilt by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(context, owner, enabled) {
        val manager = context.getSystemService(SensorManager::class.java)
        val reduced = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                val rotation = context.display?.rotation ?: android.view.Surface.ROTATION_0
                val x = event.values[0]; val y = event.values[1]
                val mapped = when (rotation) {
                    android.view.Surface.ROTATION_90 -> Offset(-y, -x)
                    android.view.Surface.ROTATION_180 -> Offset(x, -y)
                    android.view.Surface.ROTATION_270 -> Offset(y, x)
                    else -> Offset(-x, y)
                }
                val target = Offset((mapped.x / 5f).coerceIn(-1f, 1f), (mapped.y / 5f).coerceIn(-1f, 1f))
                tilt = Offset(tilt.x * 0.88f + target.x * 0.12f, tilt.y * 0.88f + target.y * 0.12f)
            }
        }
        fun update() {
            manager?.unregisterListener(listener)
            if (enabled && !reduced && sensor != null && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            else tilt = Offset.Zero
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        owner.lifecycle.addObserver(observer); update()
        onDispose { owner.lifecycle.removeObserver(observer); manager?.unregisterListener(listener) }
    }
    return tilt
}
