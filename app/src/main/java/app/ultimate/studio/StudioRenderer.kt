package app.ultimate.studio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@androidx.annotation.OptIn(UnstableApi::class)
class StudioRenderer(private val context: Context) {
    suspend fun export(project: StudioProject, progress: (Int) -> Unit, detail: (String) -> Unit, onPublished: suspend (StudioExport) -> Unit): StudioExport {
        Timeline.validate(project)
        val id = UUID.randomUUID().toString()
        val temp = File(context.cacheDir, "studio-$id.mp4")
        var pending: Uri? = null
        var published = false
        try {
            val result = render(project, temp, progress)
            detail("Encoded by ${result.videoEncoderName}; ${result.width}×${result.height}; ${result.videoFrameCount} frames")
            currentCoroutineContext().ensureActive()
            val displayName = "${project.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(64).ifBlank { "Studio" }}-${System.currentTimeMillis()}.mp4"
            val uri = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Ultimate Studio"); put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val output = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("Could not create output video")
                pending = output
                resolver.openOutputStream(output)?.use { dest -> temp.inputStream().use { source ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = source.read(buffer); if (n < 0) break; dest.write(buffer, 0, n)
                    }
                } } ?: error("Could not open output video")
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    check(resolver.update(output, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) == 1) { "Could not publish output video" }
                    published = true
                    val entry = StudioExport(id, displayName, output.toString(), System.currentTimeMillis(), project.durationMs, temp.length(), result.width, result.height)
                    onPublished(entry)
                }
                output
            }
            return StudioExport(id, displayName, uri.toString(), System.currentTimeMillis(), project.durationMs, temp.length(), result.width, result.height)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (!published) pending?.let { context.contentResolver.delete(it, null, null) }
                temp.delete()
            }
        }
    }
    private suspend fun render(project: StudioProject, file: File, progress: (Int) -> Unit): ExportResult = withContext(Dispatchers.Main.immediate) {
        val videos = project.clips.map { c -> EditedMediaItem.Builder(StudioEffects.mediaItem(c))
            .setRemoveAudio(c.volume == 0f).setEffects(StudioEffects.effects(c, project)).build() }
        val sequences = mutableListOf(EditedMediaItemSequence.Builder(videos).build())
        project.music?.let { m ->
            val item = MediaItem.Builder().setUri(m.uri).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(m.startMs).setEndPositionMs(m.durationMs).build()).build()
            val music = EditedMediaItem.Builder(item).setRemoveVideo(true).setEffects(Effects(StudioEffects.audio(1f, m.volume), emptyList())).build()
            sequences += EditedMediaItemSequence.Builder(music).setIsLooping(true).build()
        }
        val fades = if (project.fadeInMs > 0 || project.fadeOutMs > 0) listOf<androidx.media3.common.audio.AudioProcessor>(FadeProcessor(project.fadeInMs, project.fadeOutMs, project.durationMs)) else emptyList()
        val composition = Composition.Builder(sequences).experimentalSetForceAudioTrack(true).setEffects(Effects(fades, emptyList()))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
        val handler = Handler(Looper.getMainLooper())
        val holder = ProgressHolder()
        var transformer: Transformer? = null
        var poll: Runnable? = null
        try {
            suspendCancellableCoroutine { continuation ->
                val encoder = DefaultEncoderFactory.Builder(context).setEnableFallback(true)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(if (project.shortSide == 1080) 8_000_000 else 3_500_000).build()).build()
                val worker = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoder).addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(exportResult)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    }).build()
                transformer = worker
                val tick = object : Runnable {
                    override fun run() {
                        if (!continuation.isActive) return
                        if (worker.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress)
                        handler.postDelayed(this, 250)
                    }
                }
                poll = tick
                worker.start(composition, file.absolutePath)
                handler.post(tick)
            }
        } finally {
            // Cancellation completes on the owning looper before temporary output can be removed.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                poll?.let(handler::removeCallbacks)
                transformer?.cancel()
            }
        }
    }
}
