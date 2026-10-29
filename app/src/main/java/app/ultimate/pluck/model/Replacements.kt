package app.ultimate.pluck.model

/** How completely one of our tools stands in for an installed app. */
enum class Coverage { Full, Mostly }

/**
 * An installed app that one of our tools can stand in for.
 * [missing] lists what the original does that our tool does not, as found in the original's own menus and strings
 * (not by running it), so it is a prompt to double-check rather than a guarantee.
 */
data class Replacement(
    val originalPackage: String,
    val originalName: String,
    val toolId: String,
    val coverage: Coverage,
    val missing: List<String> = emptyList(),
    val note: String? = null,
    /** Other tools that cover some of the original's features, with those features. When a helper is not available they count as missing. */
    val helpers: Map<String, List<String>> = emptyMap(),
)

/** A replaceable app that is installed, with where its replacement is available. [accepted] means the user said they do not need the missing features. */
data class Cleanup(val app: AppItem, val replacement: Replacement, val via: String, val accepted: Boolean) {
    /** Safe to uninstall without losing a feature the user wants. */
    val safe: Boolean get() = replacement.coverage == Coverage.Full || accepted
}

/**
 * The apps we know how to replace. When a new APK is reverse-engineered into a tool, add one entry here.
 * Keep [Replacement.missing] honest: anything the original offers that the tool does not.
 */
object Replacements {
    val known: List<Replacement> = listOf(
        Replacement(
            "com.compress.video.compressor.size.reducer", "Compress Video Size", "squeeze", Coverage.Mostly,
            missing = listOf(
                "Saving as MKV, WebM, AVI or other containers (results are MP4: H.264, HEVC or AV1)",
                "A free-form crop box (crops to a shape you can slide into place)",
            ),
            note = "Squeeze crops, changes speed, converts any playable video to MP4 and takes an exact bitrate and resolution.",
            helpers = mapOf("studio" to listOf("Add music to a video", "Join several videos into one")),
        ),
        Replacement(
            "mp3videoconverter.videotomp3.videotomp3converter", "Video to MP3 Converter", "audio", Coverage.Full,
            note = "Video to audio also cuts, converts (MP3, M4A, WAV), changes the speed of and joins audio files.",
            helpers = mapOf("studio" to listOf("Video merger")),
        ),
        Replacement(
            "com.toralabs.apkextractor", "Apk Extractor", "pluck", Coverage.Full,
            note = "Pluck names saved files with your own pattern. The original also has an Install option; its purpose was not confirmed.",
        ),
    )

    private val byPackage = known.associateBy { it.originalPackage }

    /** Our own editions must never be offered for cleanup, even if someone marks them by hand. */
    val ours = setOf("app.ultimate", "app.ultimate.plus", "app.squeeze", "app.ultimate.audio", "app.pluck", "app.ultimate.traffic", "app.ultimate.studio")

    fun forPackage(pkg: String): Replacement? = byPackage[pkg]

    private val names = mapOf("squeeze" to "Squeeze", "audio" to "Video to audio", "pluck" to "Pluck", "studio" to "Studio")
    private val homes = mapOf(
        "squeeze" to listOf("app.squeeze", "app.ultimate", "app.ultimate.plus"),
        "audio" to listOf("app.ultimate.audio", "app.ultimate", "app.ultimate.plus"),
        "pluck" to listOf("app.pluck", "app.ultimate", "app.ultimate.plus"),
        "studio" to listOf("app.ultimate.studio", "app.ultimate", "app.ultimate.plus"),
    )

    fun toolName(id: String) = names[id] ?: id

    /**
     * Which tools can actually be used right now, and where: a tool built into this app, or an installed edition that has it.
     * A replacement that is not installed anywhere is not offered, since uninstalling the original would leave nothing.
     */
    fun availability(ownTools: Set<String>, installed: Set<String>): Map<String, String> =
        names.keys.mapNotNull { id ->
            when {
                id in ownTools -> id to "${names.getValue(id)} in this app"
                else -> homes[id].orEmpty().firstOrNull { it in installed }?.let { id to "${names.getValue(id)} ($it)" }
            }
        }.toMap()

    fun find(apps: List<AppItem>, available: Map<String, String>, custom: Map<String, Replacement>, accepted: Set<String>): List<Cleanup> =
        apps.mapNotNull { a ->
            if (a.packageName in ours) return@mapNotNull null
            val r = custom[a.packageName] ?: byPackage[a.packageName] ?: return@mapNotNull null
            if (r.toolId !in available) return@mapNotNull null
            Cleanup(a, effective(r, available), via(r, available), a.packageName in accepted)
        }

    /** A helper that is not available here leaves its features missing, so the cleanup advice matches what is actually installed. */
    fun effective(r: Replacement, available: Map<String, String>): Replacement {
        val absent = r.helpers.filterKeys { it !in available }
        return if (absent.isEmpty()) r else r.copy(coverage = Coverage.Mostly, missing = absent.values.flatten() + r.missing)
    }

    /** "Squeeze + Studio in this app", or each tool's own place when they live in different apps. */
    fun via(r: Replacement, available: Map<String, String>): String {
        val ids = listOf(r.toolId) + r.helpers.keys.filter { it in available }
        val places = ids.map { available.getValue(it) }
        return if (places.all { it.endsWith(" in this app") }) ids.joinToString(" + ") { toolName(it) } + " in this app" else places.joinToString(" and ")
    }
}
