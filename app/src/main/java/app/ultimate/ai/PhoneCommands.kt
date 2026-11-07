package app.ultimate.ai

import java.util.Locale

/**
 * Plain phone controls read without a model, so they're instant and never mixed up: brightness, volume and the
 * flashlight, with a number or up / down / max / off. Anything else goes to Local AI. Also the sanity check for
 * Local AI's answers: a volume change for a sentence that never mentions sound is dropped. Pure, so it is tested.
 */
object PhoneCommands {
    fun parse(text: String): AiCall? {
        val t = text.lowercase(Locale.ROOT).trim().trimEnd('.', '!')
        fun pct(): Int? = Regex("(\\d{1,3})\\s*(%|percent)?").find(t)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
        val up = Regex("\\b(up|higher|brighter|louder|increase|raise|more)\\b").containsMatchIn(t)
        val down = Regex("\\b(down|lower|dimmer|dim|quieter|softer|decrease|reduce|less)\\b").containsMatchIn(t)
        val max = Regex("\\b(max|maximum|full|all the way up)\\b").containsMatchIn(t)
        val min = Regex("\\b(min|minimum|lowest|all the way down)\\b").containsMatchIn(t)
        if (Regex("\\b(brightness|bright|screen|dim|dimmer|brighter)\\b").containsMatchIn(t) && !t.contains("volume")) {
            val p = pct() ?: when { max -> 100; min -> 5; up -> -1; down -> -2; else -> return null }
            return AiCall("set_brightness", mapOf("percent" to p), 1.0)
        }
        if (Regex("\\b(volume|sound|louder|quieter|mute|unmute)\\b").containsMatchIn(t)) {
            val p = pct() ?: when { t.contains("unmute") -> 50; t.contains("mute") || min -> 0; max -> 100; up -> -1; down -> -2; else -> return null }
            return AiCall("set_volume", mapOf("percent" to p), 1.0)
        }
        if (Regex("\\b(flashlight|torch|flash light)\\b").containsMatchIn(t)) {
            val off = Regex("\\b(off|stop|disable)\\b").containsMatchIn(t)
            return AiCall("flashlight", mapOf("on" to !off), 1.0)
        }
        return null
    }

    /** Whether Local AI's [c] plausibly answers [text]; mismatches (volume for "brightness") are dropped. */
    fun plausible(c: AiCall, text: String): Boolean {
        val t = text.lowercase(Locale.ROOT)
        return when (c.name) {
            "set_volume" -> Regex("\\b(volume|sound|loud|quiet|music|mute)").containsMatchIn(t) && !t.contains("bright")
            "flashlight" -> Regex("\\b(flash|torch|light)").containsMatchIn(t)
            "set_brightness" -> Regex("\\b(bright|screen|dim)").containsMatchIn(t)
            else -> true
        }
    }
}
