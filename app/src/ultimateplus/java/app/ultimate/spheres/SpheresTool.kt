package app.ultimate.spheres

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Vrpano
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.ultimate.UltimateApp
import app.ultimate.core.GlassCard
import app.ultimate.core.LibraryItem
import app.ultimate.core.Liquid
import app.ultimate.core.LiquidButton
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Step inside: capture a place (a backyard, a room, a trail) as 360° spheres you can stand in and walk between, on the phone
 * or in a headset. Everything stays on the phone.
 */
class SpheresTool(context: Context) : Tool {
    private val app = context.applicationContext
    private val store = SphereStore(app.filesDir)
    override val id = "spheres"
    override val title = "Step inside"
    override val tagline = "Capture a place in 360° and walk around it later, or in your headset"
    override val icon = Icons.Rounded.Vrpano
    override val headline: StateFlow<String> = MutableStateFlow("Turn on the spot, step, turn again")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override val acceptedMimePrefixes: List<String> = emptyList()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit

    private sealed interface Screen { data object List : Screen; data class Capture(val tourId: String, val name: String) : Screen; data class View(val tourId: String) : Screen }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        var screen by remember { mutableStateOf<Screen>(Screen.List) }
        var tick by remember { mutableIntStateOf(0) }
        BackHandler(screen != Screen.List) { screen = Screen.List; tick++ }
        when (val s = screen) {
            is Screen.Capture -> { CaptureScreen(s.tourId, s.name) { screen = Screen.List; tick++ }; return }
            is Screen.View -> { Viewer(s.tourId) { screen = Screen.List }; return }
            Screen.List -> Unit
        }
        val tours = remember(tick) { store.list() }
        Scaffold(containerColor = Liquid.bg, topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Liquid.bg, titleContentColor = Color.White, navigationIconContentColor = Color.White),
                title = { Text("Step inside") }, navigationIcon = { IconButton(onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GlassCard(title = "Capture a place", accent = Liquid.teal) {
                    Text("Stand somewhere and turn slowly on the spot until the ring is full. Then walk to the glowing ring a couple of metres on and turn again. Each spot becomes a sphere you can stand inside, and you can step between them like walking through it.",
                        color = Liquid.dim, fontSize = 14.sp, lineHeight = 20.sp)
                    LiquidButton("Capture a new place", { val id = "p" + System.currentTimeMillis().toString(36); screen = Screen.Capture(id, java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())) }, Modifier.fillMaxWidth())
                }
                tours.forEach { t -> PlaceCard(t, onOpen = { screen = Screen.View(t.id) }, onAdd = { screen = Screen.Capture(t.id, t.name) }, onDelete = { store.delete(t.id); tick++ }) }
                if (tours.isNotEmpty()) {
                    val send = (app as UltimateApp).sendServer
                    val st by send.state.collectAsState()
                    Text(st.secureAddress?.let { "On your Quest or a computer: open ${it}/spheres/<place>/ — each place shows its own address below when Send to computer is on." }
                        ?: "To walk around a place on your Quest, turn on Send to computer; each place then shows the address to open in the headset's browser.",
                        color = Liquid.dim, fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    private fun PlaceCard(t: SphereTour, onOpen: () -> Unit, onAdd: () -> Unit, onDelete: () -> Unit) {
        val thumb = remember(t.id, t.stops.size) {
            t.stops.firstOrNull()?.let { s -> runCatching { BitmapFactory.decodeFile(store.image(t.id, s.id).path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }.getOrNull() }
        }
        val send = (app as UltimateApp).sendServer
        val st by send.state.collectAsState()
        GlassCard(title = t.name, accent = Liquid.violet) {
            if (thumb != null) Image(thumb, null, Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen), contentScale = ContentScale.Crop)
            Text("${t.stops.size} sphere${if (t.stops.size == 1) "" else "s"}", color = Color.White, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t.stops.isNotEmpty()) LiquidButton("Step inside", onOpen, Modifier.weight(1f))
                LiquidButton("Add spheres", onAdd, Modifier.weight(1f))
            }
            st.secureAddress?.let { Text("Headset: $it/spheres/${t.id}/", color = Liquid.dim, fontSize = 12.sp) }
            Text("Delete", color = Color(0xFFFFB4A9), fontSize = 13.sp, modifier = Modifier.clickable(onClick = onDelete).padding(vertical = 4.dp))
        }
    }

    // ---------------------------------------------------------------- viewing

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun Viewer(tourId: String, onBack: () -> Unit) {
        val ctx = LocalContext.current
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView({ c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false; settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        // The page and its pictures come from this phone's own storage, under a made-up address that never leaves the phone.
                        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                            val u = req.url
                            if (u.host != HOST) return WebResourceResponse("text/plain", "utf-8", 404, "Not here", emptyMap(), null)
                            val path = u.path.orEmpty().removePrefix("/$tourId/")
                            if (path.isEmpty() || path == "index.html") return WebResourceResponse("text/html", "utf-8", ctx.assets.open("spheres.html"))
                            val (bytes, type) = store.serve(tourId, path) ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not here", emptyMap(), null)
                            return WebResourceResponse(type, null, bytes.inputStream())
                        }
                    }
                    loadUrl("https://$HOST/$tourId/index.html")
                }
            }, Modifier.fillMaxSize())
            IconButton(onBack, Modifier.statusBarsPadding().padding(8.dp).size(40.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
            }
        }
    }

    // ---------------------------------------------------------------- capturing

    private fun Context.activity(): Activity? { var c: Context? = this; while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }

    @Composable
    private fun CaptureScreen(tourId: String, name: String, onDone: () -> Unit) {
        val ctx = LocalContext.current
        var camera by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camera = it }
        LaunchedEffect(Unit) { if (!camera) ask.launch(Manifest.permission.CAMERA) }
        if (!camera) {
            Column(Modifier.fillMaxSize().background(Liquid.bg).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Step inside uses the camera to build the spheres. Camera pictures are placed onto the sphere on the phone and not kept on their own.", color = Color.White, lineHeight = 20.sp)
                LiquidButton("Allow the camera", { ask.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth().padding(top = 12.dp))
                Text("Back", color = Liquid.dim, modifier = Modifier.clickable(onClick = onDone).padding(12.dp))
            }
            return
        }
        var session by remember { mutableStateOf<Session?>(null) }
        var problem by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            val act = ctx.activity() ?: run { problem = "Can't start the camera from here."; return@LaunchedEffect }
            try {
                if (ArCoreApk.getInstance().requestInstall(act, true) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) { problem = "Install or update \"Google Play Services for AR\", then come back."; return@LaunchedEffect }
                session = Session(act)
            } catch (e: com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException) { problem = "This phone doesn't support ARCore, which spheres need to know where the camera points."
            } catch (e: Exception) { problem = "ARCore couldn't start: ${e.message ?: e.javaClass.simpleName}" }
        }
        val s = session
        if (s == null) {
            Column(Modifier.fillMaxSize().background(Liquid.bg).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text(problem ?: "Starting the camera…", color = Color.White)
                if (problem != null) Text("Back", color = Liquid.dim, modifier = Modifier.clickable(onClick = onDone).padding(12.dp))
            }
            return
        }
        val cap = remember(s) { SphereCapture(ctx, s, store, tourId, name) }
        val st by cap.state.collectAsState()
        val owner = LocalLifecycleOwner.current
        val view = LocalView.current
        var gl by remember { mutableStateOf<GLSurfaceView?>(null) }
        var note by remember { mutableStateOf<String?>(null) }
        DisposableEffect(s, owner) {
            view.keepScreenOn = true
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) { runCatching { s.resume() }; gl?.onResume() }
                if (e == Lifecycle.Event.ON_PAUSE) { gl?.onPause(); runCatching { s.pause() } }
            }
            owner.lifecycle.addObserver(obs)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) runCatching { s.resume() }
            onDispose { view.keepScreenOn = false; owner.lifecycle.removeObserver(obs); gl?.onPause(); runCatching { s.pause() }; cap.close(); Thread { runCatching { s.close() } }.start() }
        }
        val pulse by rememberInfiniteTransition(label = "ring").animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart), label = "p")

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView({ c -> GLSurfaceView(c).apply { setEGLContextClientVersion(2); preserveEGLContextOnPause = true; setRenderer(cap); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY; gl = this } }, Modifier.fillMaxSize())
            // Where to stand next: a glowing liquid ring on the floor.
            Canvas(Modifier.fillMaxSize()) {
                val x = st.nextX; val y = st.nextY
                if (st.phase == SphereCapture.Phase.Walking && x != null && y != null) {
                    val c = Offset(x * size.width, y * size.height); val r = size.minDimension * 0.09f
                    for (k in 0..2) { val p = (pulse + k / 3f) % 1f; drawOval(Liquid.teal.copy(alpha = 0.5f * (1 - p)), Offset(c.x - r * (1 + p), c.y - r * 0.45f * (1 + p)), androidx.compose.ui.geometry.Size(2 * r * (1 + p), r * 0.9f * (1 + p)), style = Stroke(4f)) }
                    drawOval(Brush.radialGradient(listOf(Color(0x883DDCD0), Color(0x003DDCD0)), c, r), Offset(c.x - r, c.y - r * 0.45f), androidx.compose.ui.geometry.Size(2 * r, r * 0.9f))
                }
            }
            Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${st.stops} sphere${if (st.stops == 1) "" else "s"} so far", color = Color.White, fontSize = 13.sp, modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp))
                (note ?: st.message ?: when (st.phase) {
                    SphereCapture.Phase.Walking -> st.nextMetres?.let { "Walk to the glowing ring (%.1f m), then tap Start sphere".format(it) } ?: "Look around for the glowing ring on the floor"
                    SphereCapture.Phase.Saving -> "Saving this sphere…"
                    SphereCapture.Phase.Turning -> if (st.coverage > 0.9) "Full. Tap Done here, or keep filling gaps" else null
                })?.let { Text(it, color = Color.White, fontSize = 15.sp, modifier = Modifier.background(Color(0xAA0A0E14), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 9.dp)) }
            }
            // While turning: a liquid ring that fills as the sphere does, and an arrow towards the biggest gap.
            if (st.phase == SphereCapture.Phase.Turning) Box(Modifier.align(Alignment.Center).size(150.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = center; val r = size.minDimension * 0.36f
                    drawCircle(Color(0x33FFFFFF), r, c, style = Stroke(10f))
                    drawArc(Brush.sweepGradient(listOf(Liquid.teal, Liquid.violet, Liquid.teal), c), -90f, 360f * st.coverage.toFloat(), false, Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = Stroke(10f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    val g = st.gapTurn
                    if (g != null && st.coverage < 0.97) {
                        val pitch = (st.gapPitch ?: 0.0).coerceIn(-60.0, 60.0)
                        val a = atan2Safe(-pitch, g) // screen angle: right = 0, up = -90
                        val tip = Offset(c.x + (r + 26f) * cos(a).toFloat(), c.y + (r + 26f) * sin(a).toFloat())
                        drawCircle(Liquid.teal, 9f + 3f * pulse, tip)
                    }
                }
                Text("${(st.coverage * 100).toInt()}%", color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
            }
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (st.phase) {
                    SphereCapture.Phase.Turning -> LiquidButton("Done here", { cap.finishSphere { err -> note = err } }, Modifier.weight(1f), enabled = st.coverage >= 0.15)
                    SphereCapture.Phase.Walking -> LiquidButton("Start sphere", { note = null; cap.startSphere() }, Modifier.weight(1f))
                    SphereCapture.Phase.Saving -> Unit
                }
                LiquidButton("Complete", { if (st.phase == SphereCapture.Phase.Turning && st.coverage >= 0.15) cap.finishSphere { onDone() } else onDone() }, Modifier.weight(1f), danger = true)
            }
        }
    }

    private fun atan2Safe(y: Double, x: Double) = if (x == 0.0 && y == 0.0) -PI / 2 else kotlin.math.atan2(y, x)

    companion object { const val HOST = "spheres.local" }
}
