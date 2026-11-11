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
    val centreM: Float? = null, val solid: Int = 0, val stats: RoomStats? = null,
    val objects: List<RoomObject> = emptyList(), val detector: Boolean = false,
    val view: FloatArray? = null, val proj: FloatArray? = null,
    /** Two tapped points and the straight-line distance between them. */
    val measure: List<P3> = emptyList(),
    val fps: Float = 0f, val depthSize: String = "",
)

/**
 * The live scan: ARCore tracks the phone and gives a depth picture; each depth pixel it is sure about becomes a point in the room
 * and goes into the [map]. A small on-device detector looks at a camera picture about once a second and each thing it finds is placed
 * in the room using the depth at its middle. Everything runs on this phone; no picture, depth or map leaves it.
 */
class ArScan(private val context: Context, val session: Session, val map: RoomMap = RoomMap(), val memory: ObjectMemory = ObjectMemory()) : GLSurfaceView.Renderer {
    val state = MutableStateFlow(ScanState())
    private val worker = Executors.newSingleThreadExecutor()
    private val detecting = AtomicBoolean(false)
    private val statsBusy = AtomicBoolean(false)
    @Volatile private var taps = ArrayList<Pair<Float, Float>>()
    private val measured = ArrayList<P3>()
    private var detector: ObjectDetector? = null

    private var tex = 0; private var bgProg = 0; private var ptProg = 0
    private val quad: FloatBuffer = buf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uv: FloatBuffer = buf(FloatArray(8))
    private var points: FloatBuffer? = null; private var pointCount = 0
    private var frameNo = 0; private var lastSnap = 0L; private var lastDetect = 0L; private var lastStats = 0L; private var lastPublish = 0L
    private var w = 1; private var h = 1
    private var fpsAt = 0L; private var fpsN = 0; private var fps = 0f

    init {
        val c = Config(session)
        c.depthMode = when { session.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> Config.DepthMode.AUTOMATIC; session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> Config.DepthMode.RAW_DEPTH_ONLY; else -> Config.DepthMode.DISABLED }
        c.focusMode = Config.FocusMode.AUTO; c.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE; c.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
        session.configure(c)
        state.value = state.value.copy(depth = c.depthMode != Config.DepthMode.DISABLED, why = if (c.depthMode == Config.DepthMode.DISABLED) "This phone's ARCore gives no depth, so a room can't be scanned on it." else null)
        detector = runCatching {
            ObjectDetector.createFromOptions(context, ObjectDetector.ObjectDetectorOptions.builder().setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
                .setRunningMode(RunningMode.IMAGE).setMaxResults(8).setScoreThreshold(0.45f).build())
        }.getOrNull()
        state.value = state.value.copy(detector = detector != null)
    }

    /** A tap on the picture (0..1 across and down): measured at the next frame. Two taps give a distance; a third starts again. */
    fun tap(x: Float, y: Float) { taps = ArrayList(taps).also { it += x to y } }
    fun clearMeasure() { measured.clear(); state.value = state.value.copy(measure = emptyList()) }
    fun close() { worker.shutdown(); runCatching { detector?.close() } }

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
        if (tracking) { cam.getViewMatrix(view, 0); cam.getProjectionMatrix(proj, 0, 0.1f, 30f) }
        var centre: Float? = state.value.centreM; var depthSize = state.value.depthSize
        if (tracking && state.value.depth && frameNo % 3 == 0) runCatching { integrate(frame, now)?.let { centre = it.first; depthSize = it.second } }
        if (tracking) drawPoints(view, proj, now)
        if (now - lastPublish > 100) {
            lastPublish = now
            state.value = state.value.copy(tracking = tracking, centreM = centre, solid = pointCount, view = if (tracking) view else null, proj = if (tracking) proj else null,
                objects = memory.confirmed(), measure = measured.toList(), fps = fps, depthSize = depthSize,
                why = if (!state.value.depth) state.value.why else if (!tracking) "Move the phone slowly so it can find its place" else null)
        }
        if (now - lastStats > 2500 && statsBusy.compareAndSet(false, true)) { lastStats = now; worker.execute { runCatching { val s = synchronized(map) { map.stats() }; state.value = state.value.copy(stats = s) }; statsBusy.set(false) } }
    }

    /** Adds this frame's depth to the map. Returns the distance at the middle of the screen and the depth picture's size. */
    private fun integrate(frame: Frame, now: Long): Pair<Float?, String>? {
        val depth = try { frame.acquireRawDepthImage16Bits() } catch (e: NotYetAvailableException) { return null }
        val conf = try { frame.acquireRawDepthConfidenceImage() } catch (e: NotYetAvailableException) { depth.close(); return null }
        try {
            val dw = depth.width; val dh = depth.height
            val db = depth.planes[0].buffer.order(ByteOrder.nativeOrder()); val dStride = depth.planes[0].rowStride
            val cb = conf.planes[0].buffer; val cStride = conf.planes[0].rowStride
            val intr = frame.camera.textureIntrinsics; val dims = intr.imageDimensions
            val fx = intr.focalLength[0] * dw / dims[0]; val fy = intr.focalLength[1] * dh / dims[1]
            val cx = intr.principalPoint[0] * dw / dims[0]; val cy = intr.principalPoint[1] * dh / dims[1]
            val pose = FloatArray(16); frame.camera.pose.toMatrix(pose, 0)
            fun metres(x: Int, y: Int): Float? {
                if (x !in 0 until dw || y !in 0 until dh) return null
                if ((cb.get(y * cStride + x).toInt() and 0xFF) < 110) return null
                val mm = db.getShort(y * dStride + x * 2).toInt() and 0xFFFF
                return if (mm in 250..6000) mm / 1000f else null
            }
            /** The depth near a spot: the middle value of what is trusted in a small window, so one bad pixel can't decide. */
            fun near(px: Float, py: Float, r: Int = 2): Pair<Float, P3>? {
                val vals = ArrayList<Float>(); for (yy in -r..r) for (xx in -r..r) metres(px.toInt() + xx, py.toInt() + yy)?.let { vals += it }
                if (vals.size < 3) return null
                val d = vals.sorted()[vals.size / 2]; return d to Depth.toWorld(px, py, d, fx, fy, cx, cy, pose)
            }
            synchronized(map) { for (y in 0 until dh) for (x in 0 until dw) { val d = metres(x, y) ?: continue; val p = Depth.toWorld(x.toFloat(), y.toFloat(), d, fx, fy, cx, cy, pose); map.add(p.x, p.y, p.z) } }
            /** A spot on screen (0..1) as a pixel of the depth picture: the depth picture lines up with the camera texture. */
            fun viewToDepth(vx: Float, vy: Float): Pair<Float, Float> { val o = FloatArray(2); frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, floatArrayOf(vx, vy), Coordinates2d.TEXTURE_NORMALIZED, o); return o[0] * dw to o[1] * dh }
            val centre = viewToDepth(0.5f, 0.5f).let { near(it.first, it.second) }?.first
            val pending = taps; if (pending.isNotEmpty()) { taps = ArrayList(); pending.forEach { (tx, ty) -> viewToDepth(tx, ty).let { near(it.first, it.second, 3) }?.let { if (measured.size >= 2) measured.clear(); measured += it.second } } }
            if (detector != null && now - lastDetect > 900 && detecting.compareAndSet(false, true)) { lastDetect = now; if (!detect(frame, dw, dh) { px, py -> near(px, py, 3)?.second }) detecting.set(false) }
            return centre to "${dw}×$dh"
        } finally { depth.close(); conf.close() }
    }

    /**
     * Copies this frame's camera picture and, off the drawing thread, looks for household things in it. Each find is placed using the
     * depth at its middle. The depth lookups are made now, on a coarse grid, because the depth picture is gone once this frame ends.
     */
    private fun detect(frame: Frame, dw: Int, dh: Int, depthAt: (Float, Float) -> P3?): Boolean {
        val image = try { frame.acquireCameraImage() } catch (e: Exception) { return false }
        val iw = image.width; val ih = image.height
        val rgb: IntArray
        try { rgb = Yuv.toArgb(image) } finally { image.close() }
        // How the camera picture sits on screen (to turn it upright) and on the depth picture (to place what is found).
        val o = FloatArray(4); frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, floatArrayOf(0f, 0f, iw.toFloat(), 0f), Coordinates2d.VIEW_NORMALIZED, o)
        val rot = Upright.rotation(o[2] - o[0], o[3] - o[1])
        // Depth-backed positions on a 16 by 12 grid over the camera picture.
        val gx = 16; val gy = 12; val grid = arrayOfNulls<P3>(gx * gy)
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
                    val (sx, sy) = Upright.toSensor(box.centerX(), box.centerY(), rot, iw, ih)
                    val i = (sx / iw * gx).toInt().coerceIn(0, gx - 1); val j = (sy / ih * gy).toInt().coerceIn(0, gy - 1)
                    grid[j * gx + i]?.let { p -> synchronized(memory) { memory.see(label, p, cat.score()) } }
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

    private fun drawPoints(view: FloatArray, proj: FloatArray, now: Long) {
        if (now - lastSnap > 700) {
            lastSnap = now
            val pts = synchronized(map) { map.points() }.let { if (it.size > MAX_DRAWN) it.filterIndexed { i, _ -> i % (it.size / MAX_DRAWN + 1) == 0 } else it }
            val fb = ByteBuffer.allocateDirect(pts.size * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
            pts.forEach { fb.put(it.x); fb.put(it.y); fb.put(it.z) }; fb.position(0); points = fb
            pointCount = synchronized(map) { map.solid }
        }
        val fb = points ?: return; val n = fb.capacity() / 3; if (n == 0) return
        val mvp = FloatArray(16); android.opengl.Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(ptProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ptProg, "u_M"), 1, false, mvp, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "u_F"), state.value.stats?.floorY ?: 0f)
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
