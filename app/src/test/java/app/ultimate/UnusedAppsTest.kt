package app.ultimate

import app.ultimate.storage.InstalledApp
import app.ultimate.storage.UnusedApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnusedAppsTest {
    private val day = UnusedApps.DAY; private val now = 1_800_000_000_000L
    private fun app(label: String, size: Long = 50_000_000, usedDaysAgo: Long? = null, installedDaysAgo: Long = 400, covered: String? = null, pkg: String = "p.${label.lowercase()}") =
        InstalledApp(pkg, label, size, if (usedDaysAgo == null) 0 else now - usedDaysAgo * day, now - installedDaysAgo * day, covered)

    @Test fun nothingIsOfferedWithoutUsageAccess() { assertTrue(UnusedApps.candidates(listOf(app("Old", usedDaysAgo = 200)), now, usageKnown = false).isEmpty()) }

    @Test fun anAppWithNoUseInTheWindowIsIdleAtLeastThatLongNotForItsWholeLife() {
        val c = UnusedApps.candidates(listOf(app("Ancient", installedDaysAgo = 1000)), now, true).single()
        assertEquals(60, c.idleDays); assertTrue(c.atLeast); assertEquals("Not opened in at least 60 days", c.reason)
    }

    @Test fun anAppInstalledRecentlyAndNeverOpenedIsJudgedFromItsInstallDate() {
        assertTrue("installed 10 days ago: too soon to call it unused", UnusedApps.candidates(listOf(app("New", installedDaysAgo = 10)), now, true).isEmpty())
        val c = UnusedApps.candidates(listOf(app("Newish", installedDaysAgo = 45)), now, true).single()
        assertEquals(45, c.idleDays); assertFalse(c.atLeast)
    }

    @Test fun recentlyUsedAppsAreNotOffered() { assertTrue(UnusedApps.candidates(listOf(app("Daily", usedDaysAgo = 1), app("Monthly", usedDaysAgo = 29)), now, true).isEmpty()) }

    @Test fun anAppUltimateAlreadyDoesIsOfferedAfterAWeekAndFirst() {
        val list = UnusedApps.candidates(listOf(app("Big old", size = 900_000_000, installedDaysAgo = 900), app("Compress Video Size", size = 20_000_000, usedDaysAgo = 9, covered = "Squeeze")), now, true)
        assertEquals(listOf("Compress Video Size", "Big old"), list.map { it.app.label })
        assertTrue(list[0].reason.startsWith("Squeeze in Ultimate does this"))
    }

    @Test fun appsThatCouldHoldSomethingUnrecoverableAreFlaggedAndNeverSuggested() {
        val list = UnusedApps.candidates(listOf(app("Authenticator"), app("Samsung Wallet"), app("Proton Mail"), app("CA DMV Wallet"), app("Magic Piano"), app("Cash App")), now, true)
        assertEquals(setOf("Authenticator", "Samsung Wallet", "Proton Mail", "CA DMV Wallet", "Cash App"), list.filter { it.careful }.map { it.app.label }.toSet())
        assertEquals(listOf("Magic Piano"), UnusedApps.suggested(list).map { it.app.label })
    }

    @Test fun biggestIdleAppsComeFirstAndTheTotalAddsUp() {
        val list = UnusedApps.candidates(listOf(app("Small", size = 5_000_000), app("Large", size = 400_000_000), app("Medium", size = 90_000_000)), now, true)
        assertEquals(listOf("Large", "Medium", "Small"), list.map { it.app.label })
        assertEquals(495_000_000L, UnusedApps.bytes(list))
    }
}
