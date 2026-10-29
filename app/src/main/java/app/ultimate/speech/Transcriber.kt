package app.ultimate.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.ultimate.audio.engine.AudioConverter
import app.ultimate.audio.engine.AudioProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Turns speech in any video or audio file into timed text with the phone's own on-device recogniser. Audio is decoded here
 * and handed over through a pipe, so nothing is played aloud and nothing leaves the phone.
 */
class Transcriber(private val context: Context) {
    class Unavailable(message: String) : Exception(message)

    /** Why transcription cannot run on this phone, or null when it can. */
    fun unavailableReason(): String? = when {
        Build.VERSION.SDK_INT < 33 -> "Transcribing needs Android 13 or newer."
        !onDevice() -> "This phone has no on-device speech recognition. On Samsung, install Speech Recognition and Synthesis from Google, then try again."
        else -> null
    }

    @androidx.annotation.ChecksSdkIntAtLeast(api = 31)
    private fun onDevice() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    suspend fun transcribe(uri: Uri, startMs: Long = 0, endMs: Long = -1, onProgress: (Float) -> Unit = {}): List<Segment> {
        unavailableReason()?.let { throw Unavailable(it) }
        if (Build.VERSION.SDK_INT < 33) throw Unavailable("Transcribing needs Android 13 or newer.")
        val source = withContext(Dispatchers.IO) { AudioProbe.probe(context, uri).audio } ?: throw Unavailable("This file has no sound.")
        val pcm = AudioConverter(context).speechPcm(uri, source, startMs, endMs) { onProgress(it * 0.2f) }
        val chunks = Chunker.split(Chunker.levels(pcm))
        val out = mutableListOf<Segment>()
        val per = (16_000 * Chunker.WINDOW_MS / 1000).toInt()
        chunks.forEachIndexed { i, range ->
            val slice = pcm.copyOfRange(range.first * per, minOf(pcm.size, (range.last + 1) * per))
            val text = recognize(slice).trim()
            if (text.isNotEmpty()) out += Segment(startMs + range.first * Chunker.WINDOW_MS, startMs + (range.last + 1) * Chunker.WINDOW_MS, text.replaceFirstChar { it.uppercase() })
            onProgress(0.2f + 0.8f * (i + 1) / chunks.size)
        }
        return out
    }

    @androidx.annotation.RequiresApi(33)
    private suspend fun recognize(pcm: ShortArray): String = withContext(Dispatchers.Main) {
        val (read, write) = ParcelFileDescriptor.createPipe()
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        if (cont.isActive) cont.resume(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                    }
                    override fun onError(error: Int) {
                        if (!cont.isActive) return
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> cont.resume("")
                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                                runCatching { recognizer.triggerModelDownload(intent(read)) }
                                cont.resumeWithException(Unavailable("The speech model for ${Locale.getDefault().displayLanguage} isn't on this phone yet. Android is downloading it; try again in a minute."))
                            }
                            else -> cont.resumeWithException(Unavailable("Speech recognition stopped (error $error)."))
                        }
                    }
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                recognizer.startListening(intent(read))
                // Feed the audio on a background thread; closing the pipe tells the recogniser the audio has ended.
                Thread {
                    runCatching {
                        ParcelFileDescriptor.AutoCloseOutputStream(write).use { os ->
                            val bytes = ByteArray(pcm.size * 2)
                            for (i in pcm.indices) { bytes[i * 2] = pcm[i].toInt().toByte(); bytes[i * 2 + 1] = (pcm[i].toInt() shr 8).toByte() }
                            os.write(bytes)
                        }
                    }
                }.start()
                cont.invokeOnCancellation { runCatching { recognizer.cancel() } }
            }
        } finally {
            recognizer.destroy()
            runCatching { read.close() }
        }
    }

    @androidx.annotation.RequiresApi(33)
    private fun intent(audio: ParcelFileDescriptor) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
    }
}
