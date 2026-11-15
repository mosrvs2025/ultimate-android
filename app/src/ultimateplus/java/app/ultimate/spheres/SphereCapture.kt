package app.ultimate.spheres

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.WindowManager
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Capturing a place, one sphere at a time. While you stand on a spot and turn slowly, a frame is taken from the camera
 * whenever you have turned far enough to see something new, and placed onto the sphere by where ARCore says the camera is
 * pointing. Camera pictures are only used to build the sphere and are never kept on their own.
 */
class SphereCapture(private val context: Context, val session: Session, private val store: SphereStore, val tourId: String, name: String) : GLSurfaceView.Renderer {
    enum class Phase { Turning, Walking, Saving }

    data class State(
        val phase: Phase = Phase.Turning, val tracking: Boolean = false, val coverage: Double = 0.0, val frames: Int = 0, val stops: Int = 0,
        /** Where the biggest unfilled part is, relative to where you face: degrees right (+) or left (-), and up (+) / down (-). */
        val gapTurn: Double? = null, val gapPitch: Double? = null,
        /** On screen, where to walk to next (fractions of the screen), and how far it is in metres. */
        val nextX: Float? = null, val nextY: Float? = null, val nextMetres: Double? = null,
        val drifted: Boolean = false, val message: String? = null,
    )

    val state = MutableStateFlow(State())
    private var tour = store.get(tourId) ?: SphereTour(tourId, name, System.currentTimeMillis(), emptyList())
    @Volatile private var stitch = SphereStitch()
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var origin: FloatArray? = null
    @Volatile private var lastDir: DoubleArray? = null
    private var lastAt = 0L
    @Volatile private var nextSpot: DoubleArray? = null
    @Volatile private var phase = Phase.Turning

    // The camera background, drawn the same way as World Scan's.
    private var tex = 0; private var bgProg = 0
    private val quad: FloatBuffer = buf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); private val uv: FloatBuffer = buf(FloatArray(8))
    private var frameNo = 0

    init {
        val c = session.config
        c.focusMode = com.google.ar.core.Config.FocusMode.AUTO
        c.updateMode = com.google.ar.core.Config.UpdateMode.LATEST_CAMERA_IMAGE
        c.planeFindingMode = com.google.ar.core.Config.PlaneFindingMode.DISABLED
        c.lightEstimationMode = com.google.ar.core.Config.LightEstimationMode.DISABLED
        c.depthMode = com.google.ar.core.Config.DepthMode.DISABLED
        session.configure(c)
        state.value = State(stops = tour.stops.size)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        session.setCameraTextureName(tex)
        bgProg = program("attribute vec4 a_P; attribute vec2 a_T; varying vec2 v; void main(){ gl_Position = a_P; v = a_T; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES s; varying vec2 v; void main(){ gl_FragColor = texture2D(s, v); }")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        @Suppress("DEPRECATION") val rot = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        session.setDisplayGeometry(rot, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val frame = runCatching { session.update() }.getOrNull() ?: return
        if (frame.hasDisplayGeometryChanged() || frameNo == 0) { quad.position(0); uv.position(0); frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, uv) }
        frameNo++
        drawBackground()
        val cam = frame.camera
        if (cam.trackingState != TrackingState.TRACKING) { state.value = state.value.copy(tracking = false, message = "Move the phone a little so it can find its place"); return }
        val pose = cam.pose
        val here = floatArrayOf(pose.tx(), pose.ty(), pose.tz())
        val fwd = FloatArray(3).also { pose.getTransformedAxis(2, -1f, it, 0) } // where the camera looks, in the world
        val yawNow = atan2(fwd[0].toDouble(), -fwd[2].toDouble())

        when (phase) {
            Phase.Turning -> {
                if (origin == null) origin = here
                val o = origin!!
                val drift = hypot((here[0] - o[0]).toDouble(), (here[2] - o[2]).toDouble())
                val dir = doubleArrayOf(fwd[0].toDouble(), fwd[1].toDouble(), fwd[2].toDouble())
                val turned = lastDir?.let { acos((it[0] * dir[0] + it[1] * dir[1] + it[2] * dir[2]).coerceIn(-1.0, 1.0)) } ?: Double.MAX_VALUE
                val now = System.currentTimeMillis()
                // A new picture when you've turned about 9° and a moment has passed, unless you've wandered off the spot.
                if (drift < 0.6 && turned > Math.toRadians(9.0) && now - lastAt > 180 && !busy.get() && take(frame, pose)) { lastDir = dir; lastAt = now }
                val s = stitch
                val gap = s.biggestGap()
                val pitchNow = Math.toDegrees(kotlin.math.asin(fwd[1].toDouble().coerceIn(-1.0, 1.0)))
                state.value = state.value.copy(phase = Phase.Turning, tracking = true, coverage = s.coverage(),
                    gapTurn = gap?.let { wrap(it[0] - Math.toDegrees(yawNow)) }, gapPitch = gap?.let { it[1] - pitchNow },
                    drifted = drift >= 0.6, nextX = null, nextY = null, nextMetres = null,
                    message = when { drift >= 0.6 -> "Step back to where this sphere started: turn on the spot, don't walk"; s.coverage() < 0.05 -> "Turn slowly on the spot, all the way round"; else -> null })
            }
            Phase.Walking -> {
                val t = nextSpot ?: return
                // The next spot, on the floor about 1.4 m below the phone.
                val view = FloatArray(16); val proj = FloatArray(16); cam.getViewMatrix(view, 0); cam.getProjectionMatrix(proj, 0, 0.1f, 50f)
                val vp = FloatArray(16); android.opengl.Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
                val c = FloatArray(4); android.opengl.Matrix.multiplyMV(c, 0, vp, 0, floatArrayOf(t[0].toFloat(), here[1] - 1.4f, t[1].toFloat(), 1f), 0)
                val onScreen = c[3] > 0
                val dist = hypot(t[0] - here[0], t[1] - here[2])
                state.value = state.value.copy(phase = Phase.Walking, tracking = true,
                    nextX = if (onScreen) (c[0] / c[3] + 1) / 2 else null, nextY = if (onScreen) (1 - c[1] / c[3]) / 2 else null, nextMetres = dist,
                    message = if (dist < 0.4) "You're there. Tap Start sphere and turn slowly" else null)
            }
            Phase.Saving -> Unit
        }
    }

    /** One camera picture, shrunk to about 480 across, handed to the worker to place on the sphere. */
    private fun take(frame: Frame, pose: com.google.ar.core.Pose): Boolean {
        val img = runCatching { frame.acquireCameraImage() }.getOrNull() ?: return false
        try {
            val intr = frame.camera.imageIntrinsics; val dims = intr.imageDimensions
            val step = maxOf(1, dims[0] / 480)
            val w = img.width / step; val h = img.height / step
            val argb = yuvToArgb(img, step, w, h)
            val k = 1.0 / step
            val lens = SphereStitch.Lens(intr.focalLength[0] * k, intr.focalLength[1] * k, intr.principalPoint[0] * k, intr.principalPoint[1] * k, w, h)
            val turn = SphereStitch.Turn(pose.qx().toDouble(), pose.qy().toDouble(), pose.qz().toDouble(), pose.qw().toDouble())
            busy.set(true)
            val s = stitch
            worker.execute { try { s.add(argb, lens, turn); state.value = state.value.copy(frames = state.value.frames + 1) } finally { busy.set(false) } }
            return true
        } catch (_: Exception) { return false } finally { img.close() }
    }

    /** Ends this sphere: saves it as a picture and a stop, then shows where to stand for the next one. */
    fun finishSphere(onDone: (String?) -> Unit) {
        val o = origin ?: return onDone("Turn around a little first.")
        if (stitch.coverage() < 0.15) return onDone("Turn a bit further first: this sphere is mostly empty.")
        phase = Phase.Saving; state.value = state.value.copy(phase = Phase.Saving)
        val s = stitch; val yaw = lastDir?.let { atan2(it[0], -it[2]) } ?: 0.0
        worker.execute {
            val err = runCatching {
                val id = "s" + (tour.stops.size + 1) + "-" + System.currentTimeMillis().toString(36)
                val bmp = Bitmap.createBitmap(s.pixels(), s.width, s.height, Bitmap.Config.ARGB_8888)
                store.image(tourId, id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                bmp.recycle()
                tour = tour.copy(stops = tour.stops + SphereTour.Stop(id, o[0].toDouble(), o[1].toDouble(), o[2].toDouble(), s.coverage(), System.currentTimeMillis()))
                store.save(tour)
                nextSpot = SphereTour.nextSpot(tour.stops, o[0].toDouble(), o[2].toDouble(), yaw)
            }.exceptionOrNull()
            stitch = SphereStitch(); origin = null; lastDir = null
            phase = Phase.Walking
            state.value = state.value.copy(phase = Phase.Walking, stops = tour.stops.size, coverage = 0.0, frames = 0)
            onDone(err?.let { "Couldn't save this sphere: ${it.message}" })
        }
    }

    /** Starts the next sphere wherever you stand now. */
    fun startSphere() { stitch = SphereStitch(); origin = null; lastDir = null; phase = Phase.Turning; state.value = state.value.copy(phase = Phase.Turning, coverage = 0.0, frames = 0) }

    fun stops() = tour.stops.size
    fun close() { worker.shutdown() }

    private fun wrap(d: Double): Double { var x = d % 360; if (x > 180) x -= 360; if (x < -180) x += 360; return x }

    private fun drawBackground() {
        GLES20.glUseProgram(bgProg); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        val p = GLES20.glGetAttribLocation(bgProg, "a_P"); val t = GLES20.glGetAttribLocation(bgProg, "a_T")
        quad.position(0); uv.position(0)
        GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 0, quad); GLES20.glVertexAttribPointer(t, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(t)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(t)
    }

    companion object {
        private fun buf(a: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
        private fun program(vs: String, fs: String): Int {
            fun sh(t: Int, s: String) = GLES20.glCreateShader(t).also { GLES20.glShaderSource(it, s); GLES20.glCompileShader(it) }
            return GLES20.glCreateProgram().also { GLES20.glAttachShader(it, sh(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(it, sh(GLES20.GL_FRAGMENT_SHADER, fs)); GLES20.glLinkProgram(it) }
        }

        /** A YUV_420_888 camera image to ARGB, taking every [step]-th pixel. */
        fun yuvToArgb(img: android.media.Image, step: Int, w: Int, h: Int): IntArray {
            val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
            val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
            val out = IntArray(w * h)
            for (j in 0 until h) {
                val sy = j * step
                for (i in 0 until w) {
                    val sx = i * step
                    val yy = (yb.get(sy * y.rowStride + sx * y.pixelStride).toInt() and 0xFF) - 16
                    val uu = (ub.get((sy / 2) * u.rowStride + (sx / 2) * u.pixelStride).toInt() and 0xFF) - 128
                    val vv = (vb.get((sy / 2) * v.rowStride + (sx / 2) * v.pixelStride).toInt() and 0xFF) - 128
                    val c = 1.164f * yy
                    val r = (c + 1.596f * vv).toInt().coerceIn(0, 255); val g = (c - 0.392f * uu - 0.813f * vv).toInt().coerceIn(0, 255); val b = (c + 2.017f * uu).toInt().coerceIn(0, 255)
                    out[j * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return out
        }
    }
}
