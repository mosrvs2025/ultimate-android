package app.ultimate.home

/** Something the app can do, offered for a file handed to it or for words typed into Home's bar. */
data class Action(val id: String, val label: String, val detail: String, val toolId: String? = null, val shareClean: Boolean = false)

/**
 * Turns "what is this" and "what do you want" into ranked actions. Pure, so the matching is tested and works offline;
 * actions for tools this edition lacks are dropped by [available].
 */
object Commands {
    private val video = listOf(
        Action("squeeze", "Make it smaller", "Squeeze · keeps it sharp", "squeeze"),
        Action("studio", "Edit it", "Studio · trim, captions, music", "studio"),
        Action("audio", "Pull the sound out", "Video to audio · MP3, M4A or WAV", "audio"),
        Action("clean", "Send without location", "Share clean · nothing re-encoded", shareClean = true),
        Action("send", "Send to my computer", "Over your Wi-Fi, no cloud", "send"),
    )
    private val image = listOf(
        Action("photos", "Resize or crop", "Photos · smaller files, no location", "photos"),
        Action("clean", "Send without location", "Share clean · same pixels", shareClean = true),
        Action("send", "Send to my computer", "Over your Wi-Fi, no cloud", "send"),
    )
    private val audio = listOf(
        Action("audio", "Convert, trim or clean up", "Video to audio · pitch, EQ, fades", "audio"),
        Action("studio", "Use as a soundtrack", "Studio", "studio"),
        Action("send", "Send to my computer", "Over your Wi-Fi, no cloud", "send"),
    )

    fun forMime(mime: String): List<Action> = when {
        mime.startsWith("video/") -> video
        mime.startsWith("image/") -> image
        mime.startsWith("audio/") -> audio
        else -> listOf(Action("send", "Send to my computer", "Over your Wi-Fi, no cloud", "send"))
    }

    /** Keyword families for typed requests; each family's first match wins, so order is ranking. */
    private val phrases: List<Pair<List<String>, Action>> = listOf(
        listOf("smaller", "compress", "shrink", "squeeze", "size", "mb", "space", "storage") to Action("squeeze", "Make a video smaller", "Squeeze", "squeeze"),
        listOf("edit", "trim video", "cut video", "caption", "subtitle", "tiktok", "reel", "short", "montage", "film") to Action("studio", "Edit a video", "Studio", "studio"),
        listOf("mp3", "audio", "sound out", "music from", "ringtone", "podcast", "pitch", "bass", "wav", "m4a", "convert") to Action("audio", "Convert or edit audio", "Video to audio", "audio"),
        listOf("file", "folder", "browse", "download", "install", "apk file", "unzip", "move file", "rename") to Action("files", "Browse my files", "Files", "files"),
        listOf("clean", "duplicate", "free up", "cleanup", "wipe", "swipe", "declutter", "storage full", "old photos", "similar") to Action("cleanup", "Clean up my phone", "Clean up", "cleanup"),
        listOf("notification", "unsent", "deleted message", "missed", "who messaged", "notif") to Action("inbox", "Find an old notification", "Notification history", "inbox"),
        listOf("clipboard", "copied", "paste", "clip") to Action("clips", "Find something I copied", "Clipboard", "clips"),
        listOf("record", "voice", "memo", "dictate", "microphone") to Action("recorder", "Record something", "Recorder", "recorder"),
        listOf("photo", "picture", "image", "crop", "resize", "jpeg", "webp", "exif") to Action("photos", "Fix up photos", "Photos", "photos"),
        listOf("location", "gps", "metadata", "private", "anonymous") to Action("photos-clean", "Remove location from photos", "Photos · or Share clean from any app", "photos"),
        listOf("script", "teleprompter", "prompter", "read while", "film myself") to Action("prompter", "Read a script on camera", "Teleprompter", "prompter"),
        listOf("scan", "check", "security", "privacy", "permission", "spy", "safe", "virus", "health") to Action("checkup", "Check my phone", "Phone checkup", "checkup"),
        listOf("apk", "extract", "backup app", "pluck", "tracker", "ads in") to Action("pluck", "Save or inspect an app", "Pluck", "pluck"),
        listOf("loud", "noise", "decibel", "db", "sound level") to Action("sound-meter", "Measure how loud it is", "Sound meter", "sound-meter"),
        listOf("compass", "level", "sensor", "tilt", "light", "pressure", "altitude", "magnet", "metal") to Action("sensors", "Use the phone's sensors", "Sensors", "sensors"),
        listOf("wifi", "network", "devices", "router", "who is on", "lan", "ip") to Action("lan", "See what's on my Wi-Fi", "Network scanner", "lan"),
        listOf("traffic", "tracking", "connections", "which servers", "data usage") to Action("traffic", "See what apps connect to", "Traffic inspector", "traffic"),
        listOf("screen time", "time spent", "addicted", "limit", "usage", "how long") to Action("screentime", "See where my time goes", "Screen time", "screentime"),
        listOf("computer", "pc", "laptop", "transfer", "send to", "mac") to Action("send", "Send files to my computer", "Send to computer", "send"),
        listOf("recipe", "automate", "automatic", "every time", "whenever", "chain") to Action("recipes", "Automate something", "Recipes", "recipes"),
    )

    /** Best matches for typed words, most relevant first. */
    fun search(query: String): List<Action> {
        val q = query.lowercase().trim()
        if (q.length < 2) return emptyList()
        return phrases.mapNotNull { (words, action) ->
            val score = words.sumOf { w -> if (q.contains(w)) w.length * 2 else if (w.startsWith(q) || action.label.lowercase().contains(q)) 1 else 0 }
            if (score > 0) action to score else null
        }.sortedByDescending { it.second }.map { it.first }.distinctBy { it.toolId }
    }

    fun available(actions: List<Action>, toolIds: Set<String>, shareClean: Boolean) =
        actions.filter { (it.toolId == null || it.toolId in toolIds) && (!it.shareClean || shareClean) }
}
