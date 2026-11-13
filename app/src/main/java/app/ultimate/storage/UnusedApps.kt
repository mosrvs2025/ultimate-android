package app.ultimate.storage

/** An installed app (not a system app, not Ultimate) with what the phone knows about when it was last used. */
data class InstalledApp(val pkg: String, val label: String, val sizeBytes: Long, val lastUsedAt: Long, val installedAt: Long, val coveredBy: String?)

/** An app offered for removal, and why. [atLeast] means the phone's record only reaches back [UnusedApps.WINDOW_DAYS] days, so it has been idle at least that long. */
data class Droppable(val app: InstalledApp, val idleDays: Long, val atLeast: Boolean, val reason: String, val careful: Boolean)

/**
 * Which apps are worth offering to remove. Usage history is only read back [WINDOW_DAYS] days, so an app with no use in that window has been idle
 * "at least" that long, not "for 400 days"; an app installed more recently than that has simply not had the chance to be used, and is judged
 * from its install date. Apps that hold something that cannot be recovered (a code generator, a wallet, a messenger with a local history, a money
 * app) are offered with a warning and never preselected. Without usage access nothing can be said, so nothing is offered. Pure, so it is tested.
 */
object UnusedApps {
    const val DAY = 86_400_000L
    const val WINDOW_DAYS = 60L

    /** Days idle, and whether that is a lower bound. */
    fun idle(a: InstalledApp, now: Long): Pair<Long, Boolean> {
        if (a.lastUsedAt > 0) return ((now - a.lastUsedAt) / DAY).coerceAtLeast(0) to false
        val sinceInstall = ((now - a.installedAt) / DAY).coerceAtLeast(0)
        return if (sinceInstall >= WINDOW_DAYS) WINDOW_DAYS to true else sinceInstall to false
    }

    private val precious = Regex("authenticat|wallet|\\b2fa\\b|\\botp\\b|vault|password|bank|secure folder|signal|proton|crypto|paypal|cash app|chime|venmo|varo|remitly|kalshi|\\bpay\\b", RegexOption.IGNORE_CASE)
    /** Apps that might hold codes, messages or money that cannot be got back by installing the app again. */
    fun careful(label: String, pkg: String) = precious.containsMatchIn("$label $pkg")

    fun candidates(apps: List<InstalledApp>, now: Long, usageKnown: Boolean, minIdleDays: Long = 30): List<Droppable> {
        if (!usageKnown) return emptyList()
        return apps.mapNotNull { a ->
            val (days, atLeast) = idle(a, now)
            val covered = a.coveredBy != null
            // An app that an Ultimate tool already does is offered once it has not been opened for a week; any other after a month.
            if (days < (if (covered) 7 else minIdleDays)) return@mapNotNull null
            val reason = when {
                covered -> "${a.coveredBy} in Ultimate does this" + if (days >= 7) " · not opened in ${if (atLeast) "at least " else ""}$days days" else ""
                else -> "Not opened in ${if (atLeast) "at least " else ""}$days days"
            }
            Droppable(a, days, atLeast, reason, careful(a.label, a.pkg))
        }.sortedWith(compareByDescending<Droppable> { it.app.coveredBy != null }.thenByDescending { it.atLeast || it.idleDays >= WINDOW_DAYS }.thenByDescending { it.app.sizeBytes }.thenByDescending { it.idleDays })
    }

    /** The apps "select all" picks: long idle and not the careful kind. */
    fun suggested(list: List<Droppable>) = list.filter { !it.careful && (it.app.coveredBy != null || it.atLeast || it.idleDays >= WINDOW_DAYS) }
    fun bytes(list: List<Droppable>) = list.sumOf { it.app.sizeBytes }
}
