package app.ultimate.pluck.model

/** One thing the original app seems to offer, and whether the user needs it in Ultimate. */
data class Gap(val text: String, val kind: Kind, val covered: Boolean) {
    /** Where it was found: the app's own words on screen, or something it declares to Android. */
    enum class Kind(val label: String) { Words("In its menus and settings"), Declared("Declared to Android") }
}

enum class GapMark { Need, Skip }

/** What a scan of one installed app found. */
data class GapScan(val pkg: String, val name: String, val version: String, val toolId: String, val gaps: List<Gap>) {
    val open: List<Gap> get() = gaps.filterNot { it.covered }
}

/**
 * Works out what an app does that one of our tools may not, from the app itself: the words it shows (read straight from
 * its resource table) and what it declares to Android (widgets, tiles, share targets, permissions). Pure: the reading
 * of the APK happens elsewhere. It finds hints, not certainties, so every result is "possibly missing".
 */
object FeatureGap {
    /** Words that name a feature. A phrase must contain one of these to count. */
    private val vocabulary = listOf(
        "trim", "cut", "split", "merge", "join", "crop", "rotate", "flip", "reverse", "speed", "slow motion", "fast motion", "watermark",
        "subtitle", "caption", "filter", "effect", "transition", "sticker", "music", "audio", "mute", "volume", "fade", "compress",
        "resolution", "bitrate", "frame rate", "fps", "quality", "gif", "convert", "format", "mp3", "mp4", "wav", "aac", "flac", "ogg",
        "m4a", "hevc", "record", "schedule", "timer", "widget", "cloud", "backup", "export", "import", "history", "calibrat", "weighting",
        "graph", "chart", "average", "peak", "maximum", "minimum", "alarm", "remote", "mirror", "scroll", "font", "countdown", "voice",
        "loop", "overlay", "floating", "picture-in-picture", "batch", "queue", "rename", "password", "lock", "theme", "bluetooth",
        "camera", "microphone", "4k", "hdr", "aspect ratio", "background", "blur", "text", "csv", "pdf", "spectrum", "frequency",
        "noise", "reminder", "shortcut", "share", "extract", "install", "split apk", "bundle", "obb", "sort", "search", "favorite",
        "favourite", "trash", "duplicate", "clean", "scan", "notification", "clipboard", "translate", "transcri", "teleprompt",
    )

    /** What each of our tools already does, in the same words, so the app's matching phrases count as covered. */
    private val covers = mapOf(
        "sound-meter" to listOf("level", "peak", "average", "decibel", "session", "export", "history", "graph", "chart", "maximum", "minimum", "calibrat", "csv", "share"),
        "prompter" to listOf("scroll", "speed", "font", "mirror", "text", "script", "countdown"),
        "squeeze" to listOf("compress", "resolution", "bitrate", "crop", "speed", "convert", "mp4", "hevc", "quality", "batch", "queue", "mute", "share", "format"),
        "audio" to listOf("mp3", "m4a", "wav", "cut", "trim", "convert", "speed", "join", "merge", "extract", "audio", "bitrate", "format", "share"),
        "pluck" to listOf("extract", "backup", "share", "export", "rename", "batch", "split apk", "bundle", "sort", "search"),
        "studio" to listOf("trim", "cut", "split", "merge", "join", "speed", "music", "text", "caption", "subtitle", "filter", "volume", "rotate", "export", "resolution", "audio", "fade", "transcri"),
        "recorder" to listOf("record", "transcri", "share", "rename", "quality", "microphone", "voice"),
        "clips" to listOf("clipboard", "history", "search", "favorite", "favourite"),
        "inbox" to listOf("notification", "history", "search"),
        "cleanup" to listOf("duplicate", "clean", "trash", "scan"),
        "storage" to listOf("clean", "scan", "sort"),
    )

    /** Strings from libraries every app carries, which say nothing about the app itself. */
    private val noise = listOf(
        "google", "play services", "firebase", "consent", "privacy", "cookie", "accessibility", "navigate up", "collapse", "expand",
        "abc_", "material", "dismiss", "rate us", "rate the app", "ad free", "remove ads", "advert", "sign in", "log in", "terms",
        "license", "licence", "crash", "error", "failed", "permission", "loading", "please wait", "unknown", "%", "\\n",
    )

    /** Short, human phrases that name a feature, de-duplicated, in the order the app lists them. */
    fun phrases(strings: List<String>, limit: Int = 60): List<String> {
        val seen = HashSet<String>()
        return strings.asSequence()
            .map { it.trim() }
            .filter { s ->
                s.length in 3..40 && s.first().isUpperCase() && s.count { it == ' ' } <= 5 && s.any(Char::isLetter) &&
                    s.none { it in "/{}<>_@=|#\\" } && !s.contains("http") && !s.endsWith(".") && !s.contains("..") &&
                    s.count(Char::isDigit) <= 4
            }
            .filter { s -> val l = s.lowercase(); noise.none { it in l } && vocabulary.any { matches(l, it) } }
            .filter { seen.add(it.lowercase()) }
            .take(limit).toList()
    }

    private fun matches(lower: String, word: String) = Regex("(^|[^a-z])" + Regex.escape(word)).containsMatchIn(lower)

    /** Whether our tool already covers a phrase, going by the words in it. */
    fun covered(toolId: String, phrase: String): Boolean {
        val l = phrase.lowercase()
        return covers[toolId].orEmpty().any { matches(l, it) }
    }

    /** What an app's declarations say it can do, in plain words. */
    fun declared(permissions: Collection<String>, widgets: List<String>, tiles: Int, shares: List<String>): List<String> = buildList {
        widgets.forEach { add("Home-screen widget: $it") }
        if (tiles > 0) add(if (tiles == 1) "A quick-settings tile" else "$tiles quick-settings tiles")
        shares.forEach { add("Accepts $it shared from other apps") }
        val p = permissions.map { it.substringAfterLast('.') }.toSet()
        if ("CAMERA" in p) add("Uses the camera")
        if ("RECORD_AUDIO" in p) add("Records from the microphone")
        if ("ACCESS_FINE_LOCATION" in p || "ACCESS_COARSE_LOCATION" in p) add("Uses your location")
        if ("SYSTEM_ALERT_WINDOW" in p) add("Floats over other apps")
        if ("FOREGROUND_SERVICE_MEDIA_PROJECTION" in p) add("Records or casts the screen")
        if ("BLUETOOTH_CONNECT" in p || "BLUETOOTH" in p) add("Connects to Bluetooth devices")
        if ("SCHEDULE_EXACT_ALARM" in p || "USE_EXACT_ALARM" in p) add("Sets alarms or schedules")
        if ("RECEIVE_BOOT_COMPLETED" in p) add("Starts by itself when the phone turns on")
        if ("REQUEST_INSTALL_PACKAGES" in p) add("Installs apps")
        if ("BILLING" in p) add("Has paid features")
    }

    /** Declared abilities our tools already have (by tool), so they are not listed as missing. */
    fun declaredCovered(toolId: String, text: String): Boolean = when {
        text.startsWith("Accepts videos") -> toolId in setOf("squeeze", "studio", "audio")
        text.startsWith("Accepts audio") -> toolId in setOf("audio", "studio")
        text == "Records from the microphone" -> toolId in setOf("sound-meter", "recorder", "prompter")
        text == "Uses the camera" -> toolId in setOf("prompter")
        text == "Has paid features" -> true
        text == "Installs apps" -> toolId == "pluck"
        else -> false
    }

    fun scan(pkg: String, name: String, version: String, toolId: String, strings: List<String>, declaredList: List<String>): GapScan {
        val words = phrases(strings).map { Gap(it, Gap.Kind.Words, covered(toolId, it)) }
        val decl = declaredList.map { Gap(it, Gap.Kind.Declared, declaredCovered(toolId, it)) }
        return GapScan(pkg, name, version, toolId, decl + words)
    }

    /**
     * A brief to paste into a coding chat: for each app, what to build (marked needed, or not yet reviewed), what was
     * already known to be missing, and what is not needed. Items marked "don't need" are listed once, so they are not
     * built by mistake.
     */
    fun brief(scans: List<GapScan>, known: Map<String, List<String>>, marks: Map<String, GapMark>, toolName: (String) -> String, date: String): String = buildString {
        appendLine("# Ultimate+: features to build so these apps can be uninstalled")
        appendLine("Found on the phone on $date by reading each app's own menus, settings and declarations. These are hints, not a full feature list.")
        scans.forEach { s ->
            val need = s.open.filter { marks[key(s.pkg, it.text)] == GapMark.Need }
            val unsure = s.open.filter { marks[key(s.pkg, it.text)] == null }
            val skip = s.open.filter { marks[key(s.pkg, it.text)] == GapMark.Skip }
            val knownMissing = known[s.pkg].orEmpty()
            appendLine()
            appendLine("## ${s.name} (${s.pkg}, v${s.version}) → ${toolName(s.toolId)}")
            if (knownMissing.isNotEmpty()) { appendLine("Already known missing:"); knownMissing.forEach { appendLine("- $it") } }
            if (need.isNotEmpty()) { appendLine("Needed:"); need.forEach { appendLine("- ${it.text}") } }
            if (unsure.isNotEmpty()) { appendLine("Not reviewed yet (check whether ${toolName(s.toolId)} does these):"); unsure.forEach { appendLine("- ${it.text}") } }
            if (skip.isNotEmpty()) appendLine("Not needed: " + skip.joinToString("; ") { it.text })
            if (need.isEmpty() && unsure.isEmpty() && knownMissing.isEmpty()) appendLine("Nothing left to build.")
        }
    }

    fun key(pkg: String, text: String) = "$pkg|$text"
}

/** Reads every string in an APK's resources.arsc: the global string pool holds all the text the app can show. */
object ArscStrings {
    fun read(b: ByteArray): List<String> {
        if (b.size < 12 || u16(b, 0) != 0x0002) return emptyList()
        val pool = u16(b, 2) // the table header is followed by its global string pool
        if (pool + 28 > b.size || u16(b, pool) != 0x0001) return emptyList()
        val count = u32(b, pool + 8)
        val utf8 = (u32(b, pool + 16) and 0x100) != 0
        val start = pool + u32(b, pool + 20)
        val offsets = pool + u16(b, pool + 2)
        if (count < 0 || offsets + count * 4L > b.size) return emptyList()
        return (0 until count).mapNotNull { i -> runCatching { at(b, start + u32(b, offsets + i * 4), utf8) }.getOrNull() }
    }

    private fun at(b: ByteArray, p0: Int, utf8: Boolean): String {
        var p = p0
        return if (utf8) {
            p += if (b[p].toInt() and 0x80 != 0) 2 else 1 // length in UTF-16 units, unused
            var n = b[p].toInt() and 0xFF
            if (n and 0x80 != 0) { n = ((n and 0x7F) shl 8) or (b[p + 1].toInt() and 0xFF); p += 2 } else p += 1
            String(b, p, n, Charsets.UTF_8)
        } else {
            var n = u16(b, p)
            if (n and 0x8000 != 0) { n = ((n and 0x7FFF) shl 16) or u16(b, p + 2); p += 4 } else p += 2
            String(b, p, n * 2, Charsets.UTF_16LE)
        }
    }

    private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, p: Int) = u16(b, p) or (u16(b, p + 2) shl 16)
}
