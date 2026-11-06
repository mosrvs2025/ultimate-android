package app.ultimate

import androidx.test.core.app.ApplicationProvider
import app.ultimate.home.Changelog
import app.ultimate.home.NewFeatures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UltimateApp::class)
class NewFeaturesTest {
    @After fun restoreSecurityProviders() {
        java.security.Security.getProviders().filter { it.name.contains("Conscrypt", true) }.forEach { java.security.Security.removeProvider(it.name) }
    }

    @Test fun newToolsComeFromTheNewestVersionsWithoutRepeats() {
        val entries = listOf(
            Changelog.Entry("3.0", listOf("a"), tries = listOf("x", "y")),
            Changelog.Entry("2.0", listOf("b"), tries = listOf("y", "z")),
            Changelog.Entry("1.0", listOf("c"), tries = listOf("old")),
        )
        assertEquals(listOf("x", "y", "z"), NewFeatures.ids("3.0", entries, versions = 2))
        assertEquals(listOf("y", "z", "old"), NewFeatures.ids("2.0", entries, versions = 2))
    }

    @Test fun everyTryButtonOpensAToolThatExistsInTheFullEdition() {
        if (BuildConfig.FLAVOR != "ultimateplus") return
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        val ids = app.tools.map { it.id }.toSet()
        val missing = Changelog.entries.flatMap { it.tries }.filter { it !in ids }
        assertTrue("Changelog points at tools that don't exist: $missing", missing.isEmpty())
    }

    @Test fun openedOrHiddenToolsAreNoLongerNew() {
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        val current = Changelog.entries.first().version
        val before = NewFeatures.pending(app, current).map { it.id }
        if (before.isEmpty()) return
        NewFeatures.hide(app, listOf(before.first()))
        assertTrue(before.first() !in NewFeatures.pending(app, current).map { it.id })
    }
}
