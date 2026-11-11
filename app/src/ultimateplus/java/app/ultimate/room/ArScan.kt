package app.ultimate.room

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.WindowManager
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** What the scan screen shows, refreshed a few times a second. */
data class ScanState(
    val tracking: Boolean = false, val depth: Boolean = true, val why: String? = null,
    /** Distance to the centre dot; [centreApprox] when it comes from ARCore's filled-in depth rather than a confident raw reading. */
    val centreM: Float? = null, val centreApprox: Boolean = false, val solid: Int = 0, val stats: RoomStats? = null,
    val objects: List<RoomObject> = emptyList(), val detector: Boolean = false,
    val view: FloatArray? = null, val proj: FloatArray? = null,
    /** Two tapped points and the straight-line distance between them. */
    val measure: List<P3> = emptyList(),
    val fps: Float = 0f, val depthSize: String = "", val keyFrames: Int = 0, val diag: String = "", val coach: String? = null,
)

/**
 * The live scan. The drawing thread only copies each *new* depth picture (ARCore repeats one for several camera frames) and hands it
 * to a worker, where [ScanIntegrator] decides whether to trust it and adds it to the [map] as evidence. Measuring, cleaning the points
 * for display and recognising things also run off the drawing thread. Everything runs on this phone; no picture, depth or map leaves it.
 */
class ArScan(private val context: Context, val session: Session, val map: RoomMap = RoomMap(), val memory: ObjectMemory = ObjectMemory()) : GLSurfaceView.Renderer {
    val state = MutableStateFlow(ScanState())
    @Volatile var integrator = ScanIntegrator(map); private set
    private val worker = Executors.newSingleThreadExecutor()
    private val fuser = Executors.newSingleThreadExecutor()
    private val detecting = AtomicBoolean(false)
    private val statsBusy = AtomicBoolean(false)
    private val fusing = AtomicBoolean(false)
    @Volatile private var taps = ArrayList<Pair<Float, Float>>()
    private val measured = ArrayList<P3>()
    private var detector: ObjectDetector? = null
    @Volatile private var planes: List<PlaneHint> = emptyList()
    @Volatile private var lastSkip: String? = null; @Volatile private var lastSkipAt = 0L

    private var tex = 0; private var bgProg = 0; private var ptProg = 0
    private val quad: FloatBuffer = buf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uv: FloatBuffer = buf(FloatArray(8))
    @Volatile private var points: FloatBuffer? = null; @Volatile private var pointCount = 0
    private var frameNo = 0; private var lastDetect = 0L; private var lastStats = 0L; private var lastPublish = 0L; private var lastPlanes = 0L
    private var lastDepthTs = -1L
    private var smooth = false
    private var w = 1; private var h = 1
    private var fpsAt = 0L; private var fpsN = 0; private var fps = 0f

    init {
        val c = Config(session)
        c.depthMode = when { session.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> Config.DepthMode.AUTOMATIC; session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> Config.DepthMode.RAW_DEPTH_ONLY; else -> Config.DepthMode.DISABLED }
        c.focusMode = Config.FocusMode.AUTO; c.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE; c.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
        session.configure(c)
        smooth = c.depthMode == Config.DepthMode.AUTOMATIC
        state.value = state.value.copy(depth = c.depthMode != Config.DepthMode.DISABLED, why = if (c.depthMode == Config.DepthMode.DISABLED) "This phone's ARCore gives no depth, so a room can't be scanned on it." else null)
        detector = runCatching {
            ObjectDetector.createFromOptions(context, ObjectDetector.ObjectDetectorOptions.builder().setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
                .setRunningMode(RunningMode.IMAGE).setMaxResults(8).setScoreThreshold(0.5f).build())
        }.getOrNull()
        state.value = state.value.copy(detector = detector != null)
    }

    /** A tap on the picture (0..1 across and down): measured at the next frame. Two taps give a distance; a third starts again. */
    fun tap(x: Float, y: Float) { taps = ArrayList(taps).also { it += x to y } }
    fun clearMeasure() { measured.clear(); state.value = state.value.copy(measure = emptyList()) }
    fun reset() { synchronized(map) { map.clear(); integrator = ScanIntegrator(map) }; synchronized(memory) { memory.clear() }; measured.clear(); points = null; pointCount = 0; state.value = state.value.copy(stats = null, solid = 0, objects = emptyList(), measure = emptyList(), keyFrames = 0) }
    fun close() { worker.shutdown(); fuser.shutdown(); runCatching { detector?.close() } }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        session.setCameraTextureName(tex)
        bgProg = program("attribute vec4 a_P; attribute vec2 a_T; varying vec2 v; void main(){ gl_Position = a_P; v = a_T; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES s; varying vec2 v; void main(){ gl_FragColor = texture2D(s, v); }")
        ptProg = program("uniform mat4 u_M; uniform float u_F; attribute vec3 a_P; varying float hgt; void main(){ gl_Position = u_M * vec4(a_P, 1.0); gl_PointSize = clamp(16.0 / gl_Position.w, 2.0, 9.0); hgt = a_P.y - u_F; }",
            "precision mediump float; varying float hgt; void main(){ float t = clamp(hgt / 2.5, 0.0, 1.0); gl_FragColor = vec4(mix(vec3(0.1, 0.95, 0.6), vec3(0.45, 0.5, 1.0), t), 0.8); }")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width; h = height; GLES20.glViewport(0, 0, width, height)
        @Suppress("DEPRECATION") val rot = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        session.setDisplayGeometry(rot, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val frame = runCatching { session.update() }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        fpsN++; if (now - fpsAt >= 1000) { fps = fpsN * 1000f / (now - fpsAt).coerceAtLeast(1); fpsN = 0; fpsAt = now }
        if (frame.hasDisplayGeometryChanged() || frameNo == 0) { quad.position(0); uv.position(0); frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, uv) }
        frameNo++
        drawBackground()
        val cam = frame.camera
        val tracking = cam.trackingState == TrackingState.TRACKING
        val view = FloatArray(16); val proj = FloatArray(16)
        var centre = state.value.centreM; var approx = state.value.centreApprox; var depthSize = state.value.depthSize
        if (tracking) {
            cam.getViewMatrix(view, 0); cam.getProjectionMatrix(proj, 0, 0.1f, 30f)
            val pose = FloatArray(16); cam.pose.toMatrix(pose, 0); integrator.noteCamera(pose)
            if (state.value.depth) runCatching { depthStep(frame, pose, now)?.let { centre = it.first; approx = it.second; depthSize = it.third } }
            if (now - lastPlanes > 1000) { lastPlanes = now; planes = runCatching { session.getAllTrackables(Plane::class.java).filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }.map { PlaneHint(it.centerPose.ty(), it.extentX * it.extentZ, true) } }.getOrDefault(emptyList()) }
            drawPoints(view, proj)
        }
        if (now - lastPublish > 100) {
            lastPublish = now
            val skip = lastSkip.takeIf { now - lastSkipAt < 900 }
            state.value = state.value.copy(tracking = tracking, centreM = centre, centreApprox = approx, solid = pointCount, view = if (tracking) view else null, proj = if (tracking) proj else null,
                objects = synchronized(memory) { memory.confirmed() }, measure = measured.toList(), fps = fps, depthSize = depthSize, keyFrames = integrator.keyFrames,
                coach = if (skip == "fast") "Slow down: moving this fast blurs the depth" else null,
                why = if (!state.value.depth) state.value.why else if (!tracking) "Move the phone slowly so it can find its place" else null)
        }
        if (now - lastStats > 1200 && statsBusy.compareAndSet(false, true)) {
            lastStats = now
            worker.execute {
                runCatching {
                    val it = integrator
                    val pts = synchronized(map) { map.cleaned() }
                    val shown = if (pts.size > MAX_DRAWN) pts.filterIndexed { i, _ -> i % (pts.size / MAX_DRAWN + 1) == 0 } else pts
                    val fb = ByteBuffer.allocateDirect(shown.size * 12).order(ByteOrder.nativeOrder()).asFloatBuffer(); shown.forEach { fb.put(it.x); fb.put(it.y); fb.put(it.z) }; fb.position(0)
                    points = fb; pointCount = pts.size
                    val s = RoomGeometry.measure(pts, it.counters.camMinY.takeIf { v -> v.isFinite() }, it.counters.camMaxY.takeIf { v -> v.isFinite() }, planes)
                    state.value = state.value.copy(stats = s, diag = it.counters.summary() + " · planes ${planes.size}")
                }
                statsBusy.set(false)
            }
        }
    }

    /**
     * Reads this frame's depth. A new raw depth picture is copied and sent to be added to the map; the distance at the dot and at any
     * taps is read straight away. Returns the centre distance, whether it is approximate, and the depth picture's size.
     */
    private fun depthStep(frame: Frame, pose: FloatArray, now: Long): Triple<Float?, Boolean, String>? {
        val depth = try { frame.acquireRawDepthImage16Bits() } catch (e: NotYetAvailableException) { return null }
        val conf = try { frame.acquireRawDepthConfidenceImage() } catch (e: NotYetAvailableException) { depth.close(); return null }
        try {
            val dw = depth.width; val dh = depth.height
            val db = depth.planes[0].buffer.order(ByteOrder.nativeOrder()); val dStride = depth.planes[0].rowStride
            val cb = conf.planes[0].buffer; val cStride = conf.planes[0].rowStride
            val intr = frame.camera.textureIntrinsics; val dims = intr.imageDimensions
            val fx = intr.focalLength[0] * dw / dims[0]; val fy = intr.focalLength[1] * dh / dims[1]
            val cx = intr.principalPoint[0] * dw / dims[0]; val cy = intr.principalPoint[1] * dh / dims[1]
            // ARCore hands out the same depth picture for several camera frames: only a new one goes to the map.
            if (depth.timestamp != lastDepthTs && fusing.compareAndSet(false, true)) {
                lastDepthTs = depth.timestamp
                val mm = ShortArray(dw * dh); val cf = ByteArray(dw * dh)
                for (y in 0 until dh) { for (x in 0 until dw) mm[y * dw + x] = db.getShort(y * dStride + x * 2); cb.position(y * cStride); cb.get(cf, y * dw, dw) }
                val f = DepthFrame(dw, dh, mm, cf, fx, fy, cx, cy, pose, depth.timestamp); val it = integrator
                fuser.execute { runCatching { it.integrate(f)?.let { r -> lastSkip = r; lastSkipAt = System.currentTimeMillis() } }; fusing.set(false) }
            }
            fun metres(x: Int, y: Int): Float? {
                if (x !in 0 until dw || y !in 0 until dh) return null
                if ((cb.get(y * cStride + x).toInt() and 0xFF) < 110) return null
                val mm = db.getShort(y * dStride + x * 2).toInt() and 0xFFFF
                return if (mm in 300..6000) mm / 1000f else null
            }
            /** The depth near a spot: the middle value of what is trusted in a small window, so one bad pixel can't decide. */
            fun near(px: Float, py: Float, r: Int = 2): Pair<Float, P3>? {
                val vals = ArrayList<Float>(); for (yy in -r..r) for (xx in -r..r) metres(px.toInt() + xx, py.toInt() + yy)?.let { vals += it }
                if (vals.size < 3) return null
                val d = vals.sorted()[vals.size / 2]; return d to Depth.toWorld(px, py, d, fx, fy, cx, cy, pose)
            }
            /** A spot on screen (0..1) as a pixel of the depth picture: the depth picture lines up with the camera texture. */
            fun viewToDepth(vx: Float, vy: Float): Pair<Float, Float> { val o = FloatArray(2); frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, floatArrayOf(vx, vy), Coordinates2d.TEXTURE_NORMALIZED, o); return o[0] * dw to o[1] * dh }
            val c = viewToDepth(0.5f, 0.5f)
            var centre = near(c.first, c.second)?.first; var approx = false
            // Raw depth has holes (plain walls, shiny things). ARCore's filled-in depth covers them with an estimate: shown, but marked as one.
            if (centre == null && smooth && frameNo % 4 == 0) runCatching { frame.acquireDepthImage16Bits().use { s ->
                val x = (c.first / dw * s.width).toInt().coerceIn(0, s.width - 1); val y = (c.second / dh * s.height).toInt().coerceIn(0, s.height - 1)
                val mm = s.planes[0].buffer.order(ByteOrder.nativeOrder()).getShort(y * s.planes[0].rowStride + x * 2).toInt() and 0xFFFF
                if (mm in 300..8000) { centre = mm / 1000f; approx = true }
            } } else if (centre == null && smooth) { centre = state.value.centreM.takeIf { state.value.centreApprox }; approx = centre != null }
            val pending = taps; if (pending.isNotEmpty()) { taps = ArrayList(); pending.forEach { (tx, ty) -> viewToDepth(tx, ty).let { near(it.first, it.second, 3) }?.let { if (measured.size >= 2) measured.clear(); measured += it.second } } }
            if (detector != null && now - lastDetect > 900 && detecting.compareAndSet(false, true)) { lastDetect = now; if (!detect(frame, dw, dh, P3(pose[12], pose[13], pose[14])) { px, py -> near(px, py, 3)?.second }) detecting.set(false) }
            return Triple(centre, approx, "${dw}×$dh")
        } finally { depth.close(); conf.close() }
    }

    /**
     * Copies this frame's camera picture and, off the drawing thread, looks for household things in it. Each find is placed at the
     * middle distance of the trusted depth inside the middle of its box (not just the one pixel at its centre, which is often the wall
     * behind). The depth lookups are made now, on a coarse grid, because the depth picture is gone once this frame ends.
     */
    private fun detect(frame: Frame, dw: Int, dh: Int, camAt: P3, depthAt: (Float, Float) -> P3?): Boolean {
        val image = try { frame.acquireCameraImage() } catch (e: Exception) { return false }
        val iw = image.width; val ih = image.height
        val rgb: IntArray
        try { rgb = Yuv.toArgb(image) } finally { image.close() }
        val o = FloatArray(4); frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, floatArrayOf(0f, 0f, iw.toFloat(), 0f), Coordinates2d.VIEW_NORMALIZED, o)
        val rot = Upright.rotation(o[2] - o[0], o[3] - o[1])
        val gx = 24; val gy = 18; val grid = arrayOfNulls<P3>(gx * gy)
        val src = FloatArray(gx * gy * 2); for (j in 0 until gy) for (i in 0 until gx) { src[(j * gx + i) * 2] = (i + 0.5f) * iw / gx; src[(j * gx + i) * 2 + 1] = (j + 0.5f) * ih / gy }
        val dst = FloatArray(src.size); frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, src, Coordinates2d.TEXTURE_NORMALIZED, dst)
        for (k in 0 until gx * gy) grid[k] = depthAt(dst[k * 2] * dw, dst[k * 2 + 1] * dh)
        worker.execute {
            try {
                var bmp = Bitmap.createBitmap(rgb, iw, ih, Bitmap.Config.ARGB_8888)
                if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, iw, ih, Matrix().apply { postRotate(rot.toFloat()) }, true)
                val found = detector?.detect(BitmapImageBuilder(bmp).build())?.detections().orEmpty()
                for (d in found) {
                    val cat = d.categories().firstOrNull() ?: continue
                    val label = cat.categoryName() ?: continue
                    if (label !in RoomLabels.KEEP) continue
                    val box = d.boundingBox()
                    // The middle half of the box, back in the sensor picture, as a range of grid cells.
                    val (ax, ay) = Upright.toSensor(box.left + box.width() * 0.25f, box.top + box.height() * 0.25f, rot, iw, ih)
                    val (bx, by) = Upright.toSensor(box.right - box.width() * 0.25f, box.bottom - box.height() * 0.25f, rot, iw, ih)
                    val i0 = (minOf(ax, bx) / iw * gx).toInt().coerceIn(0, gx - 1); val i1 = (maxOf(ax, bx) / iw * gx).toInt().coerceIn(0, gx - 1)
                    val j0 = (minOf(ay, by) / ih * gy).toInt().coerceIn(0, gy - 1); val j1 = (maxOf(ay, by) / ih * gy).toInt().coerceIn(0, gy - 1)
                    val inside = ArrayList<P3>(); for (j in j0..j1) for (i in i0..i1) grid[j * gx + i]?.let { inside += it }
                    if (inside.isEmpty()) continue
                    val p = inside.sortedBy { it.dist(camAt) }[inside.size / 2]
                    synchronized(memory) { memory.see(label, p, cat.score()) }
                }
            } catch (_: Exception) { } finally { detecting.set(false) }
        }
        return true
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
        val fb = (points ?: return).duplicate(); val n = fb.capacity() / 3; if (n == 0) return
        val mvp = FloatArray(16); android.opengl.Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(ptProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ptProg, "u_M"), 1, false, mvp, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "u_F"), state.value.stats?.floorY ?: -1.3f)
        val a = GLES20.glGetAttribLocation(ptProg, "a_P"); fb.position(0)
        GLES20.glVertexAttribPointer(a, 3, GLES20.GL_FLOAT, false, 0, fb); GLES20.glEnableVertexAttribArray(a)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, n)
        GLES20.glDisableVertexAttribArray(a); GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also { GLES20.glAttachShader(it, sh(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(it, sh(GLES20.GL_FRAGMENT_SHADER, fs)); GLES20.glLinkProgram(it) }
    }

    companion object {
        const val MAX_DRAWN = 60_000
        private fun buf(a: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
    }
}

/** Camera pictures arrive as brightness plus two colour planes; this turns one into ordinary colour pixels. */
object Yuv {
    fun toArgb(image: android.media.Image): IntArray {
        val w = image.width; val h = image.height; val out = IntArray(w * h)
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val yRow = yP.rowStride; val cRow = uP.rowStride; val cPix = uP.pixelStride
        for (j in 0 until h) {
            val cj = (j shr 1) * cRow
            for (i in 0 until w) {
                val y = (yB.get(j * yRow + i).toInt() and 0xFF) - 16
                val ci = cj + (i shr 1) * cPix
                val u = (uB.get(ci).toInt() and 0xFF) - 128; val v = (vB.get(ci).toInt() and 0xFF) - 128
                val c = 1192 * maxOf(y, 0)
                val r = ((c + 1634 * v) shr 10).coerceIn(0, 255); val g = ((c - 833 * v - 400 * u) shr 10).coerceIn(0, 255); val b = ((c + 2066 * u) shr 10).coerceIn(0, 255)
                out[j * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}
