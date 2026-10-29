package app.ultimate.audio.engine

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat as PlatformAudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import app.ultimate.audio.model.AudioPlanner
import app.ultimate.audio.model.AudioSettings
import app.ultimate.audio.model.AudioSource
import app.ultimate.audio.model.OutFormat
import app.ultimate.audio.model.PcmMixer
import app.ultimate.audio.model.PeakTracker
import app.ultimate.audio.model.Stretcher
import app.ultimate.audio.model.ToneShaper
import app.ultimate.core.JobLog
import app.ultimate.core.formatBytes
import app.ultimate.core.formatDuration
import de.sciss.jump3r.lowlevel.LameEncoder
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class Converted(
    val uri: Uri,
    val displayName: String,
    val bytes: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
    val peaks: List<Float>,
    val format: OutFormat,
)

/** One file to read audio from. */
data class ConvertInput(val uri: Uri, val name: String, val source: AudioSource)

/**
 * Turns the audio of videos or audio files into MP3, M4A (AAC) or WAV, saved to Music/Ultimate. Several inputs can be joined into one
 * file, and the speed can change without changing pitch. Every path decodes with the phone's own decoders into 16-bit PCM, then
 * mixes, stretches and encodes; MP3 uses a Java port of LAME and AAC the phone's encoder. AAC sources are copied untouched when
 * nothing about the sound changes.
 */
class AudioConverter(private val context: Context) {
    private var log: JobLog = JobLog()

    suspend fun convert(
        inputs: List<ConvertInput>,
        settings: AudioSettings,
        log: JobLog,
        onProgress: (fraction: Float, peaks: List<Float>) -> Unit,
    ): Converted = withContext(Dispatchers.IO) {
        require(inputs.isNotEmpty())
        this@AudioConverter.log = log
        // Trim points belong to one file; a join always uses whole files.
        val s = (if (inputs.size == 1) settings else settings.copy(trimStartMs = 0, trimEndMs = -1)).copy(join = inputs.size > 1)
        val format = s.format
        val copy = inputs.size == 1 && AudioPlanner.copies(inputs[0].source, s)
        val base = if (inputs.size == 1) baseName(inputs[0].name) else baseName(AudioPlanner.joinedName(inputs[0].name, inputs.size))
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$base.${format.ext}")
            put(MediaStore.Audio.Media.MIME_TYPE, format.mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Ultimate")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Could not create the output file")
        try {
            val outMs = inputs.sumOf { AudioPlanner.outputMs(it.source, s) }
            if (outMs <= 0) throw IOException("Nothing left after trimming")
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Could not open the output file")
            val facts = pfd.use { fd -> if (copy) copyAac(inputs[0].uri, inputs[0].source, s, fd, onProgress) else transcode(inputs, s, outMs, fd, onProgress) }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val bytes = size(uri)
            log.detail("  wrote   ${formatBytes(bytes)} · ${bytes * 8 / outMs} kbps average · ${facts.sampleRate} Hz · ${if (facts.channels == 1) "mono" else "stereo"}")
            Converted(
                uri, displayName(uri) ?: "$base.${format.ext}", bytes, outMs, facts.sampleRate, facts.channels,
                (bytes * 8000 / outMs).toInt(), facts.peaks, format,
            )
        } catch (e: Throwable) {
            // Includes cancellation: never leave a half-written file behind.
            withContext(NonCancellable) { runCatching { resolver.delete(uri, null, null) } }
            throw e
        }
    }

    private data class Facts(val sampleRate: Int, val channels: Int, val peaks: List<Float>)

    // ─────────────────────────── decode → mix → stretch → encode ───────────────────────────

    private suspend fun transcode(
        inputs: List<ConvertInput>, s: AudioSettings, outMs: Long, fd: ParcelFileDescriptor,
        onProgress: (Float, List<Float>) -> Unit,
    ): Facts {
        val first = inputs[0].source
        val rate = if (s.format == OutFormat.Wav) first.sampleRate else AudioPlanner.encoderRate(first.sampleRate)
        val channels = AudioPlanner.outputChannels(inputs.map { it.source }, s)
        if (inputs.size > 1) log.detail("  join    ${inputs.size} files, in order, as one")
        if (s.speed != 1f) log.detail("  speed   ${s.speed}× (pitch kept)")
        if (s.pitch != 0) log.detail("  pitch   ${if (s.pitch > 0) "+" else ""}${s.pitch} semitones")
        if (s.bassDb != 0f || s.trebleDb != 0f || s.highCutHz > 0) log.detail("  tone    bass ${s.bassDb} dB · treble ${s.trebleDb} dB · high cut ${if (s.highCutHz > 0) "${s.highCutHz} Hz" else "off"}")
        if (s.fadeInMs > 0 || s.fadeOutMs > 0) log.detail("  fades   in ${s.fadeInMs} ms · out ${s.fadeOutMs} ms")
        val totalFrames = outMs * rate / 1000

        /** Decodes every input and runs it through mixing, stretching and shaping, handing finished PCM to [emit]. */
        suspend fun chain(gain: Float, progressShare: Float, progressStart: Float, emit: (ShortArray, Int) -> Unit) {
            val stretcher = if (s.speed != 1f || s.pitch != 0) Stretcher(rate, channels, s.speed, AudioPlanner.pitchFactor(s.pitch)) else null
            val shaper = if (s.shaped || gain != 1f) ToneShaper(rate, channels, s.bassDb, s.trebleDb, s.highCutHz, s.fadeInMs * rate / 1000, s.fadeOutMs * rate / 1000, totalFrames, gain) else null
            fun out(samples: ShortArray, n: Int) { if (n <= 0) return; shaper?.process(samples, n); emit(samples, n) }
            inputs.forEachIndexed { i, input ->
                if (inputs.size > 1 && progressStart > 0f) log.detail("  part ${i + 1}  ${input.name}")
                var mixer: PcmMixer? = null
                var mixedFrom = 0 to 0
                decode(input.uri, input.source, s, { f -> onProgress(progressStart + progressShare * (i + f) / inputs.size, emptyList()) }) { pcm, count, srcRate, srcChannels ->
                    if (mixer == null || mixedFrom != (srcRate to srcChannels)) {
                        mixer = PcmMixer(srcChannels, channels, srcRate, rate)
                        mixedFrom = srcRate to srcChannels
                    }
                    val (mixed, n) = mixer!!.process(pcm, count)
                    if (stretcher == null) out(mixed, n) else { val (st, m) = stretcher.process(mixed, n); out(st, m) }
                }
            }
            stretcher?.finish()?.let { (st, m) -> out(st, m) }
        }

        // Normalizing needs the loudest moment of the finished sound before writing, so measure it in a first pass.
        var gain = 1f
        if (s.normalize) {
            var peak = 1
            chain(1f, 0.4f, 0f) { samples, n -> for (k in 0 until n) peak = maxOf(peak, kotlin.math.abs(samples[k].toInt())) }
            gain = (AudioPlanner.NORMALIZE_PEAK.toFloat() / peak).coerceIn(0.1f, 8f)
            log.detail("  level   peak ${String.format(java.util.Locale.US, "%.1f", 20 * kotlin.math.log10(peak / 32768.0))} dBFS → gain ${String.format(java.util.Locale.US, "%.1f", 20 * kotlin.math.log10(gain.toDouble()))} dB")
        }
        val tracker = PeakTracker(totalFrames = totalFrames)
        val sink: PcmSink = when (s.format) {
            OutFormat.Mp3 -> Mp3Sink(ParcelFileDescriptor.AutoCloseOutputStream(fd), rate, channels, s.kbps)
            OutFormat.Wav -> WavSink(ParcelFileDescriptor.AutoCloseOutputStream(fd), rate, channels)
            OutFormat.M4a -> AacSink(fd, rate, channels, AudioPlanner.aacKbps(s.kbps))
        }
        log.detail("  encoder ${sink.describe()}")
        try {
            val start = if (s.normalize) 0.4f else 0f
            chain(gain, 1f - start, start + 1e-6f) { samples, n -> tracker.add(samples, n, channels); sink.write(samples, n) }
            sink.finish()
        } finally {
            sink.close()
        }
        onProgress(1f, tracker.snapshot())
        return Facts(rate, channels, tracker.snapshot())
    }

    /**
     * Renders [lengthMs] from the trim start of one input to a WAV in [out], with [settings] applied, for a quick before/after
     * listen. Nothing is added to the user's music.
     */
    suspend fun preview(input: ConvertInput, settings: AudioSettings, out: java.io.File, lengthMs: Long = 20_000): Pair<java.io.File, List<Float>> = withContext(Dispatchers.IO) {
        val start = settings.trimStartMs.coerceAtLeast(0)
        val end = (if (settings.trimEndMs > 0) settings.trimEndMs else input.source.durationMs).coerceAtMost(start + (lengthMs * settings.speed).toLong())
        val s = settings.copy(format = OutFormat.Wav, trimStartMs = start, trimEndMs = end, join = false)
        this@AudioConverter.log = JobLog()
        val outMs = AudioPlanner.outputMs(input.source, s)
        ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { fd ->
            out to transcode(listOf(input), s, outMs, fd) { _, _ -> }.peaks
        }
    }

    /** Where PCM goes to become a file. */
    private interface PcmSink {
        fun describe(): String
        fun write(samples: ShortArray, count: Int)
        fun finish()
        /** Releases everything; safe after [finish] and after a failure. */
        fun close()
    }

    private class Mp3Sink(private val out: OutputStream, rate: Int, channels: Int, private val kbps: Int) : PcmSink {
        private val encoder = LameEncoder(
            AudioFormat(rate.toFloat(), 16, channels, true, false), kbps,
            if (channels == 1) LameEncoder.CHANNEL_MODE_MONO else LameEncoder.CHANNEL_MODE_AUTO, LameEncoder.QUALITY_MIDDLE, false,
        )
        private val mode = if (channels == 1) "mono" else "joint stereo"
        private val rateHz = rate
        private val pending = ByteArray(encoder.getPCMBufferSize())
        private var pendingLen = 0
        private val mp3 = ByteArray(encoder.getMP3BufferSize())

        override fun describe() = "LAME ${encoder.encoderVersion} · $kbps kbps CBR · $mode · $rateHz Hz"

        override fun write(samples: ShortArray, count: Int) {
            var i = 0
            while (i < count) {
                if (pendingLen + 2 > pending.size) flush()
                val take = min((pending.size - pendingLen) / 2, count - i)
                for (k in 0 until take) {
                    val v = samples[i + k].toInt()
                    pending[pendingLen++] = v.toByte()
                    pending[pendingLen++] = (v shr 8).toByte()
                }
                i += take
            }
        }

        private fun flush() {
            if (pendingLen > 0) out.write(mp3, 0, encoder.encodeBuffer(pending, 0, pendingLen, mp3))
            pendingLen = 0
        }

        override fun finish() {
            flush()
            out.write(mp3, 0, encoder.encodeFinish(mp3))
        }

        override fun close() {
            runCatching { encoder.close() }
            runCatching { out.close() }
        }
    }

    private class WavSink(private val out: ParcelFileDescriptor.AutoCloseOutputStream, private val rate: Int, private val channels: Int) : PcmSink {
        private val bytes = ByteArray(64 * 1024)
        private var dataBytes = 0L

        init {
            out.write(ByteArray(44)) // header is filled in once the length is known
        }

        override fun describe() = "WAV · 16-bit PCM · ${if (channels == 1) "mono" else "stereo"} · $rate Hz"

        override fun write(samples: ShortArray, count: Int) {
            var i = 0
            while (i < count) {
                val take = min(bytes.size / 2, count - i)
                for (k in 0 until take) {
                    val v = samples[i + k].toInt()
                    bytes[2 * k] = v.toByte()
                    bytes[2 * k + 1] = (v shr 8).toByte()
                }
                out.write(bytes, 0, take * 2)
                dataBytes += take * 2
                i += take
            }
        }

        override fun finish() {
            out.channel.position(0)
            out.write(wavHeader(dataBytes, rate, channels))
        }

        override fun close() {
            runCatching { out.close() }
        }
    }

    /** AAC-LC in an MP4 (.m4a) container, encoded by the phone. */
    private class AacSink(fd: ParcelFileDescriptor, private val rate: Int, private val channels: Int, private val kbps: Int) : PcmSink {
        private val codec = MediaCodec.createEncoderByType(AudioSource.AAC)
        private val muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        private var muxing = false
        private var frames = 0L

        init {
            val format = MediaFormat.createAudioFormat(AudioSource.AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        override fun describe() = "${codec.name} · AAC-LC $kbps kbps · ${if (channels == 1) "mono" else "stereo"} · $rate Hz"

        override fun write(samples: ShortArray, count: Int) {
            var i = 0
            while (i < count) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index < 0) { drain(false); continue }
                val buf = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                buf.clear()
                val take = min(buf.remaining() / 2 / channels * channels, count - i)
                buf.asShortBuffer().put(samples, i, take)
                codec.queueInputBuffer(index, 0, take * 2, frames * 1_000_000 / rate, 0)
                frames += take / channels
                i += take
                drain(false)
            }
        }

        override fun finish() {
            var queued = false
            var tries = 0
            while (!queued && tries++ < 500) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, frames * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                } else drain(false)
            }
            drain(true)
            if (muxing) muxer.stop()
            muxing = false
        }

        private fun drain(untilEnd: Boolean) {
            var idle = 0
            while (true) {
                val o = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd || ++idle > 500) return
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        override fun close() {
            runCatching { if (muxing) muxer.stop() }
            runCatching { muxer.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    // ─────────────────────────── M4A (copy) ────────────────────────────

    /** Draws the waveform from a quick decode, then copies the compressed AAC frames across unchanged. */
    private suspend fun copyAac(
        input: Uri, src: AudioSource, s: AudioSettings, fd: ParcelFileDescriptor,
        onProgress: (Float, List<Float>) -> Unit,
    ): Facts {
        log.detail("  mode    copying AAC frames untouched (no re-encode)")
        val tracker = PeakTracker(totalFrames = AudioPlanner.keptMs(src, s) * src.sampleRate / 1000)
        decode(input, src, s, { f -> onProgress(f * 0.9f, tracker.snapshot()) }) { pcm, n, _, ch -> tracker.add(pcm, n, ch) }
        val peaks = tracker.snapshot()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(context, input, null)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val startUs = s.trimStartMs.coerceAtLeast(0) * 1000
            val endUs = if (s.trimEndMs > 0) s.trimEndMs * 1000 else Long.MAX_VALUE
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val out = muxer.addTrack(format)
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocate(max(1 shl 16, if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0))
            val info = MediaCodec.BufferInfo()
            var base = -1L
            while (true) {
                coroutineContext.ensureActive()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val t = extractor.sampleTime
                if (t > endUs) break
                if (base < 0) base = t
                info.set(0, size, t - base, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(out, buffer, info)
                extractor.advance()
            }
            onProgress(1f, peaks)
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
        return Facts(src.sampleRate, src.channels, peaks)
    }

    // ────────────────────────────── decoding ───────────────────────────

    /**
     * Decodes the audio track between the trim points and hands interleaved 16-bit PCM to [sink].
     */
    private suspend fun decode(
        input: Uri,
        src: AudioSource,
        s: AudioSettings,
        onProgress: (Float) -> Unit,
        sink: (pcm: ShortArray, count: Int, sampleRate: Int, channels: Int) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, input, null)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val startUs = s.trimStartMs.coerceAtLeast(0) * 1000
            val endUs = if (s.trimEndMs > 0) min(s.trimEndMs, src.durationMs) * 1000 else src.durationMs * 1000
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            dec.configure(format, null, null, 0)
            dec.start()
            log.detail("  decoder ${dec.name} · ${format.getString(MediaFormat.KEY_MIME)} · $rate Hz · $channels ch")
            if (startUs > 0 || s.trimEndMs > 0) log.detail("  trim    ${formatDuration(startUs / 1000)} → ${formatDuration(endUs / 1000)}")

            val info = MediaCodec.BufferInfo()
            var scratch = ShortArray(0)
            var inputDone = false
            var outputDone = false
            var lastReport = 0L
            val span = max(endUs - startUs, 1)
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(dec.getInputBuffer(i)!!, 0)
                        val t = extractor.sampleTime
                        if (n < 0 || t > endUs) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, t, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 5_000)
                when {
                    o >= 0 -> {
                        if (info.size > 0) {
                            val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset).limit(info.offset + info.size)
                            val total = if (floatPcm) info.size / 4 else info.size / 2
                            val frames = total / channels
                            // Decoding starts at the previous sync frame, so drop whatever precedes the trim start,
                            // and whatever runs past the trim end.
                            val skip = if (info.presentationTimeUs < startUs) min(frames.toLong(), (startUs - info.presentationTimeUs) * rate / 1_000_000).toInt() else 0
                            val room = ((endUs - info.presentationTimeUs).coerceAtLeast(0) * rate / 1_000_000).toInt()
                            val keep = max(0, min(frames - skip, room))
                            if (keep > 0) {
                                val shorts = keep * channels
                                if (scratch.size < shorts) scratch = ShortArray(shorts)
                                if (floatPcm) {
                                    val fb = buf.asFloatBuffer()
                                    fb.position(skip * channels)
                                    for (k in 0 until shorts) scratch[k] = (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                } else {
                                    val sb = buf.asShortBuffer()
                                    sb.position(skip * channels)
                                    sb.get(scratch, 0, shorts)
                                }
                                sink(scratch, shorts, rate, channels)
                            }
                            val now = System.nanoTime()
                            if (now - lastReport > 120_000_000L) {
                                lastReport = now
                                onProgress(((info.presentationTimeUs - startUs).toFloat() / span).coerceIn(0f, 1f))
                            }
                        }
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == PlatformAudioFormat.ENCODING_PCM_FLOAT
                        log.detail("  pcm     $rate Hz · $channels ch · ${if (floatPcm) "float" else "16-bit"}")
                    }
                }
            }
            onProgress(1f)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /**
     * Decodes [startMs]..[endMs] of any file with sound to 16 kHz mono 16-bit PCM, the form speech recognisers take.
     * Capped at [maxMs] so a long film cannot exhaust memory.
     */
    suspend fun speechPcm(uri: Uri, source: AudioSource, startMs: Long, endMs: Long, maxMs: Long = 20 * 60_000L, onProgress: (Float) -> Unit = {}): ShortArray = withContext(Dispatchers.IO) {
        val end = minOf(if (endMs > 0) endMs else source.durationMs, startMs + maxMs)
        val out = java.io.ByteArrayOutputStream()
        var mixer: PcmMixer? = null
        var from = 0 to 0
        val bytes = ByteArray(8192 * 2)
        decode(uri, source, AudioSettings(trimStartMs = startMs, trimEndMs = end), onProgress) { pcm, count, rate, channels ->
            if (mixer == null || from != (rate to channels)) { mixer = PcmMixer(channels, 1, rate, 16_000); from = rate to channels }
            val (m, n) = mixer!!.process(pcm, count)
            var k = 0
            while (k < n) {
                val chunk = minOf(n - k, bytes.size / 2)
                for (j in 0 until chunk) { val v = m[k + j].toInt(); bytes[j * 2] = v.toByte(); bytes[j * 2 + 1] = (v shr 8).toByte() }
                out.write(bytes, 0, chunk * 2); k += chunk
            }
        }
        val raw = out.toByteArray()
        ShortArray(raw.size / 2) { i -> ((raw[i * 2].toInt() and 0xFF) or (raw[i * 2 + 1].toInt() shl 8)).toShort() }
    }

    private fun displayName(uri: Uri): String? = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun size(uri: Uri): Long = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
        ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    companion object {
        fun baseName(original: String): String =
            original.substringBeforeLast('.', original).map { if (it.isLetterOrDigit() || it in " ._-+()") it else '_' }.joinToString("").trim().ifBlank { "audio" }.take(120)
    }
}

private fun wavHeader(dataBytes: Long, rate: Int, channels: Int): ByteArray {
    val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
    b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
        .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
    b.put("data".toByteArray()).putInt(dataBytes.toInt())
    return b.array()
}
