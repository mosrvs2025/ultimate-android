package app.ultimate.rules

/**
 * Turns a sentence like "when I plug in the charger, set the volume to 30 and turn on do not disturb" into a rule, offline,
 * from a phrase table. It says plainly when it did not understand, instead of guessing. [appPackage] finds an installed app
 * by the name the person typed.
 */
object RuleParser {
    sealed interface Result {
        data class Ok(val rule: Rule, val notes: List<String> = emptyList()) : Result
        data class Unclear(val why: String) : Result
    }

    private class Found(val trigger: Trigger, val end: Int)

    fun parse(input: String, appPackage: (String) -> String? = { null }, newId: () -> String = { java.util.UUID.randomUUID().toString() }): Result {
        val text = input.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return Result.Unclear("Type what should happen, like \"when I plug in the charger, turn on Do Not Disturb\".")
        val lower = text.lowercase().replace("’", "'")
        val found = trigger(lower, appPackage) ?: return Result.Unclear("I couldn't tell when this should happen. Start with \"when I plug in the charger\", \"when the battery drops below 20%\", \"at 10pm\", \"when I connect headphones\" or \"when a notification arrives from …\".")
        val rest = lower.substring(found.end).trimStart(' ', ',', ':', ';', '-', '>')
        // Lowercasing normally keeps the length; if it ever does not, fall back to the lowercase text (only capitals are lost).
        val originalRest = (if (text.length == lower.length) text.substring(found.end) else rest).trimStart(' ', ',', ':', ';', '-', '>')
        val chunks = rest.split(Regex(",| then | and then | and (?=(?:turn|set|open|show|notify|remind|mute|silence|unmute|vibrate|copy|say|speak|read|switch|put|flash|disable|enable|lower|raise)\\b)|;")).map { it.trim() }.filter { it.isNotEmpty() }
        val originalChunks = originalRest.split(Regex(",| then | and then | and (?=(?:turn|set|open|show|notify|remind|mute|silence|unmute|vibrate|copy|say|speak|read|switch|put|flash|disable|enable|lower|raise)\\b)|;", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }
        val notes = ArrayList<String>()
        // "…and put it back when I disconnect" is not an action; it only says the rule undoes itself (found below).
        val undoCue = Regex("^(?:put (?:it|them|everything|things) back|undo(?: it| that)?|restore(?: it| them)?|go back to normal|back to normal)\\b")
        val actions = chunks.mapIndexedNotNull { i, c ->
            if (undoCue.containsMatchIn(c)) return@mapIndexedNotNull null
            action(c, originalChunks.getOrNull(i) ?: c, appPackage).also { if (it == null) notes += "I didn't understand \"$c\", so I left it out." }
        }
        if (actions.isEmpty()) return Result.Unclear("I understood when, but not what to do. Try \"…, turn on Do Not Disturb\", \"…, set the volume to 30%\", \"…, show a notification saying …\" or \"…, open Spotify\".")
        val undo = found.trigger.type.stateful && actions.any { it.type.undoable } && Regex("\\b(while|until|undo|restore|put (?:it|them|everything|things) back|back to normal|afterwards?)\\b").containsMatchIn(lower)
        val name = text.replaceFirstChar { it.uppercase() }.take(48)
        return Result.Ok(Rule(newId(), name, true, found.trigger, undo, actions), notes)
    }

    private fun clock(m: MatchResult): Int? {
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
        when (m.groupValues[3]) { "pm" -> if (h < 12) h += 12; "am" -> if (h == 12) h = 0 }
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    private class Cand(val start: Int, val found: Found)

    /** Every trigger the sentence could mean; the one that comes first in the sentence wins, because actions mention things too. */
    private fun trigger(s: String, appPackage: (String) -> String?): Found? {
        val c = ArrayList<Cand>()
        fun add(m: MatchResult, t: Trigger, end: Int = m.range.last + 1) { c += Cand(m.range.first, Found(t, end)) }
        fun hit(re: String) = Regex(re).find(s)
        // Headphones come before the charger: "plug in headphones" is not "plug in".
        hit("\\b(?:dis|un)(?:connect|plug)(?:s|ed)?(?: my| the)? (?:headphones|earbuds|headset|airpods|earphones)\\b|\\b(?:dis|un)(?:connect|plug)(?:g?ed|s)?(?: my| the)? (?:headphones|earbuds|headset|airpods|earphones)\\b")?.let { add(it, Trigger(TriggerType.Headphones, on = false)) }
        hit("\\b(?:connect|plug|put)(?:g?ed|s)?(?: in)?(?: my| the)? (?:headphones|earbuds|headset|airpods|earphones)(?: in)?\\b")?.let { add(it, Trigger(TriggerType.Headphones, on = true)) }
        hit("\\b(?:un-?plug(?:g?ed)?|stop(?:s)? charging|disconnect(?:s|ed)? (?:the |my )?charger)(?: (?:the |my )?(?:charger|cable|phone))?\\b")?.let { add(it, Trigger(TriggerType.Charging, on = false)) }
        hit("\\b(?:plug(?:ged|s)?(?: it| my phone| the phone)? in|start(?:s)? charging|connect(?:s|ed)? (?:the |my )?charger|(?:put|set)(?: my| the)? phone (?:on|to) (?:the )?charg(?:er|ing)|charging)(?: (?:the |my |a )?(?:charger|cable|power))?\\b")?.let { add(it, Trigger(TriggerType.Charging, on = true)) }
        // Wi-Fi
        hit("\\b(?:dis)?connect(?:s|ed)? (?:from|to) (?:[a-z0-9']+ ){0,2}wi-?fi\\b|\\b(?:leave|leaves|left) (?:the )?wi-?fi\\b|\\bjoin(?:s|ed)? (?:the )?wi-?fi\\b")?.let {
            add(it, Trigger(TriggerType.Wifi, on = !Regex("\\bdisconnect|\\bleave|\\bleft").containsMatchIn(it.value)))
        }
        // Battery
        hit("battery[a-z ]*?(?:below|under|drops? (?:below|to|under)|less than|falls? (?:below|to)|hits|reaches|is at)\\s*(\\d{1,3})\\s*(?:%|percent)?")?.let { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it in 1..99 }?.let { add(m, Trigger(TriggerType.BatteryBelow, percent = it)) }
        }
        // Time of day: a clock time wins over a word like "night", but both count as the start of the trigger.
        val days = when {
            Regex("\\bweekdays?\\b|\\bmonday to friday\\b").containsMatchIn(s) -> setOf(2, 3, 4, 5, 6)
            Regex("\\bweekends?\\b").containsMatchIn(s) -> setOf(1, 7)
            else -> emptySet()
        }
        val clockAt = hit("\\bat (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b")?.let { m -> clock(m)?.let { m to it } }
        val part = hit("\\b(?:every |each |in the )?(night|bedtime|morning|evening|afternoon|noon|midnight)\\b")
        if (clockAt != null) c += Cand(minOf(clockAt.first.range.first, part?.range?.first ?: Int.MAX_VALUE), Found(Trigger(TriggerType.AtTime, minutes = clockAt.second, days = days), clockAt.first.range.last + 1))
        else if (part != null) {
            val mins = when (part.groupValues[1]) { "night", "bedtime" -> 22 * 60; "morning" -> 7 * 60; "evening" -> 18 * 60; "afternoon" -> 14 * 60; "noon" -> 12 * 60; else -> 0 }
            add(part, Trigger(TriggerType.AtTime, minutes = mins, days = days))
        }
        // Notifications: only when the sentence is about receiving one (the word also appears in actions like "show a notification").
        hit("\\b(?:a |any |an |new )?(?:notification|message|text)s?(?: arrives?| comes? in| appears?)?(?: from ([a-z0-9 ]+?))?(?: (?:that )?(?:contain(?:s|ing)?|with the word|saying|mentions?) \"?([^\",]+?)\"?)?(?=,| then | and |$)")?.let { m ->
            val about = Regex("^(when|whenever|if|every time|each time|once|as soon as)\\b").containsMatchIn(s) || Regex("\\b(get|got|receive|arrives?)\\b").containsMatchIn(s)
            if (!about) return@let
            val appName = m.groupValues[1].trim()
            val pkg = if (appName.isEmpty()) "" else appPackage(appName) ?: return@let
            add(m, Trigger(TriggerType.Notification, app = pkg, contains = m.groupValues[2].trim()))
        }
        hit("\\bi (?:copy|copied) (?:a |some |any )?(link|url|something|text|[a-z0-9 ]+?)(?=,| then | and |$)")?.let { m ->
            val has = when (val what = m.groupValues[1]) { "link", "url" -> "http"; "something", "text", "some" -> ""; else -> what.trim() }
            add(m, Trigger(TriggerType.Copied, contains = has))
        }
        return c.minByOrNull { it.start }?.found
    }

    private fun action(chunk: String, original: String, appPackage: (String) -> String?): Action? {
        fun has(re: String) = Regex(re).containsMatchIn(chunk)
        val off = has("\\b(off|disable|stop|end)\\b")
        quoted(original)?.let { q ->
            when {
                has("^(show|send|give|post|display)\\b.*\\b(notification|alert|message)\\b|^notify\\b|^remind\\b") -> return Action(ActionType.Notify, q)
                has("^(say|speak|read)\\b") -> return Action(ActionType.Speak, q)
                has("^copy\\b") -> return Action(ActionType.Copy, q)
            }
        }
        Regex("\\bvolume\\b.*?(\\d{1,3})\\s*(?:%|percent)?|(\\d{1,3})\\s*(?:%|percent)\\s*volume").find(chunk)?.let { m ->
            val v = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() ?: return null
            return Action(ActionType.Volume, value = v.coerceIn(0, 100))
        }
        if (has("\\bmute\\b") && !has("phone|ringer")) return Action(ActionType.Volume, value = 0)
        if (has("do not disturb|don't disturb|\\bdnd\\b|\\bquiet mode\\b|\\bfocus mode\\b")) return Action(ActionType.Dnd, value = if (off) 0 else 1)
        if (has("flash ?light|\\btorch\\b")) return Action(ActionType.Torch, value = if (off) 0 else 1)
        if (has("\\bvibrate mode\\b|\\bon vibrate\\b|\\bto vibrate\\b")) return Action(ActionType.Ringer, value = 1)
        if (has("\\b(silence|silent|mute)\\b")) return Action(ActionType.Ringer, value = 0)
        if (has("\\b(unmute|ring normally|ringer on|normal mode|ring mode)\\b")) return Action(ActionType.Ringer, value = 2)
        if (has("^vibrate\\b|\\bvibrate\\b")) return Action(ActionType.Vibrate, value = 400)
        Regex("^(?:open|launch|start)\\s+((?:https?://|www\\.)\\S+|[a-z0-9-]+\\.[a-z]{2,}(?:/\\S*)?)").find(chunk)?.let { m ->
            val url = m.groupValues[1]
            return Action(ActionType.OpenLink, if (url.startsWith("http")) url else "https://$url")
        }
        Regex("^(?:open|launch|start)\\s+(?:the\\s+)?([a-z0-9 ]+?)(?: app)?$").find(chunk)?.let { m ->
            val pkg = appPackage(m.groupValues[1].trim()) ?: return null
            return Action(ActionType.OpenApp, pkg)
        }
        Regex("^(?:show|send|give|post|display)\\b.*\\b(?:notification|alert|message)\\b.*?\\b(?:saying|that says|reading|with the text)\\s+(.+)$", RegexOption.IGNORE_CASE).find(original)?.let { return Action(ActionType.Notify, it.groupValues[1].trim()) }
        Regex("^(?:notify me|remind me)(?: to| that| about)?\\s*(.*)").find(chunk)?.let { m ->
            val t = m.groupValues[1].trim()
            return Action(ActionType.Notify, if (t.isEmpty()) "A rule you made ran" else if (chunk.startsWith("remind")) "Reminder: $t" else t)
        }
        if (has("^(?:show|send|give) me a notification|^show a notification")) return Action(ActionType.Notify, "A rule you made ran")
        Regex("^(?:say|speak|read out|read)\\s+(.+)").find(chunk)?.let { return Action(ActionType.Speak, it.groupValues[1].trim()) }
        return null
    }

    private fun quoted(s: String): String? = Regex("[\"“']([^\"”']{1,200})[\"”']").find(s)?.groupValues?.get(1)
}
