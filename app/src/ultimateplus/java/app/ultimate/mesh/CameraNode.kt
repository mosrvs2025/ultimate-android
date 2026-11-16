package app.ultimate.mesh

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * This device's camera, shared with the owner's other devices through the mesh, only while the owner has switched it on here.
 * Switching on starts [CameraNodeService], which keeps a notification up for as long as the camera is shared (and says who is
 * looking), so the camera is never open without it showing. It starts only from this phone's own screen, as Android requires,
 * and is not restarted by itself after the phone restarts: the owner switches it on again.
 */
object CameraNode {
    /** The newest picture, for whoever asks (see [CamFrames]). */
    val latest = CamFrames.Latest()

    private val _armed = MutableStateFlow(false)
    /** Whether the camera is being shared right now. */
    val armed: StateFlow<Boolean> = _armed

    @Volatile var viewer: String? = null; private set
    @Volatile private var viewerAt = 0L

    fun setArmed(on: Boolean) { _armed.value = on; if (!on) { latest.clear(); viewer = null; viewerAt = 0L } }

    /** Someone asked for a picture just now. */
    fun noteViewer(name: String) { viewer = name; viewerAt = System.currentTimeMillis() }

    /** Whether anyone has asked for a picture lately, so the camera can work hard only while it is being watched. */
    fun watched(now: Long = System.currentTimeMillis()) = now - viewerAt < 6_000

    /** Starts sharing. Must be called while this app is on screen. Returns false if Android refused. */
    fun arm(context: Context): Boolean = runCatching { ContextCompat.startForegroundService(context, Intent(context, CameraNodeService::class.java)) }.isSuccess

    fun disarm(context: Context) { runCatching { context.stopService(Intent(context, CameraNodeService::class.java)) }; setArmed(false) }
}
