package app.ultimate.checkup.ui

/**
 * The saved markdown report, read back into parts the screen can lay out as cards (instead of showing the text as it is). Pure, so it is tested.
 * It reads exactly what [app.ultimate.checkup.model.Report.markdown] writes; anything it does not recognise is left out rather than guessed.
 */
object ReportView {
    data class Finding(val severity: String, val title: String, val detail: String, val apps: List<String>)
    data class Access(val label: String, val count: Int, val apps: List<String>)
    data class AppRow(val label: String, val pkg: String, val version: String, val installer: String, val notes: String)
    data class Parsed(val summary: String, val device: List<Pair<String, String>>, val findings: List<Finding>, val access: List<Access>, val appsLine: String, val apps: List<AppRow>) {
        /** App label to package name, from the app table: so a name in a finding can open that app. */
        val packages: Map<String, String> get() = apps.associate { it.label to it.pkg }
    }

    fun parse(md: String): Parsed {
        val lines = md.lines()
        fun section(name: String): List<String> { val i = lines.indexOfFirst { it.trim() == "## $name" }; if (i < 0) return emptyList(); return lines.drop(i + 1).takeWhile { !it.startsWith("## ") } }
        val summary = lines.firstOrNull { it.startsWith("Generated ") }.orEmpty()
        val device = section("Device").mapNotNull { l -> l.trim().takeIf { it.startsWith("- ") }?.drop(2)?.split(": ", limit = 2)?.takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        val findings = ArrayList<Finding>(); val f = section("Findings")
        var i = 0
        while (i < f.size) {
            val m = Regex("""^- \*\*\[(\w+)] (.*?)\*\* — (.*)$""").find(f[i].trim())
            if (m != null) {
                val apps = f.getOrNull(i + 1)?.trim()?.takeIf { it.startsWith("- Apps: ") }?.drop(8)?.split(", ")?.map { it.trim() }.orEmpty()
                findings += Finding(m.groupValues[1], m.groupValues[2], m.groupValues[3], apps); i += if (apps.isNotEmpty()) 2 else 1
            } else i++
        }
        val access = section("Who can reach what").mapNotNull { l -> Regex("""^- \*\*(.*?)\*\* \((\d+)\): (.*)$""").find(l.trim())?.let { Access(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].split(", ").map { a -> a.trim() }) } }
        val apps = section("Apps"); val appsLine = apps.firstOrNull { it.isNotBlank() }.orEmpty()
        val rows = apps.filter { it.startsWith("| ") && !it.startsWith("| App") && !it.startsWith("|---") }.mapNotNull { l ->
            val c = l.trim().trim('|').split(" | ").map { it.trim() }; if (c.size >= 7) AppRow(c[0], c[1], c[2], c[3], c[6]) else null
        }
        return Parsed(summary, device, findings, access, appsLine, rows)
    }
}
