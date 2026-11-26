package app.ultimate.audio.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class OutFormat(val label: String, val ext: String, val mime: String, val blurb: String) {
    Mp3("MP3", "mp3", "audio/mpeg", "Plays everywhere."),
    M4a("M4A", "m4a", "audio/mp4", "AAC. Copied untouched and instantly when the source is already AAC and nothing else changes."),
    Opus("Opus", "opus", "audio/ogg", "Built for speech: far smaller than MP3 at the same clarity. Plays on Android 10 and later and in most players, but not everywhere MP3 does."),
    Wav("WAV", "wav", "audio/x-wav", "Uncompressed. Large, but ideal for editing."),
}

data class AudioSettings(
    val format: OutFormat = OutFormat.Mp3,
    val kbps: Int = 192,
    val mono: Boolean = false,
    val trimStartMs: Long = 0,
    /** Exclusive end of the part to keep, or -1 for "to the end". */
    val trimEndMs: Long = -1,
    /** Playback speed of the result; pitch is kept. */
    val speed: Float = 1f,
    /** Several inputs become one file, in the order given, instead of one file each. */
    val join: Boolean = false,
    /** Pitch change in semitones, independent of speed. */
    val pitch: Int = 0,
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f,
    /** Low-pass cutoff in Hz, or 0 for none. */
    val highCutHz: Int = 0,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    /** Raise (or lower) the level so the loudest moment sits just under full scale. */
    val normalize: Boolean = false,
    /** MP3 only: variable bit rate at this level, from 0 (best, biggest) to 9 (smallest), or -1 for the fixed [kbps]. */
    val vbr: Int = -1,
    /** Cut the kept part into parts of this many minutes of the original recording; 0 keeps one file. */
    val splitMin: Int = 0,
    /** Move each cut to the quietest moment close by, so nothing is chopped in the middle of a word. */
    val splitQuiet: Boolean = true,
) {
    /** Anything that changes the sound beyond format, speed and channels. */
    val shaped: Boolean get() = pitch != 0 || bassDb != 0f || trebleDb != 0f || highCutHz != 0 || fadeInMs > 0 || fadeOutMs > 0 || normalize
}

/** What a video's audio track looks like. */
data class AudioSource(
    val durationMs: Long,
    val mime: String,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
) {
    /** AAC can be copied into an M4A without re-encoding. */
    val canCopy: Boolean get() = mime.equals(AAC, ignoreCase = true)

    companion object {
        const val AAC = "audio/mp4a-latm"
        const val OPUS = "audio/opus"
    }
}

object AudioPlanner {
    val MP3_BITRATES = listOf(64, 96, 128, 192, 256, 320)

    /** Opus bit rates offered, in kbps. For voice, mono at 16 to 32 is plenty; the higher ones are for music. */
    val OPUS_BITRATES = listOf(16, 24, 32, 48, 64, 96, 128)

    fun opusLabel(kbps: Int) = when (kbps) {
        16 -> "Tiny voice"; 24 -> "Voice"; 32 -> "Clear voice"; 48 -> "Good"; 64 -> "Great"; 96 -> "Music"; 128 -> "Best"; else -> ""
    }

    /** The nearest Opus step at or below [kbps], so a number from another format still means something. */
    fun opusKbps(kbps: Int): Int = OPUS_BITRATES.lastOrNull { it <= kbps } ?: OPUS_BITRATES.first()

    /** Opus takes only these sample rates; anything else is resampled to 48 kHz. */
    val OPUS_RATES = setOf(8000, 12000, 16000, 24000, 48000)

    /** The sample rate a file of [format] is encoded at, from the source's [sourceRate]. */
    fun encoderRate(sourceRate: Int, format: OutFormat): Int = when (format) {
        OutFormat.Wav -> sourceRate
        OutFormat.Opus -> if (sourceRate in OPUS_RATES) sourceRate else 48_000
        else -> encoderRate(sourceRate)
    }

    /** [s] as it can be made on a phone with no Opus encoder: MP3 at a small variable rate, which keeps the point (small voice files). */
    fun withoutOpus(s: AudioSettings): AudioSettings = if (s.format == OutFormat.Opus) s.copy(format = OutFormat.Mp3, vbr = 6) else s

    /** A variable-bit-rate choice: the encoder spends only what each moment needs, so quiet and simple stretches cost almost nothing. */
    data class SmartLevel(val vbr: Int, val label: String, val blurb: String)
    val SMART = listOf(
        SmartLevel(8, "Tiny", "Smallest. For voice: meetings, lectures, calls and long recordings."),
        SmartLevel(6, "Small", "Clear voice and podcasts, and fine for most music."),
        SmartLevel(4, "Balanced", "Most music sounds the same as the original."),
        SmartLevel(2, "High", "Very close to the original, for music you care about."),
    )

    /** About what each level spends on average for stereo, in kbps. The real figure follows the sound; mono is a little over half. */
    private val VBR_STEREO_KBPS = mapOf(8 to 85, 6 to 130, 4 to 165, 2 to 190)

    fun vbrKbps(level: Int, mono: Boolean): Int = ((VBR_STEREO_KBPS[level] ?: 130) * if (mono) 0.55 else 1.0).toInt()

    /** The parts a single file will be saved as: one if no split is asked for. Times are on the original recording. */
    fun parts(src: AudioSource, s: AudioSettings): List<SplitPlan.Part> {
        val start = s.trimStartMs.coerceAtLeast(0)
        val end = if (s.trimEndMs > 0) min(s.trimEndMs, src.durationMs) else src.durationMs
        return SplitPlan.plan(start, end, s.splitMin * 60_000L)
    }

    /** What the preview is rendered as: the real codec when there is one, so the compression itself can be heard; WAV otherwise. */
    fun previewFormat(src: AudioSource, s: AudioSettings): OutFormat = if (s.format == OutFormat.Wav || copies(src, s)) OutFormat.Wav else s.format

    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    val HIGH_CUTS = listOf(0 to "Off", 16_000 to "16 kHz", 12_000 to "12 kHz", 8_000 to "8 kHz")
    val FADES = listOf(0L to "None", 1_000L to "1 s", 3_000L to "3 s", 5_000L to "5 s")
    /** Peak level normalizing aims for: about -0.4 dBFS, leaving room for encoders to overshoot. */
    const val NORMALIZE_PEAK = 31_200

    /** Sonic's pitch factor for a change in semitones. */
    fun pitchFactor(semitones: Int): Float = Math.pow(2.0, semitones / 12.0).toFloat()

    /** AAC frames can be copied unchanged only when nothing about the sound changes. */
    fun copies(src: AudioSource, s: AudioSettings): Boolean =
        s.format == OutFormat.M4a && src.canCopy && s.speed == 1f && !s.mono && !s.join && !s.shaped

    /** Milliseconds that will be kept after trimming. */
    fun keptMs(src: AudioSource, s: AudioSettings): Long {
        val end = if (s.trimEndMs > 0) min(s.trimEndMs, src.durationMs) else src.durationMs
        return max(end - s.trimStartMs.coerceAtLeast(0), 0)
    }

    /** Length of the result: the kept part, played at [AudioSettings.speed]. */
    fun outputMs(src: AudioSource, s: AudioSettings): Long = (keptMs(src, s) / s.speed.toDouble()).toLong()

    fun outputChannels(src: AudioSource, s: AudioSettings): Int = if (s.mono || src.channels <= 1) 1 else 2

    /** Channels of a joined file: stereo if any input is, unless mono was asked for. */
    fun outputChannels(sources: List<AudioSource>, s: AudioSettings): Int = if (s.mono || sources.all { it.channels <= 1 }) 1 else 2

    fun estimateBytes(src: AudioSource, s: AudioSettings): Long {
        val seconds = outputMs(src, s) / 1000.0
        return when {
            copies(src, s) -> ((if (src.bitrate > 0) src.bitrate else 128_000) / 8.0 * seconds).toLong()
            s.format == OutFormat.Mp3 && s.vbr >= 0 -> (vbrKbps(s.vbr, outputChannels(src, s) == 1) * 1000 / 8.0 * seconds).toLong()
            s.format == OutFormat.Mp3 -> (s.kbps * 1000 / 8.0 * seconds).toLong()
            s.format == OutFormat.M4a -> (aacKbps(s.kbps) * 1000 / 8.0 * seconds).toLong()
            s.format == OutFormat.Opus -> (opusKbps(s.kbps) * 1000 / 8.0 * seconds).toLong()
            else -> (encoderRate(src.sampleRate) * outputChannels(src, s) * 2.0 * seconds).toLong() + 44
        }
    }

    /** Phone AAC encoders top out around 256 kbps for stereo. */
    fun aacKbps(kbps: Int) = kbps.coerceAtMost(256)

    fun bitrateLabel(kbps: Int) = when (kbps) {
        64 -> "Voice"
        96 -> "Compact"
        128 -> "Good"
        192 -> "Great"
        256 -> "Excellent"
        320 -> "Maximum"
        else -> ""
    }

    /** Sample rates the MP3 and AAC encoders accept; anything else is resampled to 44.1 kHz. */
    val ENCODER_RATES = setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

    fun encoderRate(sourceRate: Int): Int = if (sourceRate in ENCODER_RATES) sourceRate else 44100

    /** Name for a joined file, from the first input. */
    fun joinedName(first: String, count: Int): String = "${first.substringBeforeLast('.', first)} + ${count - 1} more"

    fun peakBuckets(): Int = 160
}

/** Converts interleaved 16-bit PCM between channel layouts and sample rates. */
class PcmMixer(private val srcChannels: Int, private val dstChannels: Int, srcRate: Int, dstRate: Int) {
    private val ratio = srcRate.toDouble() / dstRate
    private val resample = srcRate != dstRate
    private var pos = 0.0
    private var prev = ShortArray(dstChannels)
    private var started = false
    private var scratch = ShortArray(0)

    /** Returns the converted samples in a reused buffer, and how many of them are valid. */
    fun process(input: ShortArray, inputShorts: Int): Pair<ShortArray, Int> {
        val frames = inputShorts / srcChannels
        if (scratch.size < frames * dstChannels) scratch = ShortArray(frames * dstChannels)
        for (f in 0 until frames) {
            val base = f * srcChannels
            when {
                srcChannels == dstChannels -> for (c in 0 until dstChannels) scratch[f * dstChannels + c] = input[base + c]
                dstChannels == 1 -> scratch[f] = if (srcChannels >= 2) ((input[base] + input[base + 1]) / 2).toShort() else input[base]
                else -> for (c in 0 until dstChannels) scratch[f * dstChannels + c] = input[base + min(c, srcChannels - 1)]
            }
        }
        if (!resample) return scratch to frames * dstChannels
        return resampled(frames)
    }

    private var resampleOut = ShortArray(0)

    private fun resampled(frames: Int): Pair<ShortArray, Int> {
        if (frames == 0) return scratch to 0
        if (!started) {
            for (c in 0 until dstChannels) prev[c] = scratch[c]
            started = true
        }
        val maxOut = ((frames / ratio).toInt() + 2) * dstChannels
        if (resampleOut.size < maxOut) resampleOut = ShortArray(maxOut)
        var p = pos
        var n = 0
        // Virtual stream: frame 0 is the last frame of the previous buffer, frame k (k >= 1) is this buffer's frame k-1.
        while (true) {
            val i = p.toInt()
            if (i + 1 > frames) break
            val frac = p - i
            for (c in 0 until dstChannels) {
                val a = if (i == 0) prev[c].toInt() else scratch[(i - 1) * dstChannels + c].toInt()
                val b = scratch[i * dstChannels + c].toInt()
                resampleOut[n++] = (a + (b - a) * frac).roundToInt().toShort()
            }
            p += ratio
        }
        pos = p - frames
        for (c in 0 until dstChannels) prev[c] = scratch[(frames - 1) * dstChannels + c]
        return resampleOut to n
    }
}

/** Tracks the loudest sample in each of [buckets] equal slices of the output, for drawing a waveform while converting. */
class PeakTracker(totalFrames: Long, private val buckets: Int = AudioPlanner.peakBuckets()) {
    private val perBucket = max(1L, totalFrames / buckets)
    private val peaks = ArrayList<Float>(buckets)
    private var current = 0
    private var inBucket = 0L

    /** Feeds interleaved samples; every [channels]-th sample starts a new frame. */
    fun add(samples: ShortArray, count: Int, channels: Int) {
        var f = 0
        while (f + channels <= count) {
            for (c in 0 until channels) current = max(current, kotlin.math.abs(samples[f + c].toInt()))
            f += channels
            if (++inBucket >= perBucket && peaks.size < buckets) {
                peaks += (current / 32768f).coerceIn(0f, 1f)
                current = 0
                inBucket = 0
            }
        }
    }

    fun snapshot(): List<Float> = peaks.toList() + if (inBucket > 0 && peaks.size < buckets) listOf((current / 32768f).coerceIn(0f, 1f)) else emptyList()
}

/** A ready-made starting point on the tool's first screen: it sets the options, then asks for the right kind of file. */
data class AudioTemplate(val title: String, val blurb: String, val audioFiles: Boolean, val apply: (AudioSettings) -> AudioSettings)

object AudioTemplates {
    val all = listOf(
        AudioTemplate("Song from a video", "High-quality MP3 of a video's sound", audioFiles = false) { AudioSettings(format = OutFormat.Mp3, kbps = 256) },
        AudioTemplate("Ringtone", "Fades in and out, evened out; trim to the best part", audioFiles = true) {
            AudioSettings(format = OutFormat.Mp3, kbps = 192, fadeInMs = 1_000, fadeOutMs = 3_000, normalize = true)
        },
        AudioTemplate("Podcast at 1.5×", "Faster, mono and small, voices unchanged", audioFiles = true) { AudioSettings(format = OutFormat.Mp3, vbr = 6, mono = true, speed = 1.5f) },
        AudioTemplate("Join voice notes", "Several recordings into one, at an even level", audioFiles = true) { AudioSettings(format = OutFormat.Mp3, vbr = 6, mono = true, join = true, normalize = true) },
        AudioTemplate("Clearer voice memo", "Less rumble and hiss, louder", audioFiles = true) {
            AudioSettings(format = OutFormat.Mp3, vbr = 6, mono = true, bassDb = -6f, trebleDb = 3f, highCutHz = 12_000, normalize = true)
        },
        AudioTemplate("Long recording, tiny", "Meetings, lectures and calls: mono and as small as it can be", audioFiles = false) { AudioSettings(format = OutFormat.Mp3, vbr = 8, mono = true) },
        AudioTemplate("Long recording in 1-hour parts", "Mono, small, cut every hour at a quiet moment", audioFiles = false) { AudioSettings(format = OutFormat.Mp3, vbr = 6, mono = true, splitMin = 60) },
        AudioTemplate("Long recording, smallest (Opus)", "Speech as Opus, mono: a fifth of the size of MP3 at 128 kbps. Where this phone can make it", audioFiles = false) { AudioSettings(format = OutFormat.Opus, kbps = 24, mono = true) },
    )
}
