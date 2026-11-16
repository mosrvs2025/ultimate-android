package app.ultimate.mesh

import android.content.Context
import app.ultimate.mesh.LiveRtc.applyLocal
import app.ultimate.mesh.LiveRtc.applyRemote
import app.ultimate.mesh.LiveRtc.awaitGathered
import app.ultimate.mesh.LiveRtc.makeOffer
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Watching and listening to another of the owner's devices live. Sends an offer over the mesh (in pieces), fetches the answer,
 * connects, then plays the sound by itself and turns the video into pictures (about twelve a second) for the camera window and the
 * Cameras page. Setting up blocks, so it runs on a background thread, and [start] throws with the reason if the call can't be made.
 */
class LiveViewer(private val context: Context, private val node: MeshNode, private val peerId: String, private val stun: Boolean, private val onPicture: (ByteArray) -> Unit) {
    @Volatile private var pc: PeerConnection? = null
    @Volatile private var state = PeerConnection.IceConnectionState.NEW
    @Volatile private var lastPicture = 0L
    private val sid = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    private val sink = VideoSink { frame ->
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPicture >= 80) { lastPicture = now; LiveRtc.jpeg(frame)?.let(onPicture) }
    }

    fun start() {
        val connected = CompletableFuture<Unit>()
        val observer = object : LiveRtc.Observer() {
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                state = s ?: return
                if (s == PeerConnection.IceConnectionState.CONNECTED || s == PeerConnection.IceConnectionState.COMPLETED) connected.complete(Unit)
                if (s == PeerConnection.IceConnectionState.FAILED) connected.completeExceptionally(IllegalStateException("the devices could not reach each other"))
            }
            override fun onTrack(transceiver: RtpTransceiver?) { (transceiver?.receiver?.track() as? VideoTrack)?.addSink(sink) }
        }
        val p = LiveRtc.factory(context).createPeerConnection(LiveRtc.config(stun), observer) ?: throw IllegalStateException("could not start a call")
        pc = p
        val recvOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnly)
        p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnly)
        p.applyLocal(p.makeOffer())
        observer.awaitGathered()
        val parts = RtcSignal.split(p.localDescription.description)
        for (i in parts.indices) node.command(peerId, Cmd.RTC_OFFER, RtcSignal.piece(sid, parts, i))
        // The answer, a piece at a time, once the other device has made it.
        val asm = RtcSignal.Assembler(); var answer: String? = null; var index = 0
        val deadline = System.currentTimeMillis() + 20_000
        while (answer == null) {
            if (System.currentTimeMillis() > deadline) throw IllegalStateException("no answer from the other device")
            val r = node.command(peerId, Cmd.RTC_ANSWER, mapOf("sid" to sid, "part" to index.toString()))
            when {
                r["fail"] != null -> throw IllegalStateException(r["fail"])
                r["wait"] != null -> Thread.sleep(300)
                else -> { answer = asm.add(r); index++ }
            }
        }
        p.applyRemote(SessionDescription(SessionDescription.Type.ANSWER, answer!!))
        connected.get(20, TimeUnit.SECONDS)
    }

    /** Whether the call is still connected. */
    fun alive() = state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED

    fun stop() {
        runCatching { node.command(peerId, Cmd.RTC_BYE) }
        pc?.let { runCatching { it.close() }; runCatching { it.dispose() } }
        pc = null
    }
}
