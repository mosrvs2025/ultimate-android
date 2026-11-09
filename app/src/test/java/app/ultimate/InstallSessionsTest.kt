package app.ultimate

import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import app.ultimate.files.ApkInstaller
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cancelled installs used to pile up as open sessions until Android said "Too many active sessions for UID". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UltimateApp::class)
class InstallSessionsTest {
    @Test fun staleSessionsAreClearedSoANewInstallCanStart() {
        val installer = ApplicationProvider.getApplicationContext<UltimateApp>().packageManager.packageInstaller
        repeat(7) { installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)) }
        assertEquals(7, installer.mySessions.size)
        assertEquals(7, ApkInstaller.abandonStale(installer))
        assertEquals(0, installer.mySessions.size)
        assertEquals("nothing left is fine", 0, ApkInstaller.abandonStale(installer))
    }
}
