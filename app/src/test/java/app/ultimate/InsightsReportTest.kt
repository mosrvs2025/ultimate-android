package app.ultimate

import app.ultimate.insights.Crash
import app.ultimate.insights.InsightsInput
import app.ultimate.insights.InsightsReport
import app.ultimate.insights.OtherApp
import app.ultimate.insights.ToolUse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsReportTest {
    private val base = InsightsInput(
        "1.15.0", "ultimateplus", "Samsung SM-F956U", "15 (SDK 35)", 1_760_000_000_000,
        listOf(ToolUse("audio", "Video to audio", 12, 40, 1_760_000_000_000, 5, 1000), ToolUse("lan", "Network | scanner", 0, 0, 0, 0, 0)),
        listOf(
            OtherApp("CapCut", "com.lemon.lvoverseas", "Video", 95, 1_760_000_000_000, null),
            OtherApp("Compress Video", "x.y", "Video", 3, 1, "Squeeze"),
        ),
        usageAccess = true, crashes = listOf(Crash(1_760_000_000_000, "1.14.0", "java.lang.IllegalStateException", "boom", listOf("AudioConverter.transcode:140"))),
        notes = "A PDF tool please",
    )

    @Test fun containsEverySectionAndEscapesTables() {
        val md = InsightsReport.markdown(base)
        assertTrue(md.contains("| Video to audio | 12 | 0 | 40 |"))
        assertTrue(md.contains("Network / scanner"))
        assertTrue(md.contains("Never opened: Network | scanner."))
        assertTrue(md.contains("A PDF tool please"))
        assertTrue(md.contains("`AudioConverter.transcode:140`"))
        assertTrue(md.contains("- Compress Video → Squeeze (3 min this week)"))
        assertTrue(md.indexOf("| CapCut | Video | 95 |") > 0)
    }

    @Test fun featuresProblemsSearchesAndSetupAreReported() {
        val md = InsightsReport.markdown(base.copy(
            tools = listOf(ToolUse("squeeze", "Squeeze", 3, 1, 0, 2, 0, quickVisits = 2, jobsOk = 3, jobsFailed = 1, jobMs = 40_000)),
            features = mapOf("studio.autocaptions" to 4, "home.savings" to 1), problems = listOf("10-28 · Studio: no sound"),
            unmatched = listOf("10-28 · scan a document"), setup = listOf("On-device speech" to "available"),
        ))
        assertTrue(md.contains("| Squeeze | 3 | 2 | 1 |"))
        assertTrue(md.contains("| 3/1 | 10s |"))
        assertTrue(md.indexOf("studio.autocaptions: 4") < md.indexOf("home.savings: 1"))
        assertTrue(md.contains("\"10-28 · scan a document\""))
        assertTrue(md.contains("- 10-28 · Studio: no sound"))
        assertTrue(md.contains("- On-device speech: available"))
    }

    @Test fun appsLeftOutWhenNotIncluded() {
        val md = InsightsReport.markdown(base.copy(apps = null, notes = ""))
        assertFalse(md.contains("My other apps"))
        assertFalse(md.contains("My notes"))
        assertFalse(md.contains("CapCut"))
    }

    @Test fun idleAppsShareOneLineAndCountingStartIsShown() {
        val md = InsightsReport.markdown(base.copy(trackingSince = 1_760_000_000_000 - 2 * 86_400_000L, apps = base.apps!! + OtherApp("Zoom", "z", "Productivity", 0, 0, null) + OtherApp("Gmail", "g", "Social", 0, 5, null)))
        assertTrue(md.contains("(2 days)"))
        assertTrue(md.contains("Not opened in 60 days: Zoom."))
        assertTrue(md.contains("not this week: Gmail."))
        assertFalse(md.contains("| Zoom |"))
    }

    @Test fun unknownUsageIsSaidPlainly() {
        val md = InsightsReport.markdown(base.copy(usageAccess = false, apps = listOf(OtherApp("Maps", "m", "Maps", null, null, null))))
        assertTrue(md.contains("Usage access is off"))
        assertTrue(md.contains("| Maps | Maps | ? | unknown |"))
    }
}
