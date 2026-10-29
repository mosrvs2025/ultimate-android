package app.ultimate

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import app.ultimate.orb.OrbService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/** Starts the real orb service and its overlay windows, so a crash while drawing them fails here instead of on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OrbServiceTest {
    // Robolectric adds its own security provider to the whole test JVM; remove it so TLS tests in the same run see the JDK's.
    @After fun restoreSecurityProviders() {
        java.security.Security.getProviders().filter { it.name.contains("Conscrypt", ignoreCase = true) }.forEach { java.security.Security.removeProvider(it.name) }
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun windows(): List<View> = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(context.getSystemService(WindowManager::class.java)).views

    @Test fun orbAppearsAndBloomsWithoutCrashing() {
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(OrbService::class.java).create()
        shadowOf(Looper.getMainLooper()).idle()
        val orb = windows().single()
        orb.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST))
        assertTrue(orb.isAttachedToWindow)

        // A tap opens the bloom window over the screen.
        val t = SystemClock.uptimeMillis()
        orb.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        orb.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, 10f, 10f, 0))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, windows().size)

        // The start counted as healthy, so the next launch starts it again.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        assertFalse(context.getSharedPreferences("orb", 0).getBoolean("starting", true))
        controller.destroy()
        assertTrue(windows().isEmpty())
    }

    @Test fun withoutPermissionItStopsQuietly() {
        ShadowSettings.setCanDrawOverlays(false)
        Robolectric.buildService(OrbService::class.java).create().destroy()
        assertTrue(windows().isEmpty())
    }

    @Test fun aStartThatNeverFinishedLeavesTheOrbOff() {
        context.getSharedPreferences("orb", 0).edit().putBoolean("on", true).putBoolean("starting", true).commit()
        ShadowSettings.setCanDrawOverlays(true)
        OrbService.resume(context)
        assertFalse(OrbService.on(context))
        assertTrue(OrbService.crashed(context))
        assertFalse(OrbService.crashed(context))
    }
}
