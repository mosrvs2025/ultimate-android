package app.ultimate.audio.engine

import android.media.MediaCodecList
import app.ultimate.audio.model.AudioSource

/**
 * Whether this phone can make Opus files. Android has had an Opus decoder for years, but the encoder (and the Ogg file writer
 * that goes with it) only arrived in Android 10, and a maker can leave it out, so the choice is only offered where it exists and
 * everything else falls back to a small MP3.
 */
object OpusSupport {
    val available: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { c -> c.isEncoder && c.supportedTypes.any { it.equals(AudioSource.OPUS, ignoreCase = true) } }
        }.getOrDefault(false)
    }
}
