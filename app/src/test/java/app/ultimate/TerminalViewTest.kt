package app.ultimate

import android.app.Application
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import app.ultimate.terminal.createTerminalView
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real widget initialization that used to crash before a shell could appear. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TerminalViewTest {
    @After fun restoreSecurityProviders() {
        java.security.Security.getProviders().filter { it.name.contains("Conscrypt", ignoreCase = true) }
            .forEach { java.security.Security.removeProvider(it.name) }
    }

    @Test fun terminalViewCanOpenAndChangeTypeface() {
        val view = createTerminalView(ApplicationProvider.getApplicationContext(), 32)
        view.setTypeface(Typeface.MONOSPACE)
        assertNotNull(view.mRenderer)
    }

    @Test fun invalidSavedFontDoesNotBreakOpeningTheView() {
        val view = createTerminalView(ApplicationProvider.getApplicationContext(), 0)
        assertNotNull(view.mRenderer)
    }
}
