package app.ultimate.rules

import org.json.JSONArray
import org.json.JSONObject

/** What a rule waits for. [stateful] triggers have two sides (plugged in and unplugged), so a rule can undo itself. */
enum class TriggerType(val label: String, val stateful: Boolean) {
    Charging("I plug in the charger", true),
    Wifi("I connect to Wi-Fi", true),
    Headphones("I connect headphones", true),
    BatteryBelow("the battery drops below a level", false),
    AtTime("it is a certain time", false),
    Notification("a notification arrives", false),
    Copied("I copy something", false),
}

/**
 * [on] picks the side of a stateful trigger (true: plugged in, connected; false: unplugged, disconnected). The other fields
 * are used by the types that need them: [percent] for the battery, [minutes] (since midnight) and [days] (Calendar weekday
 * numbers, empty for every day) for a time, [app] (a package, empty for any) and [contains] for notifications and copies.
 */
data class Trigger(
    val type: TriggerType,
    val on: Boolean = true,
    val percent: Int = 15,
    val minutes: Int = 22 * 60,
    val days: Set<Int> = emptySet(),
    val app: String = "",
    val contains: String = "",
)

/** What a rule does. [undoable] actions are put back the way they were when an "undo when it ends" rule ends. */
enum class ActionType(val label: String, val undoable: Boolean) {
    Notify("Show a notification", false),
    OpenLink("Open a link", false),
    OpenApp("Open an app", false),
    Volume("Set the media volume", true),
    Ringer("Set the ringer", true),
    Dnd("Do Not Disturb", true),
    Torch("Flashlight", true),
    Vibrate("Vibrate", false),
    Copy("Copy text", false),
    Speak("Say something out loud", false),
}

/**
 * [text] and [value] mean by type. Notify, Copy and Speak: [text] (variables allowed). OpenLink: [text] is the address.
 * OpenApp: [text] is the package. Volume: [value] is 0-100. Ringer: 0 silent, 1 vibrate, 2 normal. Dnd and Torch: 1 on, 0 off.
 * Vibrate: [value] is milliseconds.
 */
data class Action(val type: ActionType, val text: String = "", val value: Int = 0)

data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val trigger: Trigger,
    /** For stateful triggers: when the other side happens, put back what the undoable actions changed. */
    val undoWhenEnds: Boolean,
    val actions: List<Action>,
)

/** Something that happened on the phone. */
sealed interface Event {
    data class State(val type: TriggerType, val on: Boolean, val label: String = "") : Event
    data class Battery(val before: Int, val now: Int) : Event
    data class Time(val minutes: Int, val weekday: Int) : Event
    data class Notification(val pkg: String, val app: String, val title: String, val text: String) : Event
    data class Copied(val text: String) : Event
}

object RuleMatcher {
    /** True when [event] is what the rule waits for. */
    fun starts(rule: Rule, event: Event): Boolean {
        if (!rule.enabled) return false
        val t = rule.trigger
        return when (event) {
            is Event.State -> t.type == event.type && t.on == event.on
            is Event.Battery -> t.type == TriggerType.BatteryBelow && event.before >= t.percent && event.now < t.percent
            is Event.Time -> t.type == TriggerType.AtTime && t.minutes == event.minutes && (t.days.isEmpty() || event.weekday in t.days)
            is Event.Notification -> t.type == TriggerType.Notification && (t.app.isEmpty() || t.app == event.pkg) &&
                (t.contains.isEmpty() || event.title.contains(t.contains, true) || event.text.contains(t.contains, true))
            is Event.Copied -> t.type == TriggerType.Copied && (t.contains.isEmpty() || event.text.contains(t.contains, true))
        }
    }

    /** True when [event] is the other side of a stateful trigger the rule wants to undo. */
    fun ends(rule: Rule, event: Event): Boolean =
        rule.enabled && rule.undoWhenEnds && rule.trigger.type.stateful && event is Event.State && event.type == rule.trigger.type && event.on != rule.trigger.on
}

/** Fills %names in a text; a name that is not known stays as typed, so nothing silently disappears. */
object Vars {
    val NAMES = listOf("battery", "time", "date", "title", "text", "app", "clip")

    fun fill(template: String, values: Map<String, String>): String {
        var out = template
        values.keys.sortedByDescending { it.length }.forEach { k -> out = out.replace("%$k", values.getValue(k)) }
        return out
    }
}

/** Keeps a rule from firing in a loop: one run per rule every [perRuleMs], and at most [perMinute] runs overall. */
class RateLimiter(private val perRuleMs: Long = 10_000, private val perMinute: Int = 30) {
    private val last = HashMap<String, Long>()
    private val recent = ArrayDeque<Long>()

    @Synchronized fun allow(ruleId: String, now: Long): Boolean {
        while (recent.isNotEmpty() && now - recent.first() >= 60_000) recent.removeFirst()
        if (recent.size >= perMinute) return false
        if (now - (last[ruleId] ?: Long.MIN_VALUE / 2) < perRuleMs) return false
        last[ruleId] = now; recent.addLast(now)
        return true
    }
}

/** The rule in a sentence, the way it appears on its card. */
object Describe {
    private val DAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    fun time(minutes: Int) = "%d:%02d".format(minutes / 60, minutes % 60)

    fun days(days: Set<Int>): String = when {
        days.isEmpty() || days.size == 7 -> "every day"
        days == setOf(2, 3, 4, 5, 6) -> "on weekdays"
        days == setOf(1, 7) -> "on weekends"
        else -> "on " + days.sorted().joinToString(", ") { DAYS[(it - 1).coerceIn(0, 6)] }
    }

    fun trigger(t: Trigger, appName: (String) -> String = { it }): String = when (t.type) {
        TriggerType.Charging -> if (t.on) "I plug in the charger" else "I unplug the charger"
        TriggerType.Wifi -> if (t.on) "I connect to Wi-Fi" else "I leave Wi-Fi"
        TriggerType.Headphones -> if (t.on) "I connect headphones" else "I disconnect headphones"
        TriggerType.BatteryBelow -> "the battery drops below ${t.percent}%"
        TriggerType.AtTime -> "it is ${time(t.minutes)} ${days(t.days)}"
        TriggerType.Notification -> "a notification arrives" + (if (t.app.isNotEmpty()) " from ${appName(t.app)}" else "") + (if (t.contains.isNotEmpty()) " containing \"${t.contains}\"" else "")
        TriggerType.Copied -> "I copy something" + (if (t.contains.isNotEmpty()) " containing \"${t.contains}\"" else "")
    }

    fun action(a: Action, appName: (String) -> String = { it }): String = when (a.type) {
        ActionType.Notify -> "show \"${a.text}\""
        ActionType.OpenLink -> "open ${a.text}"
        ActionType.OpenApp -> "open ${appName(a.text)}"
        ActionType.Volume -> "set the volume to ${a.value}%"
        ActionType.Ringer -> when (a.value) { 0 -> "silence the phone"; 1 -> "set the phone to vibrate"; else -> "set the ringer to normal" }
        ActionType.Dnd -> if (a.value == 1) "turn on Do Not Disturb" else "turn off Do Not Disturb"
        ActionType.Torch -> if (a.value == 1) "turn on the flashlight" else "turn off the flashlight"
        ActionType.Vibrate -> "vibrate"
        ActionType.Copy -> "copy \"${a.text}\""
        ActionType.Speak -> "say \"${a.text}\""
    }

    fun rule(r: Rule, appName: (String) -> String = { it }): String {
        val does = r.actions.joinToString(", ") { action(it, appName) }.ifEmpty { "do nothing" }
        val undo = if (r.undoWhenEnds && r.trigger.type.stateful && r.actions.any { it.type.undoable }) ". Put back when " + (if (r.trigger.on) "it ends" else "it starts again") else ""
        return "When ${trigger(r.trigger, appName)}: $does$undo"
    }
}

object RuleJson {
    fun toJson(r: Rule): JSONObject = JSONObject().put("id", r.id).put("name", r.name).put("on", r.enabled).put("undo", r.undoWhenEnds)
        .put("t", JSONObject().put("type", r.trigger.type.name).put("state", r.trigger.on).put("pct", r.trigger.percent).put("min", r.trigger.minutes)
            .put("days", JSONArray(r.trigger.days.sorted())).put("app", r.trigger.app).put("has", r.trigger.contains))
        .put("a", JSONArray().apply { r.actions.forEach { put(JSONObject().put("type", it.type.name).put("text", it.text).put("v", it.value)) } })

    /** Null for a rule this version can't read (for example from a newer version), so one bad entry never loses the rest. */
    fun fromJson(o: JSONObject): Rule? = runCatching {
        val t = o.getJSONObject("t")
        val a = o.getJSONArray("a")
        val d = t.optJSONArray("days")
        Rule(
            o.getString("id"), o.optString("name"), o.optBoolean("on", true),
            Trigger(TriggerType.valueOf(t.getString("type")), t.optBoolean("state", true), t.optInt("pct", 15), t.optInt("min", 22 * 60),
                if (d == null) emptySet() else List(d.length()) { d.getInt(it) }.toSet(), t.optString("app"), t.optString("has")),
            o.optBoolean("undo", false),
            List(a.length()) { i -> a.getJSONObject(i).let { Action(ActionType.valueOf(it.getString("type")), it.optString("text"), it.optInt("v")) } },
        )
    }.getOrNull()
}

/** A run of a rule, kept so you can see what happened and why. */
data class RunRecord(val at: Long, val ruleId: String, val ruleName: String, val because: String, val result: String)

/** When a time rule next runs. Pure, with the zone passed in, so day and daylight-saving edges are tested. */
object Schedule {
    /** The next moment after [now] at [minutes] since midnight on one of [days] (Calendar weekday numbers; empty means every day). */
    fun next(now: Long, minutes: Int, days: Set<Int>, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long {
        val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = now }
        repeat(9) { i ->
            val c = (cal.clone() as java.util.Calendar).apply {
                add(java.util.Calendar.DAY_OF_YEAR, i)
                set(java.util.Calendar.HOUR_OF_DAY, minutes / 60); set(java.util.Calendar.MINUTE, minutes % 60)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis > now && (days.isEmpty() || c.get(java.util.Calendar.DAY_OF_WEEK) in days)) return c.timeInMillis
        }
        return now + 24 * 3_600_000L // unreachable for valid input; never schedules in the past
    }
}

/** Which rule types need Ultimate to be watching the phone live (the others are fed by things that already run). */
object RuleKinds {
    val LIVE = setOf(TriggerType.Charging, TriggerType.Wifi, TriggerType.Headphones, TriggerType.BatteryBelow)
}
