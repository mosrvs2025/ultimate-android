package app.ultimate

import app.ultimate.checkup.ui.ReportView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportViewTest {
    private val md = """
# Phone checkup report

Generated 2026-11-07 10:00 by Ultimate's Phone checkup, entirely on the phone. Score 79/100 (Fair).

## Device
- Model: samsung SM-F956U1
- Battery: 77%, health Good, 31 °C
- USB debugging: no

## Findings
- **[Critical] Screen reader app can see your screen** — Sideloaded and can read everything shown.
  - Apps: Clip Helper, Other App
- **[Warning] Security patch is 7 months old** — Updates fix known holes.
- **[Info] 12 apps never opened** — Remove to free space.

## Who can reach what
- **Location always** (2): Maps, Weather
- **Notifications** (1): Mail

## Apps
108 installed by the user, 600 system apps. Usage access: on.

| App | Package | Version | Installed from | Target API | Access | Notes |
|---|---|---|---|---|---|---|
| Maps | com.example.maps | 5.1 | com.android.vending | 34 | Location | ads: AdCo |
| Mail | com.example.mail | 2.0 | unknown | 33 | Notifications | reads notifications |
""".trimIndent()

    @Test fun theSavedReportComesBackAsParts() {
        val p = ReportView.parse(md)
        assertTrue(p.summary.contains("Score 79/100")); assertEquals("samsung SM-F956U1", p.device.first { it.first == "Model" }.second); assertEquals("77%, health Good, 31 °C", p.device.first { it.first == "Battery" }.second)
        assertEquals(3, p.findings.size); assertEquals("Critical", p.findings[0].severity); assertEquals(listOf("Clip Helper", "Other App"), p.findings[0].apps); assertEquals("Security patch is 7 months old", p.findings[1].title); assertTrue(p.findings[1].apps.isEmpty())
        assertEquals(listOf("Maps", "Weather"), p.access[0].apps); assertEquals(2, p.access[0].count)
        assertEquals(2, p.apps.size); assertEquals("com.example.maps", p.packages["Maps"]); assertEquals("reads notifications", p.apps[1].notes); assertTrue(p.appsLine.startsWith("108 installed"))
        assertEquals("nothing recognised, nothing invented", 0, ReportView.parse("hello").findings.size)
    }
}
