package app.ultimate.checkup

import app.ultimate.checkup.model.Access
import app.ultimate.checkup.model.AppFacts
import app.ultimate.checkup.model.Checkup
import app.ultimate.checkup.model.DeviceFacts
import app.ultimate.checkup.model.Fix
import app.ultimate.checkup.model.Report
import app.ultimate.checkup.model.ReportOptions
import app.ultimate.checkup.model.Scan
import app.ultimate.checkup.model.Severity
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckupTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = LocalDate.of(2026, 10, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun device(
        patch: String? = "2026-09-05", lock: Boolean? = true, encrypted: Boolean? = true, adb: Boolean? = false, dev: Boolean? = false,
        free: Long = 50, health: String? = "Good", cycles: Int? = 200, temp: Float? = 30f,
    ) = DeviceFacts(
        "Samsung", "SM-S918B", "15", 35, patch, "6.1", "Qualcomm SM8550", 8L shl 30, 3L shl 30, 100L shl 30, free shl 30,
        80, health, temp, cycles, false, 3_600_000, lock, encrypted, dev, adb, true, emptyList(), "com.google.android.apps.messaging", null, null, null,
    )

    private fun app(
        pkg: String, label: String = pkg, system: Boolean = false, installer: String? = "com.android.vending", target: Int = 34,
        granted: Set<Access> = emptySet(), installedDaysAgo: Int = 100, updatedDaysAgo: Int = 10, a11y: Boolean = false, notif: Boolean? = false,
        admin: Boolean = false, lastUsedDaysAgo: Int? = null, ads: List<String>? = null,
    ) = AppFacts(
        pkg, label, system, installer, "1.0", target, now - installedDaysAgo * day, now - updatedDaysAgo * day, 10L shl 20, granted,
        accessibility = a11y, notificationAccess = notif, deviceAdmin = admin, adSdks = ads,
        lastUsedAt = lastUsedDaysAgo?.let { if (it < 0) 0L else now - it * day }, usedMs7d = lastUsedDaysAgo?.let { 0L },
    )

    private fun find(device: DeviceFacts = device(), apps: List<AppFacts> = emptyList(), usage: Boolean = true, own: Set<String> = emptySet()) =
        Checkup.findings(device, apps, now, usage, own)

    @Test fun aHealthyPhoneScoresFullMarks() {
        val f = find()
        assertEquals(100, Checkup.score(f))
        assertTrue(f.all { it.severity == Severity.Good || it.severity == Severity.Info })
    }

    @Test fun noScreenLockIsCriticalWithAFix() {
        val f = find(device(lock = false)).single { it.id == "lock" }
        assertEquals(Severity.Critical, f.severity)
        assertEquals(Fix.SecuritySettings, f.fix)
    }

    @Test fun securityPatchAgeIsGraded() {
        assertEquals(Severity.Good, find(device(patch = "2026-08-01")).single { it.id == "patch" }.severity)
        assertEquals(Severity.Warning, find(device(patch = "2026-05-01")).single { it.id == "patch" }.severity)
        assertEquals(Severity.Critical, find(device(patch = "2025-12-01")).single { it.id == "patch" }.severity)
        assertTrue("unknown patch is not judged", find(device(patch = null)).none { it.id == "patch" })
        assertTrue("unparseable patch is not judged", find(device(patch = "garbage")).none { it.id == "patch" })
    }

    @Test fun unknownSecurityFactsAreNotTreatedAsBad() {
        val f = find(device(lock = null, encrypted = null, adb = null, dev = null))
        assertTrue(f.none { it.id in setOf("lock", "encryption", "adb", "dev") })
    }

    @Test fun powerfulAccessIsListedWithTheApps() {
        val apps = listOf(
            app("com.keyboard.spy", "Fancy Keyboard", a11y = true),
            app("com.chat", "Chat", notif = true),
            app("com.mdm", "Work", admin = true),
            app("com.weather", "Weather", granted = setOf(Access.Location, Access.BackgroundLocation)),
        )
        val f = find(apps = apps).associateBy { it.id }
        assertEquals(listOf("com.keyboard.spy"), f.getValue("a11y").apps)
        assertEquals(listOf("com.chat"), f.getValue("notif").apps)
        assertEquals(listOf("com.mdm"), f.getValue("admin").apps)
        assertEquals(listOf("com.weather"), f.getValue("bgloc").apps)
    }

    @Test fun systemAndOwnAppsAreNotJudged() {
        val apps = listOf(app("com.android.systemui", system = true, a11y = true, target = 10), app("app.ultimate", installer = null, target = 20))
        val f = find(apps = apps, own = setOf("app.ultimate"))
        assertTrue(f.none { it.apps.isNotEmpty() })
    }

    @Test fun appHygieneRules() {
        val apps = listOf(
            app("com.side", installer = null),
            app("com.fdroid", installer = "org.fdroid.fdroid"),
            app("com.old", target = 23),
            app("com.stale", updatedDaysAgo = 900),
            app("com.ads", ads = listOf("AdMob")),
            app("com.scanned.clean", ads = emptyList()),
        )
        val f = find(apps = apps).associateBy { it.id }
        assertEquals(listOf("com.side"), f.getValue("sideload").apps)
        assertEquals(listOf("com.old"), f.getValue("oldtarget").apps)
        assertEquals(listOf("com.stale"), f.getValue("stale").apps)
        assertEquals(listOf("com.ads"), f.getValue("ads").apps)
    }

    @Test fun unusedAppsNeedUsageAccessAndIgnoreNewInstalls() {
        val apps = listOf(
            app("com.never", lastUsedDaysAgo = -1),
            app("com.recent", lastUsedDaysAgo = 2),
            app("com.dusty", lastUsedDaysAgo = 45),
            app("com.brandnew", lastUsedDaysAgo = -1, installedDaysAgo = 3),
        )
        assertEquals(listOf("com.dusty", "com.never"), find(apps = apps).single { it.id == "unused" }.apps)
        val without = find(apps = apps.map { it.copy(lastUsedAt = null) }, usage = false)
        assertTrue(without.none { it.id == "unused" })
        assertEquals(Fix.UsageAccess, without.single { it.id == "usage" }.fix)
    }

    @Test fun deviceRules() {
        val f = find(device(free = 5, health = "Overheating", cycles = 900, temp = 47f)).associateBy { it.id }
        assertEquals(Severity.Warning, f.getValue("storage").severity)
        assertEquals(Severity.Warning, f.getValue("battery").severity)
        assertEquals(Severity.Info, f.getValue("cycles").severity)
        assertEquals(Severity.Warning, f.getValue("hot").severity)
    }

    @Test fun scoreSubtractsBySeverityAndNeverGoesNegative() {
        val bad = find(device(lock = false, encrypted = false, patch = "2024-01-01", adb = true))
        assertEquals(100 - 20 * 3 - 7, Checkup.score(bad))
        val worst = bad + bad + bad
        assertEquals(0, Checkup.score(worst))
        assertEquals("At risk", Checkup.verdict(10))
        assertEquals("Looking great", Checkup.verdict(95))
    }

    @Test fun privacyMapGroupsUserAppsByAccessMostHeldFirst() {
        val apps = listOf(
            app("a", "Alpha", granted = setOf(Access.Camera, Access.Location)),
            app("b", "Beta", granted = setOf(Access.Location)),
            app("s", "System", system = true, granted = setOf(Access.Location)),
        )
        val map = Checkup.privacyMap(apps)
        assertEquals(Access.Location, map.first().first)
        assertEquals(listOf("Alpha", "Beta"), map.first().second.map { it.label })
        assertEquals(listOf(Access.Location, Access.Camera), map.map { it.first })
    }

    @Test fun permissionNamesMapToAccess() {
        assertEquals(Access.BackgroundLocation, Access.byPermission["android.permission.ACCESS_BACKGROUND_LOCATION"])
        assertEquals(Access.Media, Access.byPermission["android.permission.READ_MEDIA_IMAGES"])
        assertNull(Access.byPermission["android.permission.INTERNET"])
    }

    @Test fun reportSaysWhatWasAndWasNotMeasuredAndCanLeaveOutApps() {
        val apps = listOf(app("com.chat", "Chat | Pro", notif = true, granted = setOf(Access.Contacts)))
        val scan = Scan(now, device(lock = null), apps, false, find(device(lock = null), apps, usage = false))
        val full = Report.markdown(scan)
        assertTrue(full.contains("Screen lock: unknown"))
        assertTrue(full.contains("Usage access: off"))
        assertTrue("pipes in names must not break the table", full.contains("| Chat / Pro | com.chat |"))
        assertTrue(full.contains("**Contacts** (1): Chat | Pro"))
        val lean = Report.markdown(scan, ReportOptions(includeApps = false))
        assertFalse(lean.contains("| com.chat |"))
        assertTrue(lean.contains("App list left out"))
    }
}
