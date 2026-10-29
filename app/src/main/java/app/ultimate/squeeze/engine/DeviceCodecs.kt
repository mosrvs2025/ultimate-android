package app.ultimate.squeeze.engine

import android.media.MediaCodecList
import app.ultimate.squeeze.model.Capabilities
import app.ultimate.squeeze.model.Codec

object DeviceCodecs {
    /** Only hardware encoders count: software HEVC/AV1 would be too slow and hot to be a sensible default. */
    fun detect(): Capabilities {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.isHardwareAccelerated }
        fun has(codec: Codec) = encoders.any { info -> info.supportedTypes.any { it.equals(codec.mime, ignoreCase = true) } }
        return Capabilities(hevc = has(Codec.HEVC), av1 = has(Codec.AV1))
    }
}
