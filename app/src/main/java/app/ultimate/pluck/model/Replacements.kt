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
        Replacement(
            "com.splendapps.decibel", "Sound Meter", "sound-meter", Coverage.Mostly,
            missing = listOf("Readings in calibrated dB(A): the Sound meter shows unweighted levels unless you calibrate it against a reference meter"),
            note = "The Sound meter shows live level, peak and average, keeps sessions and exports them.",
        ),
    )

    /**
     * Apps we have no entry for, recognised by what their name says they do. These were not checked feature by
     * feature, so they are only ever offered as "check first", never as safe.
     */
    private val byName: List<Pair<List<String>, String>> = listOf(
        listOf("sound meter", "decibel", "db meter", "noise meter", "sound level") to "sound-meter",
        listOf("voice recorder", "audio recorder", "sound recorder", "voice memo") to "recorder",
        listOf("teleprompter", "prompter") to "prompter",
        listOf("clipboard") to "clips",
        listOf("notification history", "notification log", "notifications history") to "inbox",
        listOf("video compressor", "compress video", "video compress", "reduce video") to "squeeze",
        listOf("video to mp3", "video to audio", "mp3 converter", "audio extractor") to "audio",
        listOf("apk extractor", "apk export", "apk backup") to "pluck",
        listOf("video editor", "video maker") to "studio",
        listOf("screen time", "app usage tracker", "usage tracker") to "screentime",
        listOf("network scanner", "wifi scanner", "lan scanner", "ip scanner") to "network",
        listOf("sensor test", "phone sensors", "sensors toolbox", "bubble level", "spirit level") to "sensors",
        listOf("storage analyzer", "disk usage", "storage analyser") to "storage",
        listOf("duplicate photo", "photo cleaner", "gallery cleaner") to "cleanup",
    )

    /** A guess from the app's name, for apps not in [known]. */
    fun guess(a: AppItem): Replacement? {
        if (a.isSystem) return null
        val name = a.label.lowercase()
        val tool = byName.firstOrNull { (words, _) -> words.any { it in name } }?.second ?: return null
        return Replacement(a.packageName, a.label, tool, Coverage.Mostly,
            missing = listOf("Not checked feature by feature: going by its name, ${toolName(tool)} does the same job. Open it once and make sure you don't rely on anything extra."))
    }

    private val byPackage = known.associateBy { it.originalPackage }

    /** Our own editions must never be offered for cleanup, even if someone marks them by hand. */
    val ours = setOf("app.ultimate", "app.ultimate.plus", "app.squeeze", "app.ultimate.audio", "app.pluck", "app.ultimate.traffic", "app.ultimate.studio")

    fun forPackage(pkg: String): Replacement? = byPackage[pkg]

    private val names = mapOf(
        "squeeze" to "Squeeze", "audio" to "Video to audio", "pluck" to "Pluck", "studio" to "Studio",
        "sound-meter" to "Sound meter", "recorder" to "Recorder", "prompter" to "Teleprompter", "clips" to "Clipboard", "inbox" to "Notification history",
        "screentime" to "Screen time", "network" to "Network scanner", "sensors" to "Sensors", "storage" to "Storage", "cleanup" to "Clean up",
    )
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
            val r = custom[a.packageName] ?: byPackage[a.packageName] ?: guess(a) ?: return@mapNotNull null
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
