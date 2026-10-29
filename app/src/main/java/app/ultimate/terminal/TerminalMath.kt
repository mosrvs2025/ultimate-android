package app.ultimate.terminal

/**
 * The shell side of Ultimate's own commands. Each command is a shell function that writes one request to the app's FIFO and
 * reads the answer from a FIFO of its own, so the commands work in every edition (no network socket, no INTERNET permission).
 * Pure, so the protocol and the shell script are tested.
 */
object Bridge {
    const val FIELD = '\u001f'
    const val RECORD = '\u001e'

    /** One request: where to answer, the shell's working folder, then the command and its arguments. */
    data class Request(val reply: String, val cwd: String, val args: List<String>)

    /** Splits the bytes read so far into whole requests and what is left of an unfinished one. */
    fun parse(buffer: String): Pair<List<Request>, String> {
        val parts = buffer.split(RECORD)
        val done = parts.dropLast(1).mapNotNull { rec ->
            val f = rec.split(FIELD).let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
            if (f.size < 2) null else Request(f[0], f[1], f.drop(2))
        }
        return done to parts.last()
    }

    /** Ultimate's commands, each a shell function calling the app. */
    val COMMANDS = listOf("battery", "storage", "notify", "open", "share", "ring", "squeeze", "toaudio", "transcribe", "pluck",
        "notifications", "torch", "vibrate", "myip")

    /** The mksh startup file: the bridge function, the commands, a colored prompt and a welcome line. */
    fun rc(fifo: String): String = buildString {
        appendLine("export ULT_FIFO='$fifo'")
        appendLine("_ult() { f=\"\$TMPDIR/ult.\$\$.\$RANDOM\"; mkfifo \"\$f\" || return 1; m=\$(printf '%s\\037' \"\$f\" \"\$PWD\" \"\$@\"); printf '%s\\036' \"\$m\" > \"\$ULT_FIFO\"; cat \"\$f\"; r=\$(cat \"\$f.rc\" 2>/dev/null); rm -f \"\$f\" \"\$f.rc\"; return \${r:-0}; }")
        appendLine("ult() { _ult \"\$@\"; }")
        appendLine("help() { _ult help \"\$@\"; }")
        COMMANDS.forEach { appendLine("$it() { _ult $it \"\$@\"; }") }
        // With no arguments and something piped in, clip copies it; otherwise it reads the clipboard history.
        appendLine("clip() { if [ \$# -eq 0 ] && [ ! -t 0 ]; then _ult clip set \"\$(cat)\"; else _ult clip \"\$@\"; fi; }")
        appendLine("alias ll='ls -lh' la='ls -lah' ..='cd ..'")
        // mksh: a leading "\\001\\r" makes \\001 mark the invisible color codes, so line editing counts the prompt's width right.
        appendLine("PS1=\$'\\001\\r\\001\\e[1;35m\\001'ult\$'\\001\\e[0m\\001':\$'\\001\\e[1;34m\\001''\${PWD##*/}'\$'\\001\\e[0m\\001'' \$ '")
        appendLine("echo \$'\\e[1mUltimate terminal\\e[0m  ·  type \\e[1;35mhelp\\e[0m for phone commands'")
    }

    /** Resolves a path typed in the shell against its working folder. */
    fun resolve(cwd: String, path: String): String = when {
        path.startsWith("/") -> path
        path == "~" || path.startsWith("~/") -> path // the shell expands ~ before we see it; kept as is otherwise
        else -> cwd.trimEnd('/') + "/" + path
    }
}

/** What a plain-English request most likely means as a command, offline, from a phrase table. */
data class Suggestion(val command: String, val explain: String)

object Suggest {
    private data class Rule(val words: List<List<String>>, val make: (String) -> List<Suggestion>)

    private val rules = listOf(
        Rule(listOf(listOf("big", "large", "huge", "biggest", "largest"), listOf("video", "movie"))) { _ ->
            listOf(Suggestion("find /sdcard -type f \\( -iname '*.mp4' -o -iname '*.mov' -o -iname '*.mkv' \\) -size +500M", "Videos over 500 MB"))
        },
        Rule(listOf(listOf("big", "large", "huge", "biggest", "largest"))) { _ ->
            listOf(Suggestion("find /sdcard -type f -size +200M 2>/dev/null", "Files over 200 MB"), Suggestion("storage", "Where your space goes"))
        },
        Rule(listOf(listOf("space", "storage", "free", "full", "disk"))) { _ ->
            listOf(Suggestion("storage", "Used and free space"), Suggestion("du -sh /sdcard/* 2>/dev/null | sort -h", "Size of each top folder"))
        },
        Rule(listOf(listOf("folder", "directory"), listOf("size", "big", "space"))) { _ ->
            listOf(Suggestion("du -sh * 2>/dev/null | sort -h", "Size of each folder here"))
        },
        Rule(listOf(listOf("battery", "charge", "charging"))) { _ -> listOf(Suggestion("battery", "Charge, state and temperature")) },
        Rule(listOf(listOf("ip", "address", "network"))) { _ -> listOf(Suggestion("myip", "This phone's addresses on your networks")) },
        Rule(listOf(listOf("clipboard", "copied", "clip"))) { _ ->
            listOf(Suggestion("clip list", "Your last copied things"), Suggestion("echo hello | clip", "Copy text from the terminal"))
        },
        Rule(listOf(listOf("notification", "notifications", "message", "messages"))) { q ->
            val word = q.split(' ').lastOrNull { it.length > 3 && it !in setOf("notification", "notifications", "message", "messages", "from", "with", "about", "show", "find", "search") }
            listOf(Suggestion("notifications" + (word?.let { " $it" } ?: ""), "Search kept notifications"))
        },
        Rule(listOf(listOf("torch", "flashlight", "flash", "light"))) { _ -> listOf(Suggestion("torch on", "Flashlight on"), Suggestion("torch off", "Flashlight off")) },
        Rule(listOf(listOf("shrink", "compress", "smaller", "squeeze"))) { _ -> listOf(Suggestion("squeeze video.mp4", "Make a video smaller (Squeeze)")) },
        Rule(listOf(listOf("audio", "mp3", "sound"), listOf("extract", "get", "convert", "from", "to"))) { _ -> listOf(Suggestion("toaudio video.mp4", "Save a video's sound as MP3")) },
        Rule(listOf(listOf("transcribe", "transcript", "text", "words"), listOf("audio", "video", "recording", "speech", "file"))) { _ -> listOf(Suggestion("transcribe recording.m4a", "Turn speech into text")) },
        Rule(listOf(listOf("photo", "photos", "pictures", "pics"), listOf("count", "many", "number"))) { _ -> listOf(Suggestion("ls /sdcard/DCIM/Camera | wc -l", "Count camera photos and videos")) },
        Rule(listOf(listOf("recent", "latest", "newest", "downloaded", "downloads"))) { _ -> listOf(Suggestion("ls -lt /sdcard/Download | head -20", "Newest downloads first")) },
        Rule(listOf(listOf("ring", "lost", "locate"))) { _ -> listOf(Suggestion("ring", "Ring at full volume for 30 seconds")) },
        Rule(listOf(listOf("apk", "backup", "save", "extract"), listOf("app"))) { _ -> listOf(Suggestion("pluck com.example.app", "Save an installed app as an APK")) },
        Rule(listOf(listOf("time", "uptime", "date", "running since"))) { _ -> listOf(Suggestion("uptime", "Time and how long since restart"), Suggestion("date", "Date and time")) },
        Rule(listOf(listOf("open", "browse", "website", "link", "url"))) { _ -> listOf(Suggestion("open https://example.com", "Open a link or file")) },
    )

    fun english(query: String): List<Suggestion> {
        val q = query.lowercase().replace(Regex("[^a-z0-9 .]"), " ").trim()
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(Regex("\\s+")).toSet()
        val out = rules.filter { r -> r.words.all { group -> group.any { w -> if (' ' in w) w in q else w in tokens || "${w}s" in tokens } } }.flatMap { it.make(q) }.toMutableList()
        // "find X" or "where is X": look for files named like it.
        Regex("(?:find|where is|where's|search for|look for)\\s+(?:my\\s+|the\\s+|a\\s+)?([a-z0-9._-]+)").find(q)?.let { m ->
            val name = m.groupValues[1]
            if (name !in setOf("big", "large", "biggest", "files", "file", "videos", "photos")) out += Suggestion("find /sdcard -iname '*$name*' 2>/dev/null | head -50", "Files named like \"$name\"")
        }
        return out.distinctBy { it.command }.take(5)
    }
}
