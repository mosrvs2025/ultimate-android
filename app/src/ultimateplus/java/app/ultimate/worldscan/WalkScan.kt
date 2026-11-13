package app.ultimate.worldscan

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.WindowManager
import app.ultimate.room.RoomMap
import app.ultimate.room.ScanIntegrator
import app.ultimate.room.DepthFrame
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot

enum class WalkPhase { READY, RECORDING, ENDED }

/** What the walk screen shows, refreshed ten times a second. Nothing here is estimated for show: every number comes from ARCore, the sensors or the map being built. */
data class WalkState(
    val phase: WalkPhase = WalkPhase.READY,
    val tracking: Boolean = false, val trackingWhy: String? = null, val depth: Boolean = true, val why: String? = null,
    val distanceM: Double = 0.0, val seconds: Double = 0.0, val speed: Float = 0f,
    val cubes: Int = 0, val pieces: Int = 0, val keyFrames: Int = 0, val fps: Float = 0f, val depthSize: String = "",
    val gnss: GnssLive = GnssLive(), val gpsOn: Boolean = false, val clockNote: String = "",
    val coach: String? = null, val endedBecause: String? = null,
    val mini: Raster? = null, val camX: Float = 0f, val camZ: Float = 0f, val headingDeg: Float = 0f, val trail: FloatArray = FloatArray(0), val diag: String = "",
)

/**
 * A walk being captured. The drawing thread only copies each *new* raw depth picture (ARCore repeats one for several camera frames) and hands
 * it to a worker, where the room scanner's [ScanIntegrator] (set for a walking pace, see [WalkSettings]) adds it to the current piece of
 * the map as evidence. Every ~15 m of path the piece is closed and a new one started, so a later walk can be fitted piece by piece.
 * Every camera frame is stamped twice, with ARCore's own frame time and the elapsed-realtime clock when it arrived ([ClockSync]), because
 * ARCore does not say which clock its frame time is on. The path comes only from ARCore's motion tracking; GPS only places the finished
 * walk on the map. A walk ends by itself if tracking is lost for more than [WalkSettings.MAX_LOSS_SECONDS] or comes back somewhere impossible,
 * because after that nothing that follows can be lined up with what came before. Everything stays on this phone.
 */
class WalkScan(private val context: Context, val session: Session, private val store: WalkStore) : GLSurfaceView.Renderer {
    val state = MutableStateFlow(WalkState())
    /** The screen state is written from the drawing thread, the map worker and the UI: one change at a time, so none is lost. */
    private fun upd(f: WalkState.() -> WalkState) = synchronized(state) { state.value = state.value.f() }
    val walkId: String = UUID.randomUUID().toString()
    private val fuser = Executors.newSingleThreadExecutor()
    private val worker = Executors.newSingleThreadExecutor()
    private val fusing = AtomicBoolean(false); private val mapping = AtomicBoolean(false)
    private val clock = ClockSync()
    private val track = WalkTrack()
    private val finished = ArrayList<WalkChunk>()
    private val integrators = ArrayList<ScanIntegrator>()
    @Volatile private var currentMap: RoomMap = WalkSettings.newMap()
    @Volatile private var integrator: ScanIntegrator = WalkSettings.integrator(currentMap)
    @Volatile private var recording = false
    private var recorder: WorldSensorRecorder? = null
    private var startedAt = 0L; private var startFrameNs = -1L
    private var chunkT0 = -1L; private var chunkPath = 0.0; private var pathTotal = 0.0
    private var lastTrackNs = 0L; private var lastTx = Float.NaN; private var lastTz = Float.NaN
    private val watch = TrackingWatch()
    private var lastDepthTs = -1L; private var lastSkip: String? = null; private var lastSkipAt = 0L
    private val trail = ArrayList<Float>(); private var trailX = Float.NaN; private var trailZ = Float.NaN
    private var smooth = false
    private var speed = 0f; private var speedX = Float.NaN; private var speedZ = Float.NaN; private var speedAt = 0L

    // drawing
    private var tex = 0; private var bgProg = 0; private var ptProg = 0
    private val quad: FloatBuffer = buf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); private val uv: FloatBuffer = buf(FloatArray(8))
    @Volatile private var points: FloatBuffer? = null
    private var w = 1; private var h = 1; private var frameNo = 0
    private var fpsAt = 0L; private var fpsN = 0; private var fps = 0f; private var lastPublish = 0L; private var lastMap = 0L
    private var pitchDeg = 0f

    init {
        val c = Config(session)
        c.depthMode = when { session.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> Config.DepthMode.AUTOMATIC; session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> Config.DepthMode.RAW_DEPTH_ONLY; else -> Config.DepthMode.DISABLED }
        c.focusMode = Config.FocusMode.AUTO; c.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        c.planeFindingMode = Config.PlaneFindingMode.DISABLED; c.lightEstimationMode = Config.LightEstimationMode.DISABLED
        session.configure(c)
        smooth = c.depthMode == Config.DepthMode.AUTOMATIC
        state.value = state.value.copy(depth = c.depthMode != Config.DepthMode.DISABLED, why = if (c.depthMode == Config.DepthMode.DISABLED) "This phone's ARCore gives no depth, so a walk can't be mapped on it (the path and GPS can still be recorded)." else null)
    }

    /** Starts recording. [gps] true when location permission is held: the sensor and GPS log is written too. Returns a reason it could not start, or null. */
    fun start(gps: Boolean): String? {
        if (recording) return null
        if (!store.hasRoomToStart()) return "Not enough free storage to record a walk (needs about 300 MB free)."
        if (gps) {
            try { recorder = WorldSensorRecorder(context).also { it.start(store.incomingLog(walkId)) } }
            catch (e: Exception) { recorder = null; return "Couldn't start the sensors: ${e.message ?: e.javaClass.simpleName}" }
        }
        startedAt = System.currentTimeMillis(); recording = true
        upd { copy(phase = WalkPhase.RECORDING, gpsOn = recorder != null) }
        recorder?.note("start", "walk $walkId")
        return null
    }

    /** Stops recording and gives the walk, ready to store; null when nothing worth keeping was captured. Blocks while the last depth pictures are added: call it off the main thread. */
    fun finish(): WalkInput? {
        if (!recording && state.value.phase == WalkPhase.ENDED && finalInput != null) return finalInput
        recording = false
        val summary = recorder?.stop(); val fixes = recorder?.fixes() ?: emptyList()
        fuser.shutdown(); runCatching { fuser.awaitTermination(15, TimeUnit.SECONDS) }
        val ts = synchronized(track) { track.points.lastOrNull()?.tNs ?: 0L }
        synchronized(finished) { if (currentMap.solid > 50) { finished += WalkChunk(currentMap, chunkT0.coerceAtLeast(0L), ts); integrators += integrator } }
        val pieces = synchronized(finished) { finished.toList() }
        val diag = integrators.joinToString(" · ") { it.counters.summary() }.take(600) + " · " + clock.summary()
        val frames = integrators.sumOf { it.counters.integrated }
        val input = synchronized(track) {
            if (track.points.size < 10 || pieces.isEmpty()) null
            else WalkInput(walkId, defaultName(startedAt), startedAt, System.currentTimeMillis(), track, pieces, clock, fixes, summary, summary?.file, frames, diag)
        }
        finalInput = input
        upd { copy(phase = WalkPhase.ENDED) }
        return input
    }
    @Volatile private var finalInput: WalkInput? = null

    /** Throws the walk away (and its sensor log). */
    fun discard() { recording = false; recorder?.stop()?.file?.delete(); fuser.shutdownNow(); worker.shutdownNow(); finalInput = null }
    fun close() { recording = false; recorder?.stop(); fuser.shutdown(); worker.shutdown() }

    private fun defaultName(at: Long) = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))

    /** Ends the walk now, for [why], and stops the sensors and GPS at once (not when the screen gets round to saving). */
    private fun end(why: String) { if (!recording) return; recording = false; recorder?.note("end", why); recorder?.stop(); upd { copy(phase = WalkPhase.ENDED, endedBecause = why) } }

    /** World Scan only collects in the foreground: if the app is sent away mid-walk, the walk is stopped (and then saved). */
    fun endForBackground() = end("The app was sent to the background, so the walk was stopped and saved. World Scan only records while it is on screen.")

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        session.setCameraTextureName(tex)
        bgProg = program("attribute vec4 a_P; attribute vec2 a_T; varying vec2 v; void main(){ gl_Position = a_P; v = a_T; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES s; varying vec2 v; void main(){ gl_FragColor = texture2D(s, v); }")
        // Surfaces already mapped, coloured by height above the lowest ground seen (a_P.w).
        ptProg = program("uniform mat4 u_M; attribute vec4 a_P; varying float hgt; void main(){ gl_Position = u_M * vec4(a_P.xyz, 1.0); gl_PointSize = clamp(14.0 / gl_Position.w, 2.0, 8.0); hgt = a_P.w; }",
            "precision mediump float; varying float hgt; void main(){ float t = clamp(hgt / 4.0, 0.0, 1.0); vec3 c = mix(vec3(0.15, 0.85, 0.85), mix(vec3(0.3, 0.95, 0.5), vec3(1.0, 0.8, 0.35), t), smoothstep(0.0, 0.5, t)); gl_FragColor = vec4(c, 0.8); }")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width; h = height; GLES20.glViewport(0, 0, width, height)
        @Suppress("DEPRECATION") val rot = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        session.setDisplayGeometry(rot, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val frame = runCatching { session.update() }.getOrNull() ?: return
        val recv = SystemClock.elapsedRealtimeNanos(); val now = System.currentTimeMillis()
        fpsN++; if (now - fpsAt >= 1000) { fps = fpsN * 1000f / (now - fpsAt).coerceAtLeast(1); fpsN = 0; fpsAt = now }
        if (frame.hasDisplayGeometryChanged() || frameNo == 0) { quad.position(0); uv.position(0); frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, uv) }
        frameNo++
        drawBackground()
        val cam = frame.camera; val ts = frame.timestamp
        val tracking = cam.trackingState == TrackingState.TRACKING
        val pose = cam.pose; val px = pose.tx(); val py = pose.ty(); val pz = pose.tz()
        var depthSize = state.value.depthSize
        if (tracking) {
            val view = FloatArray(16); val proj = FloatArray(16); cam.getViewMatrix(view, 0); cam.getProjectionMatrix(proj, 0, 0.1f, 40f)
            val m = FloatArray(16); pose.toMatrix(m, 0)
            pitchDeg = Math.toDegrees(Math.asin((-m[9]).toDouble().coerceIn(-1.0, 1.0))).toFloat()
            if (recording) {
                integrator.noteCamera(m)
                if (state.value.depth) runCatching { depthStep(frame, m)?.let { depthSize = it } }
            }
            drawPoints(view, proj)
        }
        if (recording) {
            clock.add(ts, recv)
            if (startFrameNs < 0) startFrameNs = ts
            val tq = if (tracking) TrackingQuality.TRACKING else TrackingQuality.NOT_TRACKING
            val verdict = watch.update(ts, tracking, px, pz)
            if (watch.lostJustNow) recorder?.note("tracking-lost", cam.trackingFailureReason.name)
            if (verdict != null) end(verdict + if (!tracking) " (${friendly(cam.trackingFailureReason.name)})" else "")
            if (tracking) {
                if (!lastTx.isNaN()) { val d = hypot((px - lastTx).toDouble(), (pz - lastTz).toDouble()); if (d < 3.0) { pathTotal += d; chunkPath += d } }
                lastTx = px; lastTz = pz
                if (chunkT0 < 0) chunkT0 = ts
                if (chunkPath >= WalkSettings.PIECE_LENGTH_M || (ts - chunkT0) / 1e9 >= WalkSettings.PIECE_MAX_SECONDS) closePiece(ts)
                if (trailX.isNaN() || hypot((px - trailX).toDouble(), (pz - trailZ).toDouble()) >= 0.5) { synchronized(trail) { trail.add(px); trail.add(pz) }; trailX = px; trailZ = pz }
                if (speedX.isNaN()) { speedX = px; speedZ = pz; speedAt = now } else if (now - speedAt >= 1000) { speed = (hypot((px - speedX).toDouble(), (pz - speedZ).toDouble()) / ((now - speedAt) / 1000.0)).toFloat(); speedX = px; speedZ = pz; speedAt = now }
            }
            if (ts - lastTrackNs >= 100_000_000L || !tracking) {
                lastTrackNs = ts
                synchronized(track) { track.add(PosePoint(ts, px, py, pz, pose.qx(), pose.qy(), pose.qz(), pose.qw(), tq)) }
            }
            recorder?.recordPose(ts, recv, px, py, pz, pose.qx(), pose.qy(), pose.qz(), pose.qw(), tq)
        }
        if (now - lastMap > 900 && mapping.compareAndSet(false, true)) {
            lastMap = now
            val cx = px; val cz = pz; val maps = synchronized(finished) { finished.map { it.map } } + currentMap; val cur = currentMap; val fin = synchronized(finished) { finished.toList() }
            try { worker.execute {
                runCatching {
                    val r = TopDown.render(maps, cell = 0.4f, window = floatArrayOf(cx, cz, 38f), guard = true)
                    val near = ArrayList<Float>(); val ground = r?.groundY ?: -1.4f
                    synchronized(cur) { cur.forEachSolid { x, y, z -> if (near.size < 4 * 22000 && Math.abs(x - cx) < 9f && Math.abs(z - cz) < 9f) { near.add(x); near.add(y); near.add(z); near.add(y - ground) } } }
                    val fb = ByteBuffer.allocateDirect(near.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(); near.forEach { fb.put(it) }; fb.position(0); points = fb
                    val total = fin.sumOf { c -> synchronized(c.map) { c.map.solid } } + synchronized(cur) { cur.solid }
                    upd { copy(mini = r, cubes = total) }
                }
                mapping.set(false)
            } } catch (e: java.util.concurrent.RejectedExecutionException) { mapping.set(false) }
        }
        if (now - lastPublish > 100) {
            lastPublish = now; recorder?.refreshAge()
            val skip = lastSkip.takeIf { now - lastSkipAt < 900 }
            val g = recorder?.gnss?.value ?: GnssLive(enabled = false)
            val secs = if (startFrameNs >= 0) (ts - startFrameNs) / 1e9 else 0.0
            val coach = when {
                state.value.phase == WalkPhase.ENDED -> null
                !state.value.depth -> null
                !tracking -> "Looking for features… point at buildings and textured surfaces, and hold the phone steady" + (cam.trackingFailureReason.takeIf { it.name != "NONE" }?.let { " (${friendly(it.name)})" } ?: "")
                skip == "fast" -> "Slow down: depth blurs when the phone moves or turns fast"
                pitchDeg < -50f -> "Look ahead: the pavement doesn't help the map, buildings and walls do"
                pitchDeg > 55f -> "Look ahead and a little down: the sky has no depth"
                recording && g.enabled && secs > 20 && (g.accuracyM == null || g.accuracyM > 25f) -> "GPS is weak here: the walk will be kept, but may not be placeable on the map"
                else -> null
            }
            val trailArr = synchronized(trail) { trail.toFloatArray() }
            val speedNow = speed; val fpsNow = fps
            upd { copy(tracking = tracking, trackingWhy = if (tracking) null else cam.trackingFailureReason.name, distanceM = pathTotal, seconds = secs, speed = speedNow,
                pieces = synchronized(finished) { finished.size } + (if (recording) 1 else 0), keyFrames = integrators.sumOf { it.counters.integrated } + integrator.counters.integrated, fps = fpsNow, depthSize = depthSize,
                gnss = g, clockNote = clock.summary(), coach = coach, camX = px, camZ = pz, headingDeg = PosePoint(ts, px, py, pz, pose.qx(), pose.qy(), pose.qz(), pose.qw(), TrackingQuality.TRACKING).headingDeg(), trail = trailArr,
                diag = integrator.counters.summary()) }
        }
    }

    private fun friendly(name: String) = when (name) { "INSUFFICIENT_LIGHT" -> "too dark"; "EXCESSIVE_MOTION" -> "moving too fast"; "INSUFFICIENT_FEATURES" -> "not enough to see"; "CAMERA_UNAVAILABLE" -> "camera in use"; "BAD_STATE" -> "ARCore state"; else -> name.lowercase().replace('_', ' ') }

    /** Ends the current piece and starts the next. The old piece's map keeps receiving the few depth pictures already queued for it. */
    private fun closePiece(ts: Long) {
        synchronized(finished) { finished += WalkChunk(currentMap, chunkT0, ts); integrators += integrator }
        currentMap = WalkSettings.newMap(); integrator = WalkSettings.integrator(currentMap); chunkT0 = ts; chunkPath = 0.0
        recorder?.note("piece", "${finished.size}")
    }

    /** Copies a *new* raw depth picture and queues it. Returns the depth picture's size. */
    private fun depthStep(frame: com.google.ar.core.Frame, pose: FloatArray): String? {
        val depth = try { frame.acquireRawDepthImage16Bits() } catch (e: NotYetAvailableException) { return null }
        val conf = try { frame.acquireRawDepthConfidenceImage() } catch (e: NotYetAvailableException) { depth.close(); return null }
        try {
            val dw = depth.width; val dh = depth.height
            if (depth.timestamp != lastDepthTs && fusing.compareAndSet(false, true)) {
                lastDepthTs = depth.timestamp
                val db = depth.planes[0].buffer.order(ByteOrder.nativeOrder()); val dStride = depth.planes[0].rowStride
                val cb = conf.planes[0].buffer; val cStride = conf.planes[0].rowStride
                val intr = frame.camera.textureIntrinsics; val dims = intr.imageDimensions
                val fx = intr.focalLength[0] * dw / dims[0]; val fy = intr.focalLength[1] * dh / dims[1]
                val cx = intr.principalPoint[0] * dw / dims[0]; val cy = intr.principalPoint[1] * dh / dims[1]
                val mm = ShortArray(dw * dh); val cf = ByteArray(dw * dh)
                for (y in 0 until dh) { for (x in 0 until dw) mm[y * dw + x] = db.getShort(y * dStride + x * 2); cb.position(y * cStride); cb.get(cf, y * dw, dw) }
                val f = DepthFrame(dw, dh, mm, cf, fx, fy, cx, cy, pose, depth.timestamp, null); val it = integrator
                try { fuser.execute { runCatching { it.integrate(f)?.let { r -> lastSkip = r; lastSkipAt = System.currentTimeMillis() } }; fusing.set(false) } } catch (e: Exception) { fusing.set(false) }
            }
            return "${dw}×$dh"
        } finally { depth.close(); conf.close() }
    }

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProg); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        val p = GLES20.glGetAttribLocation(bgProg, "a_P"); val t = GLES20.glGetAttribLocation(bgProg, "a_T")
        quad.position(0); uv.position(0)
        GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 0, quad); GLES20.glVertexAttribPointer(t, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(t)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(t)
        GLES20.glDepthMask(true)
    }

    private fun drawPoints(view: FloatArray, proj: FloatArray) {
        val fb = (points ?: return).duplicate(); val n = fb.capacity() / 4; if (n == 0) return
        val mvp = FloatArray(16); android.opengl.Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(ptProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ptProg, "u_M"), 1, false, mvp, 0)
        val a = GLES20.glGetAttribLocation(ptProg, "a_P"); fb.position(0)
        GLES20.glVertexAttribPointer(a, 4, GLES20.GL_FLOAT, false, 0, fb); GLES20.glEnableVertexAttribArray(a)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, n)
        GLES20.glDisableVertexAttribArray(a); GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also { GLES20.glAttachShader(it, sh(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(it, sh(GLES20.GL_FRAGMENT_SHADER, fs)); GLES20.glLinkProgram(it) }
    }

    private companion object { fun buf(a: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) } }

}
