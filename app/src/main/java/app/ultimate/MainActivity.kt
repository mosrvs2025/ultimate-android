package app.ultimate

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.ultimate.core.UltimateTheme
import app.ultimate.home.UltimateRoot

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_TOOL = "app.ultimate.TOOL"; const val EXTRA_FILES = "app.ultimate.FILES" }

    private val ultimate get() = application as UltimateApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent { UltimateTheme { UltimateRoot(ultimate) } }
    }

    // Android lets an app read the clipboard only while it has focus, so each return to Ultimate keeps whatever was copied.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && BuildConfig.HAS_CLIPS) ultimate.clips.captureNow("opened")
        // A newer APK downloaded while Ultimate was closed is offered the next time it opens.
        // The orb comes back whenever Ultimate is opened, if it was left on.
        if (hasFocus && BuildConfig.HAS_ORB) app.ultimate.orb.OrbService.resume(this)
        if (hasFocus && BuildConfig.HAS_FILES) Thread { runCatching { app.ultimate.files.UpdateWatcher.find(this)?.let { app.ultimate.files.UpdateWatcher.notify(this, it) } } }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleShare(intent: Intent?) {
        if (intent?.getBooleanExtra(app.ultimate.send.PhoneActions.EXTRA_STOP_RING, false) == true && BuildConfig.HAS_SEND) { ultimate.sendServer.actions.stopRing(); return }
        // A finished-work notification names its tool, so tapping it lands there instead of on Home.
        intent?.getStringExtra(EXTRA_TOOL)?.let { id ->
            val files = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(EXTRA_FILES, Uri::class.java) else intent.getParcelableArrayListExtra<Uri>(EXTRA_FILES)).orEmpty()
            ultimate.tools.firstOrNull { it.id == id }?.let { ultimate.shell.open(it, app.ultimate.core.ToolLaunch(files)) }
            return
        }
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) ultimate.shell.offerShared(uris, intent?.type)
    }
}
