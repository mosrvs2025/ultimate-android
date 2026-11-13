package app.ultimate.insights

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How one tool has been used on this phone. */
data class ToolUse(val id: String, val title: String, val opens: Int, val minutes: Long, val lastAt: Long, val results: Int, val resultBytes: Long,
    val quickVisits: Int = 0, val jobsOk: Int = 0, val jobsFailed: Int = 0, val jobMs: Long = 0)

/** Another app on the phone, for spotting what Ultimate could absorb next. */
data class OtherApp(val label: String, val packageName: String, val category: String, val minutes7d: Long?, val lastUsedAt: Long?, val coveredBy: String?)

/** A crash Ultimate caught, kept on the phone so it can be reported. */
data class Crash(val at: Long, val version: String, val type: String, val message: String?, val frames: List<String>)

data class InsightsInput(
    val version: String,
    val edition: String,
    val device: String,
    val android: String,
    val generatedAt: Long,
    val tools: List<ToolUse>,
    val apps: List<OtherApp>?,
    val usageAccess: Boolean,
    val crashes: List<Crash>,
    val notes: String,
    /** When Ultimate started counting tool use. */
    val trackingSince: Long = 0,
    /** Feature uses inside tools, by name. */
    val features: Map<String, Int> = emptyMap(),
    val problems: List<String> = emptyList(),
    val unmatched: List<String> = emptyList(),
    /** Label to value: permissions, speech, codecs, screen, automations. */
    val setup: List<Pair<String, String>> = emptyList(),
)

/**
 * Something that went wrong, has been fixed, and so should not be reported as open. [matches] recognises it from the exception type and
 * its text (message and stack frames); [fixedIn] is the first version without it. Only fixes that were checked in the code belong here:
 * a problem that was "fixed" and still turned up afterwards is not listed (see the install-session entry in the notes, deliberately absent).
 */
class KnownFix(val name: String, val fixedIn: String, val matches: (type: String, text: String) -> Boolean)

object KnownFixes {
    val all = listOf(
        KnownFix("Studio timeline ruler: a negative width when trimming", "1.50.0") { _, t -> "must be >= than minWidth" in t && "maxWidth(" in t },
        KnownFix("Notification list: the same record twice crashed the list", "1.89.0") { _, t -> "was already used" in t && "LazyColumn" in t },
    )

    fun find(c: Crash): KnownFix? { val text = c.type + " " + (c.message ?: "") + " " + c.frames.joinToString(" "); return all.firstOrNull { it.matches(c.type, text) } }

    /** Negative when [a] is an older version than [b]: compares the numbers in "1.49.1" and "2.8.0" part by part. */
    fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').mapNotNull { it.toIntOrNull() }; val y = b.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (k in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(k) { 0 } - y.getOrElse(k) { 0 }; if (d != 0) return d }
        return 0
    }
}

/** Messages that are not problems: work that was cancelled on purpose (a screen closed, an install declined) and so says nothing about what to fix. */
object ProblemNoise {
    private val quiet = listOf("left the composition", "Install cancelled", "was cancelled", "Job was cancelled")
    fun isNoise(message: String) = quiet.any { it in message }
}

/** Turns what the phone knows about Ultimate's use into Markdown an AI (or a person) can plan the next version from. */
object InsightsReport {
    const val PROMPT = "This is a usage report from my Ultimate Android app (a personal, ad-free, offline-first super app). " +
        "Using it, suggest which new tools to build into Ultimate next (especially ones that would let me uninstall the apps listed), " +
        "what to improve in the tools I use most, and fixes for any crashes. Rank by impact."

    fun markdown(i: InsightsInput): String = buildString {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        appendLine("# Ultimate usage report")
        appendLine()
        appendLine("- Version: ${i.version} (${i.edition})")
        appendLine("- Device: ${i.device}, Android ${i.android}")
        appendLine("- Generated: ${day.format(Date(i.generatedAt))}")
        appendLine()
        appendLine("## Tools")
        appendLine()
        if (i.trackingSince > 0) {
            val days = ((i.generatedAt - i.trackingSince) / 86_400_000L).coerceAtLeast(0)
            appendLine("Counting since ${day.format(Date(i.trackingSince))} ($days day${if (days == 1L) "" else "s"}). Results include work from before then.")
            appendLine()
        }
        appendLine("| Tool | Opens | Quick visits | Minutes | Last used | Results | Jobs ok/failed | Avg job |")
        appendLine("|---|---:|---:|---:|---|---:|---|---:|")
        i.tools.sortedWith(compareByDescending<ToolUse> { it.minutes }.thenByDescending { it.opens }).forEach { t ->
            val jobs = t.jobsOk + t.jobsFailed
            appendLine("| ${cell(t.title)} | ${t.opens} | ${t.quickVisits} | ${t.minutes} | ${if (t.lastAt > 0) day.format(Date(t.lastAt)) else "never"} | ${t.results} | " +
                (if (jobs == 0) "–" else "${t.jobsOk}/${t.jobsFailed}") + " | " + (if (jobs == 0) "–" else "${t.jobMs / jobs / 1000}s") + " |")
        }
        appendLine("\nQuick visit = opened and left within ${app.ultimate.insights.InsightsStore.QUICK_MS / 1000} seconds.")
        val unused = i.tools.filter { it.opens == 0 }
        if (unused.isNotEmpty() && i.tools.any { it.opens > 0 }) appendLine("\nNever opened: ${unused.joinToString { it.title }}.")
        appendLine()
        if (i.notes.isNotBlank()) {
            appendLine("## My notes")
            appendLine()
            appendLine(i.notes.trim())
            appendLine()
        }
        if (i.features.isNotEmpty()) {
            appendLine("## Features used")
            appendLine()
            i.features.entries.sortedByDescending { it.value }.take(40).forEach { (k, v) -> appendLine("- $k: $v") }
            appendLine()
        }
        if (i.unmatched.isNotEmpty()) {
            appendLine("## Searched for, found nothing")
            appendLine()
            i.unmatched.forEach { appendLine("- \"${cell(it)}\"") }
            appendLine()
        }
        appendLine("## Problems shown")
        appendLine()
        val shown = i.problems.filterNot { ProblemNoise.isNoise(it) }
        if (shown.isEmpty()) appendLine("None recorded.") else shown.forEach { appendLine("- ${cell(it)}") }
        appendLine()
        if (i.setup.isNotEmpty()) {
            appendLine("## Setup")
            appendLine()
            i.setup.forEach { (k, v) -> appendLine("- $k: $v") }
            appendLine()
        }
        appendLine("## Crashes")
        appendLine()
        crashSection(i)
        appendLine()
        if (i.apps != null) {
            appendLine("## My other apps")
            appendLine()
            if (!i.usageAccess) appendLine("Usage access is off, so time spent is unknown.\n")
            val (covered, rest) = i.apps.partition { it.coveredBy != null }
            if (covered.isNotEmpty()) {
                appendLine("Already covered by an Ultimate tool:")
                covered.forEach { appendLine("- ${it.label} → ${it.coveredBy}${it.minutes7d?.let { m -> " (${m} min this week)" } ?: ""}") }
                appendLine()
            }
            appendLine("| App | Category | Min / 7 days | Last used |")
            appendLine("|---|---|---:|---|")
            // Apps with no time this week only matter as names, so they share one line instead of a row each.
            val (used, idle) = if (i.usageAccess) rest.partition { (it.minutes7d ?: 0) > 0 } else rest to emptyList()
            used.sortedWith(compareByDescending<OtherApp> { it.minutes7d ?: -1 }.thenBy { it.label.lowercase() }).forEach { a ->
                val last = a.lastUsedAt?.let { if (it > 0) day.format(Date(it)) else "not in 60 days" } ?: "unknown"
                appendLine("| ${cell(a.label)} | ${a.category} | ${a.minutes7d ?: "?"} | $last |")
            }
            if (idle.isNotEmpty()) {
                val (recent, stale) = idle.partition { (it.lastUsedAt ?: 0) > 0 }
                appendLine()
                if (recent.isNotEmpty()) appendLine("Opened in the last 60 days but not this week: ${recent.sortedBy { it.label.lowercase() }.joinToString { it.label }}.")
                if (stale.isNotEmpty()) appendLine("Not opened in 60 days: ${stale.sortedBy { it.label.lowercase() }.joinToString { it.label }}.")
            }
        }
    }

    private fun StringBuilder.crashSection(i: InsightsInput) {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        if (i.crashes.isEmpty()) { appendLine("None recorded."); return }
        fun sig(c: Crash) = c.type + "|" + (c.frames.firstOrNull()?.substringBeforeLast(':') ?: (c.message ?: "").take(40))
        val cur = i.version
        val seenNow = i.crashes.filter { KnownFixes.compare(it.version, cur) >= 0 }.map { sig(it) }.toSet()
        // A crash is closed when the code is known to have fixed it before this version, or when it happened only on older builds and has not come back on this one.
        fun fixOf(c: Crash) = KnownFixes.find(c)?.takeIf { KnownFixes.compare(c.version, it.fixedIn) < 0 && KnownFixes.compare(cur, it.fixedIn) >= 0 }
        val (closed, open) = i.crashes.partition { c -> fixOf(c) != null || (KnownFixes.compare(c.version, cur) < 0 && sig(c) !in seenNow) }
        if (open.isEmpty()) appendLine("None on $cur.") else open.sortedByDescending { it.at }.forEach { c ->
            appendLine("- ${day.format(Date(c.at))} · ${c.version} · `${c.type}`${c.message?.let { ": ${cell(it.take(200))}" } ?: ""}")
            c.frames.forEach { appendLine("  - `$it`") }
        }
        if (closed.isNotEmpty()) {
            appendLine()
            appendLine("Already fixed, or not seen again on $cur (not worth any time unless one returns):")
            closed.groupBy { c -> fixOf(c)?.name ?: sig(c) }.forEach { (_, group) ->
                val first = group.first(); val fix = fixOf(first); val versions = group.map { it.version }.distinct().joinToString(", ")
                if (fix != null) appendLine("- ${fix.name}: ${group.size}× on $versions; fixed in ${fix.fixedIn}")
                else appendLine("- `${first.type}`${first.frames.firstOrNull()?.let { f -> " at `$f`" } ?: ""}${first.message?.let { m -> ": ${cell(m.take(80))}" } ?: ""}: ${group.size}× on $versions; not seen on $cur")
            }
        }
    }

    private fun cell(s: String) = s.replace("|", "/").replace("\n", " ")
}
