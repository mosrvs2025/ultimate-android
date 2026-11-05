package app.ultimate.timeline

import java.util.TimeZone

/**
 * "About me", written from the phone's own records, for giving an AI (or yourself) the context of how you live: your
 * daily rhythm, the apps you use, who reaches you, what you watch, how you focus. Made on the phone and only shared if
 * you share it. Pure, so what goes in it is tested.
 */
object AboutMe {
    private fun hm(ms: Long) = if (ms >= 3_600_000) "${ms / 3_600_000}h ${ms / 60_000 % 60}m" else "${ms / 60_000} min"

    /**
     * [rhythm] are plain sentences about the day (from Footprint's usage reading), [topApps] the apps opened most, and
     * [all] every moment from the last [days] days.
     */
    fun build(all: List<Moment>, rhythm: List<String>, topApps: List<String>, now: Long, days: Int = 30, zone: TimeZone = TimeZone.getDefault()): String = buildString {
        appendLine("# About me, from my phone (last $days days)")
        appendLine()
        appendLine("_Written on my phone by the Ultimate app from its own records. I'm sharing it so you know my habits and context. Nothing here was guessed by an AI._")
        fun section(title: String, lines: List<String>) { if (lines.isEmpty()) return; appendLine(); appendLine("## $title"); lines.forEach { appendLine("- $it") } }

        section("Daily rhythm", rhythm)
        section("Apps I use most", if (topApps.isEmpty()) emptyList() else listOf(topApps.take(6).joinToString(", ")))

        val notes = all.filter { it.source == Source.Notification }
        if (notes.isNotEmpty()) {
            val perDay = notes.size / days.coerceAtLeast(1)
            val top = notes.groupingBy { it.app }.eachCount().entries.sortedByDescending { it.value }.take(4).joinToString(", ") { "${it.key} (${it.value})" }
            section("Notifications", listOf("About $perDay a day.", "Most from: $top."))
        }

        val played = all.filter { it.source == Source.Watched }
        if (played.isNotEmpty()) {
            val total = played.sumOf { it.end - it.at }
            val who = played.filter { it.detail.isNotBlank() }.groupBy { it.detail }.mapValues { (_, v) -> v.sumOf { it.end - it.at } }.entries.sortedByDescending { it.value }.take(5).joinToString(", ") { it.key }
            val apps = played.groupBy { it.app }.mapValues { (_, v) -> v.sumOf { it.end - it.at } }.entries.sortedByDescending { it.value }.take(3).joinToString(", ") { it.key }
            section("What I watch and listen to", listOfNotNull(
                "About ${hm(total * 7 / days.coerceAtLeast(1))} a week, mostly on $apps.",
                who.takeIf { it.isNotBlank() }?.let { "Artists and channels I spend the most time with: $it." },
            ))
        }

        val focus = all.filter { it.source == Source.Focus }
        if (focus.isNotEmpty()) {
            val tasks = focus.sortedByDescending { it.at }.map { it.title }.distinct().take(5).joinToString("; ") { "\"$it\"" }
            section("Focus", listOf("${focus.size} focus sessions, ${hm(focus.sumOf { it.end - it.at })} in total.", "Recent things I worked on: $tasks."))
        }

        val photos = all.count { it.source == Source.Photo }; val shots = all.count { it.source == Source.Screenshot }
        if (photos + shots > 0) section("Photos", listOf("$photos photos and $shots screenshots."))
        val installs = all.filter { it.source == Source.Install }
        if (installs.isNotEmpty()) section("New apps", listOf(installs.sortedByDescending { it.at }.take(6).joinToString(", ") { it.title }))

        section("Patterns across all of it", TimelineMath.insights(all, now, zone))
    }
}
