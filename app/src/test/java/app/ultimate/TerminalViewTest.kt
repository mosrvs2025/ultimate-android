package app.ultimate

import androidx.test.core.app.ApplicationProvider
import app.ultimate.terminal.prepare
import app.ultimate.terminal.viewClient
import com.termux.view.TerminalView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The terminal tab, set up the way the screen sets it up, in a simulated Android. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UltimateApp::class)
class TerminalViewTest {
    private val app get() = ApplicationProvider.getApplicationContext<UltimateApp>()

    @After fun restoreSecurityProviders() {
        java.security.Security.getProviders().filter { it.name.contains("Conscrypt", ignoreCase = true) }.forEach { java.security.Security.removeProvider(it.name) }
    }

    @Test fun aNewViewIsReadyWithoutCrashing() {
        if (!BuildConfig.HAS_TERMINAL) return
        val view = TerminalView(app, null)
        view.prepare(viewClient(app.terminal, view, 40) {}, 40)
        assertNotNull(view.mRenderer)
        val before = view.mRenderer
        view.setTextSize(80) // zooming later works too, with a fresh renderer for the new size
        check(view.mRenderer !== before) { "zoom did not rebuild the renderer" }
    }

    @Test fun openingAShellTabCreatesASession() {
        if (!BuildConfig.HAS_TERMINAL) return
        val session = app.terminal.open()
        assertEquals(listOf(session), app.terminal.sessions.value)
        assertEquals(session, app.terminal.current.value)
    }
}
