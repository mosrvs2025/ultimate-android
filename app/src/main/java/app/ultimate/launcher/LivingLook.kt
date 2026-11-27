package app.ultimate.launcher

/**
 * Living looks: rules that change the look by themselves. "Zen from 22:00 to 06:00", "Terminal while a focus session runs",
 * "Dusk once today's step goal is reached". The look you pick yourself stays your everyday look; a rule borrows another one while it
 * applies and gives it back after. Rules are checked top to bottom and the first that applies wins. All plain arithmetic, so it is tested
 * without a phone.
 */
sealed interface When {
    /** Between two times of day, in minutes after midnight. Wraps midnight when [fromMin] is later than [toMin] (22:00 to 06:00). */
    data class Between(val fromMin: Int, val toMin: Int) : When
    /** While a focus session is running (not paused). */
    data object Focusing : When
    /** From the moment today's step goal is reached until midnight. */
    data object GoalReached : When
}

/** [look] is a preset's id (`zen`) or a whole `ULOOK1:` code, so a look of your own can be used too. */
data class LookRule(val whenever: When, val look: String)

/** What the rules can look at, right now. */
data class Signals(val minuteOfDay: Int = 0, val focusing: Boolean = false, val goalReached: Boolean = false)

/** The settings to draw (the base ones with the active rule's look on top) and the rule that is wearing, if any. */
data class Living(val settings: LauncherSettings, val rule: LookRule?)

object LivingLook {
    const val MAX_RULES = 6
    const val DAY = 24 * 60

    fun applies(w: When, s: Signals): Boolean = when (w) {
        is When.Between -> {
            val m = s.minuteOfDay.mod(DAY)
            when {
                w.fromMin == w.toMin -> false
                w.fromMin < w.toMin -> m >= w.fromMin && m < w.toMin
                else -> m >= w.fromMin || m < w.toMin
            }
        }
        When.Focusing -> s.focusing
        When.GoalReached -> s.goalReached
    }

    /** The first rule that applies and whose look can be read, or null. */
    fun active(rules: List<LookRule>, s: Signals, base: LauncherSettings = LauncherSettings()): LookRule? =
        rules.firstOrNull { applies(it.whenever, s) && resolve(it.look, base) != null }

    /** [base] with [look] (a preset id or a share code) on top, or null if it isn't one. Only the look's parts change; apps, gestures and rules stay. */
    fun resolve(look: String, base: LauncherSettings): LauncherSettings? {
        val id = look.trim()
        Looks.all.firstOrNull { it.id == id }?.let { return it.apply(base) }
        return LookCode.apply(id, base)
    }

    fun effective(base: LauncherSettings, s: Signals): Living {
        val rule = active(base.rules, s, base) ?: return Living(base, null)
        return Living(resolve(rule.look, base) ?: base, rule)
    }

    // ---- storage

    fun encode(rules: List<LookRule>): String = rules.take(MAX_RULES).joinToString("\n") { r ->
        val w = when (val x = r.whenever) { is When.Between -> "t${x.fromMin}-${x.toMin}"; When.Focusing -> "focus"; When.GoalReached -> "goal" }
        "$w@${r.look.replace('\n', ' ')}"
    }

    /** Forgiving: a rule from a later version, or a damaged one, is dropped; the rest are kept. */
    fun decode(text: String?): List<LookRule> = text.orEmpty().lines().mapNotNull { line ->
        val at = line.indexOf('@')
        if (at <= 0 || at == line.lastIndex) return@mapNotNull null
        val w = line.substring(0, at)
        val look = line.substring(at + 1).trim()
        val whenever: When = when {
            w == "focus" -> When.Focusing
            w == "goal" -> When.GoalReached
            w.startsWith("t") -> {
                val p = w.drop(1).split('-')
                val from = p.getOrNull(0)?.toIntOrNull(); val to = p.getOrNull(1)?.toIntOrNull()
                if (p.size != 2 || from == null || to == null || from !in 0 until DAY || to !in 0 until DAY || from == to) return@mapNotNull null
                When.Between(from, to)
            }
            else -> return@mapNotNull null
        }
        LookRule(whenever, look)
    }.take(MAX_RULES)

    // ---- words

    fun clock(min: Int): String = "%02d:%02d".format(min.mod(DAY) / 60, min.mod(60))

    fun describe(w: When): String = when (w) {
        is When.Between -> "${clock(w.fromMin)} to ${clock(w.toMin)}"
        When.Focusing -> "While focusing"
        When.GoalReached -> "Step goal reached"
    }

    /** The name of a rule's look: a preset's name, "Your look" for a code. */
    fun lookName(look: String): String = Looks.all.firstOrNull { it.id == look.trim() }?.name ?: "Your look"

    /** A line for the Themes page about what is wearing right now, and until when. */
    fun status(living: Living): String? {
        val r = living.rule ?: return null
        val until = when (val w = r.whenever) { is When.Between -> " until ${clock(w.toMin)}"; When.Focusing -> " while you focus"; When.GoalReached -> " for the rest of today" }
        return "Right now: ${lookName(r.look)}$until."
    }

    /** One-tap starting points. */
    val suggestions: List<Pair<String, LookRule>> = listOf(
        "Zen at night (22:00 to 06:00)" to LookRule(When.Between(22 * 60, 6 * 60), "zen"),
        "Paper in the daytime (07:00 to 18:00)" to LookRule(When.Between(7 * 60, 18 * 60), "paper"),
        "Terminal while focusing" to LookRule(When.Focusing, "terminal"),
        "Dusk when the step goal is reached" to LookRule(When.GoalReached, "dusk"),
    )

    /** [rules] with [rule] added last (the lowest priority), unless it is already there or there is no room. */
    fun add(rules: List<LookRule>, rule: LookRule): List<LookRule> = if (rule in rules || rules.size >= MAX_RULES) rules else rules + rule

    fun move(rules: List<LookRule>, index: Int, by: Int): List<LookRule> {
        val to = index + by
        if (index !in rules.indices || to !in rules.indices) return rules
        return rules.toMutableList().also { val r = it.removeAt(index); it.add(to, r) }
    }
}
