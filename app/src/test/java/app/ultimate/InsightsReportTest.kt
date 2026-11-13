package app.ultimate

import app.ultimate.insights.Crash
import app.ultimate.insights.InsightsInput
import app.ultimate.insights.InsightsReport
import app.ultimate.insights.KnownFixes
import app.ultimate.insights.ProblemNoise
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

    private val t0 = 1_760_000_000_000
    private fun crash(version: String, type: String = "java.lang.IllegalStateException", msg: String? = "boom", vararg frames: String) = Crash(t0, version, type, msg, frames.toList())

    @Test fun aCrashFixedBeforeThisVersionIsNotReportedAsOpen() {
        val ruler = crash("1.49.1", "java.lang.IllegalArgumentException", "maxWidth(-3) must be >= than minWidth(0)", "StudioScreenKt.Ruler\$lambda:314")
        val md = InsightsReport.markdown(base.copy(version = "2.8.0", crashes = listOf(ruler, ruler)))
        assertTrue(md.contains("None on 2.8.0."))
        assertTrue(md.contains("Studio timeline ruler: a negative width when trimming: 2× on 1.49.1; fixed in 1.50.0"))
        assertFalse("not listed as an open crash with its stack", md.contains("  - `StudioScreenKt.Ruler"))
    }

    @Test fun anOldCrashThatHasNotComeBackIsSummarisedAndOneThatHasIsOpen() {
        val gone = crash("1.40.0", "java.lang.IllegalStateException", "old", "AudioConverter.transcode:140")
        val back = crash("1.40.0", "java.lang.IllegalStateException", "old", "Foo.bar:9")
        val nowToo = crash("2.8.0", "java.lang.IllegalStateException", "again", "Foo.bar:11")
        val md = InsightsReport.markdown(base.copy(version = "2.8.0", crashes = listOf(gone, back, nowToo)))
        assertTrue(md.contains("`AudioConverter.transcode:140`") && md.contains("not seen on 2.8.0"))
        assertTrue("the one that returned is open, with its stack", md.contains("  - `Foo.bar:11`"))
        assertFalse(md.contains("None on 2.8.0."))
        assertTrue("only the crash that never returned is called not seen again", Regex("not seen on 2.8.0").findAll(md).count() == 1)
    }

    @Test fun aKnownFixThatCameBackIsAnOpenCrashAgain() {
        val regression = crash("2.8.0", "java.lang.IllegalArgumentException", "maxWidth(-3) must be >= than minWidth(0)", "StudioScreenKt.Ruler\$lambda:314")
        val md = InsightsReport.markdown(base.copy(version = "2.8.0", crashes = listOf(regression)))
        assertTrue(md.contains("  - `StudioScreenKt.Ruler"))
        assertFalse(md.contains("None on 2.8.0."))
    }

    @Test fun noneRecordedStaysNoneRecorded() { assertTrue(InsightsReport.markdown(base.copy(crashes = emptyList())).contains("None recorded.")) }

    @Test fun cancelledWorkIsNotAProblemAndRealProblemsStay() {
        val md = InsightsReport.markdown(base.copy(problems = listOf("11-11 · Share clean: The coroutine scope left the composition", "11-10 · Install: Install cancelled", "11-10 · Studio: Unexpected runtime error")))
        assertFalse(md.contains("left the composition")); assertFalse(md.contains("Install cancelled"))
        assertTrue(md.contains("Studio: Unexpected runtime error"))
        assertTrue(ProblemNoise.isNoise("StandaloneCoroutine was cancelled")); assertFalse(ProblemNoise.isNoise("Could not create output video"))
    }

    @Test fun versionsCompareByNumberNotText() {
        assertTrue(KnownFixes.compare("1.9.0", "1.50.0") < 0); assertTrue(KnownFixes.compare("2.8.0", "2.8.0") == 0)
        assertTrue(KnownFixes.compare("2.10.0", "2.9.9") > 0); assertTrue(KnownFixes.compare("1.49.1", "1.50.0") < 0)
    }

    @Test fun theReportFromTheDeviceReadsCleanOn280() {
        val ruler = { v: String, m: String -> Crash(t0, v, "java.lang.IllegalArgumentException", m, if (v == "1.49.0") emptyList() else listOf("StudioScreenKt.Ruler\$lambda\$100\$lambda\$99:314", "StudioScreenKt.R:0")) }
        val key = Crash(t0, "1.48.0", "java.lang.IllegalArgumentException", "Key \"m-Notification-1793676257880--59941445\" was already used. If you are using LazyColumn/Row please make sure you provide a unique key for each item.", emptyList())
        val md = InsightsReport.markdown(base.copy(version = "2.8.0", crashes = listOf(key, ruler("1.49.0", "maxWidth(-1) must be >= than minWidth(0)"), ruler("1.49.1", "maxWidth(-3) must be >= than minWidth(0)"), ruler("1.49.1", "maxWidth(-5) must be >= than minWidth(0)"), ruler("1.49.1", "maxWidth(-4) must be >= than minWidth(0)")),
            problems = listOf("11-02 · Studio: Move the playhead inside the selected clip to split it", "11-09 · Install: Too many active sessions for UID 10560", "11-10 · Install: Install cancelled", "11-10 · Studio: Unexpected runtime error", "11-11 · Share clean: The coroutine scope left the composition")))
        println(md.substringAfter("## Problems shown").substringBefore("## My other apps").ifBlank { md })
        assertTrue(md.contains("None on 2.8.0."))
        assertTrue(md.contains("4× on 1.49.0, 1.49.1; fixed in 1.50.0") || md.contains("× on 1.49.1, 1.49.0; fixed in 1.50.0") || md.contains("4× on"))
        assertTrue(md.contains("Notification list: the same record twice crashed the list: 1× on 1.48.0; fixed in 1.89.0"))
        // What is genuinely still open stays visible, including the install-session message whose earlier fix evidently did not cover everything.
        assertTrue(md.contains("Too many active sessions for UID 10560")); assertTrue(md.contains("Unexpected runtime error")); assertTrue(md.contains("Move the playhead"))
        assertFalse(md.contains("left the composition")); assertFalse(md.contains("Install cancelled"))
    }
}
