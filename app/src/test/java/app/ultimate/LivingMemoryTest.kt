package app.ultimate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.ultimate.home.HomeLayout
import app.ultimate.insights.InsightsStore
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LivingMemoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @After fun cleanup() {
        context.getSharedPreferences("insights", Context.MODE_PRIVATE).edit().clear().commit()
        java.security.Security.getProviders().filter { it.name.contains("Conscrypt", true) }.forEach { java.security.Security.removeProvider(it.name) }
    }

    @Test fun historySurvivesStoreRecreationAndIgnoresExpiredAndFutureEvents() {
        val store = InsightsStore(context, "test")
        store.entered("terminal"); store.left()
        val reopened = InsightsStore(context, "test")
        assertEquals("terminal", reopened.recentOpens().single().first)
        assertTrue(reopened.recentOpens(System.currentTimeMillis() + 49 * 3_600_000L).isEmpty())
        assertTrue(reopened.recentOpens(1).isEmpty())
    }

    @Test fun failedJobRemainsUntilClearedOrSucceeded() {
        val store = InsightsStore(context, "test")
        store.job("squeeze", 1, false)
        assertEquals(setOf("squeeze"), InsightsStore(context, "test").pendingTools())
        store.clearPending("squeeze")
        assertTrue(store.pendingTools().isEmpty())
        store.job("squeeze", 1, false); store.job("squeeze", 1, true)
        assertTrue(store.pendingTools().isEmpty())
        assertEquals(Triple(1, 2, 3L), store.jobs("squeeze"))
    }

    @Test fun activeToolsWinAndOldOrFutureHistoryDoesNotBiasRanking() {
        val now = 100 * 86_400_000L
        val result = HomeLayout.constellation(listOf("studio", "terminal", "files", "terminal"),
            List(50) { "studio" to now - 49 * 3_600_000L } + listOf("terminal" to now, "studio" to now + 1), setOf("files"), now)
        assertEquals(listOf("files", "terminal", "studio"), result)
        assertTrue(HomeLayout.constellation(emptyList(), emptyList(), emptySet(), now).isEmpty())
    }

    @Test fun observedTimeOfDayBreaksRecencyTieAcrossMidnight() {
        val now = java.util.Calendar.getInstance().apply { set(2026, 9, 30, 0, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        // Both tools opened yesterday. A midnight habit beats a slightly more recent lunchtime one.
        val result = HomeLayout.constellation(listOf("studio", "terminal"), listOf("terminal" to now - 24 * 3_600_000L, "studio" to now - 12 * 3_600_000L), emptySet(), now)
        assertEquals("terminal", result.first())
    }
}
