package app.ultimate.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Local AI: Cactus Needle running on the phone. The model (35 MB) is downloaded once in editions allowed online, or
 * imported from a file in the offline ones; after that nothing leaves the phone. Requests run one at a time on a
 * dedicated thread, because the engine is not thread-safe.
 */
class LocalAi(private val context: Context) {
    sealed interface State {
        data object Unsupported : State
        data object Missing : State
        data class Downloading(val fraction: Float) : State
        data object Loading : State
        data object Ready : State
        data class Failed(val why: String) : State
    }

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "needle").apply { priority = Thread.NORM_PRIORITY - 1 } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)
    private val file get() = File(context.filesDir, "ai/needle3.cact")
    private val _state = MutableStateFlow<State>(initial())
    val state: StateFlow<State> = _state

    private fun initial(): State = when {
        !NeedleNative.loaded || !runCatching { NeedleNative.engine() }.getOrDefault(false) -> State.Unsupported
        file.length() > 1_000_000 -> State.Loading.also { scope.launch { start() } }
        else -> State.Missing
    }

    /** True in editions that may use the internet; the offline ones import the file instead. */
    val canDownload: Boolean get() = context.packageManager.checkPermission(android.Manifest.permission.INTERNET, context.packageName) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun download() {
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(0f)
        scope.launch {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".part")
                var url = URL(MODEL_URL)
                var conn: HttpURLConnection
                // Hugging Face answers with a redirect to its file store; follow it by hand across hosts.
                var hops = 0
                while (true) {
                    conn = (url.openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 30_000 }
                    val code = conn.responseCode
                    if (code in 300..399 && hops++ < 5) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
                    if (code != 200) error("The download failed (HTTP $code)")
                    break
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: MODEL_BYTES
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var got = 0L; var n: Int
                        while (input.read(buf).also { n = it } >= 0) { out.write(buf, 0, n); got += n; _state.value = State.Downloading((got.toFloat() / total).coerceIn(0f, 1f)) }
                    }
                }
                if (tmp.length() < 1_000_000) error("The download was incomplete")
                tmp.renameTo(file)
            }.onFailure { _state.value = State.Failed(it.message ?: "The download failed"); return@launch }
            start()
        }
    }

    /** Copies a .cact model chosen by the person, for editions without internet access. */
    fun import(uri: Uri) {
        _state.value = State.Loading
        scope.launch {
            runCatching {
                file.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)!!.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
            }.onFailure { _state.value = State.Failed("Couldn't read that file"); return@launch }
            start()
        }
    }

    private fun start() {
        _state.value = State.Loading
        val r = NeedleNative.load(file.path)
        if (r < 0) { _state.value = State.Failed("Couldn't load the model: " + (NeedleNative.lastError() ?: "error $r")); return }
        val i = NeedleNative.init(AiTools.SYSTEM, AiTools.TOOLS)
        _state.value = if (i < 0) State.Failed("Couldn't start the model: " + (NeedleNative.lastError() ?: "error $i")) else State.Ready
    }

    /** Asks Needle what to do about [text]; confident proposals only, never acted on here. */
    suspend fun propose(text: String): List<AiCall> = withContext(thread) {
        if (_state.value != State.Ready) return@withContext emptyList()
        AiTools.parse(NeedleNative.complete(text, 256))
    }

    fun remove() { scope.launch { file.delete(); _state.value = State.Missing } }

    companion object {
        const val MODEL_URL = "https://huggingface.co/Cactus-Compute/needle3/resolve/main/needle3.cact"
        const val MODEL_BYTES = 35_335_380L
    }
}
