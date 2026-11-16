package app.ultimate.mesh

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Shared WebRTC pieces: the one factory, call settings, small blocking helpers around its callbacks, and turning frames into pictures. */
object LiveRtc {
    val egl: EglBase by lazy { EglBase.create() }
    @Volatile private var factory: PeerConnectionFactory? = null

    @Synchronized fun factory(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory().also { factory = it }
    }

    private val STUN = listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")

    fun config(stun: Boolean) = PeerConnection.RTCConfiguration(if (stun) STUN.map { PeerConnection.IceServer.builder(it).createIceServer() } else emptyList()).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
    }

    /** Callbacks of a call, with the ones nobody needs left empty. [gathered] completes when this side has found all its addresses. */
    open class Observer : PeerConnection.Observer {
        val gathered = CompletableFuture<Unit>()
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) { if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit) }
        override fun onIceCandidate(candidate: IceCandidate?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: org.webrtc.MediaStream?) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onTrack(transceiver: RtpTransceiver?) {}
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    private const val STEP_S = 10L

    fun PeerConnection.makeOffer(): SessionDescription {
        val f = CompletableFuture<SessionDescription>()
        createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) { f.complete(d) }
            override fun onCreateFailure(error: String?) { f.completeExceptionally(IllegalStateException(error ?: "offer failed")) }
        }, MediaConstraints())
        return f.get(STEP_S, TimeUnit.SECONDS)
    }

    fun PeerConnection.makeAnswer(): SessionDescription {
        val f = CompletableFuture<SessionDescription>()
        createAnswer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) { f.complete(d) }
            override fun onCreateFailure(error: String?) { f.completeExceptionally(IllegalStateException(error ?: "answer failed")) }
        }, MediaConstraints())
        return f.get(STEP_S, TimeUnit.SECONDS)
    }

    fun PeerConnection.applyLocal(d: SessionDescription) = apply(d) { setLocalDescription(it, d) }
    fun PeerConnection.applyRemote(d: SessionDescription) = apply(d) { setRemoteDescription(it, d) }
    private fun apply(d: SessionDescription, call: (SdpObserver) -> Unit) {
        val f = CompletableFuture<Unit>()
        call(object : Sdp() {
            override fun onSetSuccess() { f.complete(Unit) }
            override fun onSetFailure(error: String?) { f.completeExceptionally(IllegalStateException(error ?: "could not use the description")) }
        })
        f.get(STEP_S, TimeUnit.SECONDS)
    }

    /** Waits (up to [seconds]) for the device to find its addresses; a call can start with whatever it has found by then. */
    fun Observer.awaitGathered(seconds: Long = 4) { runCatching { gathered.get(seconds, TimeUnit.SECONDS) } }

    /** A video frame as a JPEG picture [width] pixels wide, or null if it can't be read. Used for the still-picture fallback and for the Cameras page. */
    fun jpeg(frame: VideoFrame, width: Int = 480, quality: Int = 55): ByteArray? {
        val src = frame.buffer.toI420() ?: return null
        var scaled: VideoFrame.I420Buffer? = null
        try {
            val w = src.width; val h = src.height
            val tw = minOf(width, w) and 1.inv(); val th = ((tw.toLong() * h / w).toInt()) and 1.inv()
            if (tw < 2 || th < 2) return null
            val b = if (tw == w && th == h) src else (src.cropAndScale(0, 0, w, h, tw, th).toI420() ?: return null).also { scaled = it }
            val nv = ByteArray(tw * th * 3 / 2)
            val y = b.dataY; val u = b.dataU; val v = b.dataV
            for (row in 0 until th) { y.position(row * b.strideY); y.get(nv, row * tw, tw) }
            var at = tw * th
            for (row in 0 until th / 2) for (col in 0 until tw / 2) { nv[at++] = v.get(row * b.strideV + col); nv[at++] = u.get(row * b.strideU + col) }
            val out = ByteArrayOutputStream()
            YuvImage(nv, ImageFormat.NV21, tw, th, null).compressToJpeg(Rect(0, 0, tw, th), quality, out)
            val bytes = out.toByteArray()
            if (frame.rotation == 0) return bytes
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            val turned = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(frame.rotation.toFloat()) }, true)
            val o2 = ByteArrayOutputStream(); turned.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, o2)
            bmp.recycle(); if (turned !== bmp) turned.recycle()
            return o2.toByteArray()
        } finally { scaled?.release(); src.release() }
    }
}
