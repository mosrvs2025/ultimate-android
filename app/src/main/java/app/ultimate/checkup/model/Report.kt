package app.ultimate.checkup.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How much of the scan goes into a shared report. */
data class ReportOptions(val includeApps: Boolean = true, val includeSystemApps: Boolean = false)

/**
 * The scan as plain Markdown, written to be pasted into an AI chat or saved. It says what was measured and what was not, so a reader
 * can tell "no apps found" from "could not check".
 */
object Report {
    fun markdown(scan: Scan, o: ReportOptions = ReportOptions(), ownPackages: Set<String> = emptySet()): String = buildString {
        val d = scan.device
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(scan.at))
        appendLine("# Phone checkup report")
        appendLine()
        appendLine("Generated $date by Ultimate's Phone checkup, entirely on the phone. Score ${scan.score}/100 (${Checkup.verdict(scan.score)}).")
        appendLine()
        appendLine("## Device")
        row("Model", "${d.manufacturer} ${d.model}")
        row("Android", "${d.androidVersion} (API ${d.sdk})")
        row("Security patch", d.securityPatch)
        row("Chip", d.chip)
        row("Kernel", d.kernel)
        row("Memory", "${Checkup.formatSize(d.ramBytes)} (${Checkup.formatSize(d.ramFreeBytes)} free)")
        row("Storage", "${Checkup.formatSize(d.storageBytes)} (${Checkup.formatSize(d.storageFreeBytes)} free)")
        row("Battery", listOfNotNull(d.batteryPercent?.let { "$it%" }, d.batteryHealth?.let { "health $it" }, d.batteryTempC?.let { "${it.toInt()} °C" }, d.batteryCycles?.let { "$it cycles" }).joinToString(", ").ifEmpty { null })
        row("Screen lock", yesNo(d.screenLock))
        row("Encrypted", yesNo(d.encrypted))
        row("Developer options", yesNo(d.developerOptions))
        row("USB debugging", yesNo(d.usbDebugging))
        row("Root signs", d.rootSigns.joinToString().ifEmpty { "none found" })
        row("Default SMS / dialer / browser / launcher", listOf(d.defaultSms, d.defaultDialer, d.defaultBrowser, d.defaultLauncher).joinToString(" / ") { it ?: "unknown" })
        appendLine()

        appendLine("## Findings")
        val issues = scan.findings.filter { it.severity != Severity.Good }
        if (issues.isEmpty()) appendLine("Nothing to fix.")
        issues.sortedBy { it.severity.ordinal }.forEach { f ->
            appendLine("- **[${f.severity.name}] ${f.title}** — ${f.detail}")
            if (f.apps.isNotEmpty()) appendLine("  - Apps: ${f.apps.joinToString { label(scan, it) }}")
        }
        appendLine()

        appendLine("## Who can reach what")
        val map = Checkup.privacyMap(scan.apps, ownPackages)
        if (map.isEmpty()) appendLine("No user app holds a sensitive permission.")
        map.forEach { (access, holders) -> appendLine("- **${access.label}** (${holders.size}): ${holders.joinToString { it.label }}") }
        appendLine()

        val user = scan.apps.filter { !it.system }
        appendLine("## Apps")
        appendLine("${user.size} installed by the user, ${scan.apps.size - user.size} system apps. Usage access: ${if (scan.usageAccess) "on" else "off (screen time and unused apps not measured)"}.")
        if (o.includeApps) {
            appendLine()
            appendLine("| App | Package | Version | Installed from | Target API | Access | Notes |")
            appendLine("|---|---|---|---|---|---|---|")
            scan.apps.filter { o.includeSystemApps || !it.system }.sortedBy { it.label.lowercase() }.forEach { a ->
                val notes = listOfNotNull(
                    "system".takeIf { a.system }, "accessibility".takeIf { a.accessibility }, "reads notifications".takeIf { a.notificationAccess == true },
                    "device admin".takeIf { a.deviceAdmin }, "VPN".takeIf { a.providesVpn }, a.adSdks?.takeIf { it.isNotEmpty() }?.let { "ads: ${it.joinToString()}" },
                    a.usedMs7d?.let { "${it / 60_000} min this week" },
                ).joinToString("; ")
                appendLine("| ${cell(a.label)} | ${a.packageName} | ${cell(a.versionName)} | ${a.installer ?: "unknown"} | ${a.targetSdk} | ${a.granted.joinToString { it.label }} | ${cell(notes)} |")
            }
        } else {
            appendLine("(App list left out of this report.)")
        }
        appendLine()
        appendLine("Not measured: what apps do over the network (see Traffic inspector), what encrypted data contains, and anything that needs root.")
    }

    /** A short prompt to go with the report when asking an AI. */
    const val PROMPT = "Here is a privacy and security checkup of my Android phone. Explain the most important risks in plain language, " +
        "tell me which apps or settings I should change first and exactly how, and point out anything unusual."

    private fun StringBuilder.row(label: String, value: String?) { appendLine("- $label: ${value ?: "unknown"}") }
    private fun yesNo(v: Boolean?) = when (v) { true -> "yes"; false -> "no"; null -> "unknown" }
    private fun label(scan: Scan, pkg: String) = scan.apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
    private fun cell(s: String) = s.replace("|", "/").replace("\n", " ")
}
