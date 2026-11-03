package app.ultimate.ai

/** The JNI bridge (src/main/cpp/needle_jni.c). Every call runs on [LocalAi]'s single thread. */
internal object NeedleNative {
    /** False when the native library can't load at all (an edition without it). */
    val loaded: Boolean = runCatching { System.loadLibrary("ultimate_needle"); true }.getOrDefault(false)

    @JvmStatic external fun engine(): Boolean
    @JvmStatic external fun load(path: String): Int
    @JvmStatic external fun init(system: String?, tools: String): Int
    @JvmStatic external fun complete(input: String, maxTokens: Int): String?
    @JvmStatic external fun lastError(): String?
}
