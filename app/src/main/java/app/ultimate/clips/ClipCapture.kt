package app.ultimate.clips

import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import app.ultimate.UltimateApp

/**
 * A see-through moment in front: long enough for Android to let Ultimate read the clipboard, or to receive selected or shared
 * text, then it closes. Opened by the quick-settings tile, the text-selection menu and the share menu.
 */
class ClipCaptureActivity : ComponentActivity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = (application as UltimateApp).clips
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        if (text != null) {
            store.add(text.toString(), if (intent.action == Intent.ACTION_PROCESS_TEXT) "selected" else "shared")
            toast("Saved to Ultimate clipboard"); done = true; finish()
        }
    }

    // The clipboard is readable only once this window has focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val kept = (application as UltimateApp).clips.captureNow("tile")
        toast(if (kept) "Clip saved" else "Nothing new on the clipboard")
        finish()
    }

    private fun toast(t: String) = Toast.makeText(applicationContext, t, Toast.LENGTH_SHORT).show()
}

/** "Save clip" in quick settings: pull down, tap, and whatever is copied is kept. */
class ClipTileService : TileService() {
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ClipCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 9, intent, android.app.PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}
