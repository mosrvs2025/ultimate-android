package app.ultimate.clips

import org.json.JSONArray
import org.json.JSONObject

/** What a copied piece of text really is, so it can offer the one action that fits. Pure, so the rules are tested. */
object ClipSmart {
    enum class Kind(val label: String) { Json("JSON"), Url("Link"), Color("Colour"), Path("File path"), Command("Command"), Plain("Text") }

    private val url = Regex("""^(https?://|www\.)\S+$""", RegexOption.IGNORE_CASE)
    private val hex = Regex("""^#?([0-9a-fA-F]{6}|[0-9a-fA-F]{8}|[0-9a-fA-F]{3})$""")
    private val path = Regex("""^(/storage/|/sdcard/|/data/|~/|/home/|/tmp/|content://|file://)\S*$""")
    private val commands = setOf("ls", "cd", "cat", "grep", "find", "git", "adb", "ssh", "scp", "curl", "wget", "sudo", "apt", "pkg", "npm", "npx", "pip", "python", "python3", "node",
        "docker", "kubectl", "chmod", "chown", "mkdir", "rm", "cp", "mv", "echo", "export", "tar", "unzip", "ffmpeg", "gradle", "./gradlew", "make", "systemctl", "ping", "termux-setup-storage")

    fun kind(text: String): Kind {
        val t = text.trim()
        if (t.isEmpty()) return Kind.Plain
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) if (pretty(t) != null) return Kind.Json
        if ('\n' !in t && url.matches(t)) return Kind.Url
        if (hex.matches(t) && (t.startsWith("#") || t.any { it in "abcdefABCDEF" })) return Kind.Color
        if ('\n' !in t && path.matches(t)) return Kind.Path
        val first = t.removePrefix("$ ").substringBefore(' ').substringBefore('\n')
        if (first in commands || t.startsWith("$ ") || (first.startsWith("./") && first.length > 2)) return Kind.Command
        return Kind.Plain
    }

    /** The JSON laid out readably, or null if it is not valid JSON. */
    fun pretty(text: String): String? = runCatching { val t = text.trim(); if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2) }.getOrNull()

    /** The colour as 0xAARRGGBB, from #RGB, #RRGGBB or #AARRGGBB. */
    fun color(text: String): Long? {
        val h = text.trim().removePrefix("#")
        val full = when (h.length) { 3 -> "FF" + h.map { "$it$it" }.joinToString(""); 6 -> "FF$h"; 8 -> h; else -> return null }
        return full.toLongOrNull(16)
    }

    /** The command without a leading "$ " prompt. */
    fun command(text: String) = text.trim().removePrefix("$ ")
}
