package app.ultimate.mesh

import android.content.Context
import app.ultimate.mesh.LiveRtc.applyLocal
import app.ultimate.mesh.LiveRtc.applyRemote
import app.ultimate.mesh.LiveRtc.awaitGathered
import app.ultimate.mesh.LiveRtc.makeAnswer
import org.webrtc.AudioTrack
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * The camera device's side of live video: one call per viewer, each sending the camera (and the microphone, if allowed) to that
 * viewer. A viewer's offer arrives in pieces over the mesh ([RtcSignal]); this makes the answer, waits for its addresses, and
 * hands it back for the viewer to fetch. A call ends when the viewer says goodbye, when its connection drops, or when setting it up
 * stalls. Everything here is blocking and runs on its own thread.
 */
object CameraRtc {
    val calls = RtcSignal.Calls()
    @Volatile var video: VideoTrack? = null
    @Volatile var audio: AudioTrack? = null
    private val connections = ConcurrentHashMap<String, PeerConnection>()

    /** Starts answering [offer] from [viewerId] on a new thread; the outcome is recorded in [calls]. */
    fun accept(context: Context, viewerId: String, viewerName: String, offer: String, stun: Boolean) {
        val v = video ?: run { calls.failed(viewerId, "the camera is not ready yet"); return }
        Thread {
            try {
                close(viewerId, keepCall = true)
                val factory = LiveRtc.factory(context)
                val observer = object : LiveRtc.Observer() {
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED || state == PeerConnection.IceConnectionState.DISCONNECTED) {
                            Thread { close(viewerId) }.start()
                        }
                    }
                }
                val pc = factory.createPeerConnection(LiveRtc.config(stun), observer) ?: throw IllegalStateException("could not start a call")
                connections[viewerId] = pc
                pc.addTrack(v, listOf("ultimate"))
                audio?.let { pc.addTrack(it, listOf("ultimate")) }
                pc.applyRemote(SessionDescription(SessionDescription.Type.OFFER, offer))
                pc.applyLocal(pc.makeAnswer())
                observer.awaitGathered()
                calls.answered(viewerId, pc.localDescription.description)
                CameraNode.noteViewer(viewerName)
            } catch (e: Exception) {
                calls.failed(viewerId, e.message ?: "the call could not be set up")
                close(viewerId, keepCall = true)
            }
        }.start()
    }

    /** Ends [viewerId]'s call (and forgets it unless [keepCall], used while a failure is still to be read). */
    fun close(viewerId: String, keepCall: Boolean = false) {
        connections.remove(viewerId)?.let { runCatching { it.close() }; runCatching { it.dispose() } }
        if (!keepCall) calls.bye(viewerId)
    }

    /** Ends every call: the camera is being switched off. */
    fun closeAll() { connections.keys.toList().forEach { close(it) }; calls.active().forEach { calls.bye(it) } }

    /** Ends calls that never finished setting up. */
    fun sweep() { calls.expired().forEach { close(it) } }
}
