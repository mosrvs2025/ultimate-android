package app.ultimate.studio

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer

@androidx.annotation.OptIn(UnstableApi::class)
object StudioEffects {
    fun mediaItem(c: StudioClip): MediaItem = MediaItem.Builder().setUri(c.uri)
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()).build()
    fun video(c: StudioClip, p: StudioProject, exporting: Boolean): List<Effect> = buildList {
        if (c.rotation != 0) add(ScaleAndRotateTransformation.Builder().setRotationDegrees(c.rotation.toFloat()).build())
        when (c.look) {
            Look.Natural -> Unit
            Look.Cinema -> { add(Contrast(0.18f)); add(RgbAdjustment.Builder().setRedScale(1.04f).setBlueScale(0.94f).build()) }
            Look.Warm -> add(RgbAdjustment.Builder().setRedScale(1.12f).setGreenScale(1.02f).setBlueScale(0.88f).build())
            Look.Noir -> { add(RgbFilter.createGrayscaleFilter()); add(Contrast(0.25f)) }
        }
        if (c.brightness != 0f) add(Brightness(c.brightness))
        add(Presentation.createForWidthAndHeight(p.width, p.height, if (p.fillCanvas) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT))
        if (c.title.isNotBlank()) add(OverlayEffect(ImmutableList.of<TextureOverlay>(caption(c, p))))
        if (c.captions.isNotEmpty()) add(OverlayEffect(ImmutableList.of<TextureOverlay>(SpeechCaptions(c, p))))
        if (exporting && c.speed != 1f) add(SpeedChangeEffect(c.speed))
        if (exporting) add(FrameDropEffect.createDefaultFrameDropEffect(30f))
    }
    /** The caption as a text overlay, styled and placed as chosen; preview and export share it. */
    fun caption(c: StudioClip, p: StudioProject): TextOverlay =
        TextOverlay.createStaticTextOverlay(styled(c.title, c), settings(c, p, c.titlePosition))

    fun settings(c: StudioClip, p: StudioProject, position: Int): OverlaySettings {
        val scale = p.width / 2000f * when (c.captionStyle) { CaptionStyle.Bold -> 1.6f; CaptionStyle.Pop -> 1.3f; CaptionStyle.Subtitle -> 0.8f; CaptionStyle.Box -> 1f }
        val y = when (position) { 1 -> 0.65f; 2 -> if (c.captionStyle == CaptionStyle.Subtitle) -0.8f else -0.65f; else -> 0f }
        return OverlaySettings.Builder().setScale(scale, scale).setBackgroundFrameAnchor(0f, y).build()
    }

    fun styled(raw: String, c: StudioClip): SpannableString {
        val width = if (c.captionStyle == CaptionStyle.Subtitle) 34 else 22
        val wrapped = raw.lineSequence().flatMap { line -> wrap(line, width).asSequence() }.joinToString("\n")
        val color = 0xFF000000.toInt() or c.captionColor
        val text = SpannableString(wrapped).apply {
            setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            when (c.captionStyle) {
                CaptionStyle.Box -> setSpan(BackgroundColorSpan(0xA6000000.toInt()), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                CaptionStyle.Subtitle -> setSpan(BackgroundColorSpan(0x80000000.toInt()), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                CaptionStyle.Pop -> setSpan(BackgroundColorSpan(0xE6111111.toInt()), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                CaptionStyle.Bold -> Unit
            }
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    /** Breaks a line at spaces so captions never split a word. */
    fun wrap(line: String, width: Int): List<String> {
        if (line.length <= width) return listOf(line)
        val out = mutableListOf<String>(); var cur = StringBuilder()
        line.split(' ').forEach { w ->
            if (cur.isNotEmpty() && cur.length + 1 + w.length > width) { out += cur.toString(); cur = StringBuilder() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(w)
            while (cur.length > width) { out += cur.substring(0, width); cur = StringBuilder(cur.substring(width)) }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    fun audio(speed: Float, volume: Float): List<AudioProcessor> = buildList {
        if (speed != 1f) add(SonicAudioProcessor().apply { setSpeed(speed); setPitch(1f) })
        if (volume != 1f) add(GainProcessor(volume))
    }
    fun effects(c: StudioClip, p: StudioProject) = Effects(audio(c.speed, c.volume), video(c, p, true))
}

/** Gain applied to interleaved PCM; exports use the same gain shown by the preview slider. */
@androidx.annotation.OptIn(UnstableApi::class)
class GainProcessor(private val gain: Float) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val out = replaceOutputBuffer(inputBuffer.remaining())
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) while (inputBuffer.remaining() >= 4) out.putFloat((inputBuffer.float * gain).coerceIn(-1f, 1f))
        else while (inputBuffer.remaining() >= 2) out.putShort((inputBuffer.short * gain).toInt().coerceIn(-32768, 32767).toShort())
        out.flip()
    }
}

/** Fades a whole film's mixed sound in from silence and out to silence at its end. */
@androidx.annotation.OptIn(UnstableApi::class)
class FadeProcessor(private val fadeInMs: Long, private val fadeOutMs: Long, private val totalMs: Long) : BaseAudioProcessor() {
    private var frame = 0L
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    override fun onFlush() { frame = 0 }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val fmt = inputAudioFormat
        val rate = fmt.sampleRate.toLong(); val ch = fmt.channelCount
        val inFrames = fadeInMs * rate / 1000; val outFrames = fadeOutMs * rate / 1000; val total = totalMs * rate / 1000
        val out = replaceOutputBuffer(inputBuffer.remaining())
        val float = fmt.encoding == C.ENCODING_PCM_FLOAT
        val frameBytes = ch * if (float) 4 else 2
        while (inputBuffer.remaining() >= frameBytes) {
            val g = Fades.gain(frame, inFrames, outFrames, total)
            repeat(ch) {
                if (float) out.putFloat(inputBuffer.float * g) else out.putShort((inputBuffer.short * g).toInt().toShort())
            }
            frame++
        }
        out.flip()
    }
}

object Fades {
    /** Linear gain for [frame] of [total], rising over [inFrames] and falling over the last [outFrames]. */
    fun gain(frame: Long, inFrames: Long, outFrames: Long, total: Long): Float {
        var g = 1f
        if (inFrames > 0 && frame < inFrames) g = minOf(g, frame.toFloat() / inFrames)
        if (outFrames > 0 && total > 0 && frame > total - outFrames) g = minOf(g, ((total - frame).toFloat() / outFrames).coerceAtLeast(0f))
        return g
    }
}

/**
 * Speech captions that change with time, at the bottom unless the title already sits there. Frames may carry times from the
 * clip's start or from the file's start depending on the pipeline, so the first frame seen decides which.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SpeechCaptions(private val c: StudioClip, p: StudioProject) : TextOverlay() {
    private val overlay = StudioEffects.settings(c, p, if (c.title.isNotBlank() && c.titlePosition == 2) 1 else 2)
    private var offsetMs: Long? = null
    private val blank = SpannableString(" ")
    override fun getText(presentationTimeUs: Long): SpannableString {
        val ms = presentationTimeUs / 1000
        val offset = offsetMs ?: (if (c.startMs > 0 && kotlin.math.abs(ms - c.startMs) < 400) 0L else c.startMs).also { offsetMs = it }
        return app.ultimate.speech.Captions.at(c.captions, ms + offset)?.let { StudioEffects.styled(it, c) } ?: blank
    }
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = overlay
}
