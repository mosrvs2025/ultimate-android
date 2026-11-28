package app.ultimate.launcher

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import app.ultimate.MainActivity
import app.ultimate.core.UltimateTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * The home screen activity Android shows when you press Home, once you have chosen Ultimate as your home app. It is separate from
 * Ultimate's own window, so opening Ultimate from home and pressing Home again comes back here as with any launcher.
 */
class HomeActivity : ComponentActivity() {
    private val presses = mutableIntStateOf(0)
    private lateinit var repo: LauncherRepo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = LauncherRepo(this)
        // Home has nowhere to go back to, so Back on the bare home screen does nothing instead of leaving it. This is added before the screen
        // is composed, so every Back handler the screen adds later (closing search, a settings page, the edit panel) is asked first.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = Unit })
        // Newer Android hands Back to the app through its own callback list. This one is asked first, counts the press (the launcher settings
        // show the count, to tell "Back never arrived" from "Back arrived and nothing changed") and passes it on to the handlers above.
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { LauncherBack.presses++; onBackPressedDispatcher.onBackPressed() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb)
            platformBack = cb
        }
        setContent {
            UltimateTheme {
                LauncherHome(repo, presses.intValue, onOpenUltimate = { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onOpenTool = { tool, source -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_TOOL, tool).also { i -> source?.let { i.putExtra(MainActivity.EXTRA_SOURCE, it) } }) })
            }
        }
    }

    private var platformBack: OnBackInvokedCallback? = null

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) platformBack?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        super.onDestroy()
    }

    /** Back delivered the old way (Android 12 and 13, a keyboard's Back key): counted, then handed to the same handlers. */
    @Deprecated("Counted, then passed on to the dispatcher.")
    override fun onBackPressed() { LauncherBack.presses++; @Suppress("DEPRECATION") super.onBackPressed() }

    /** Home pressed while already home: close search and the letter panel. */
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); presses.intValue++ }

    /** Apps may have been installed or removed while away. */
    override fun onResume() { super.onResume(); presses.intValue++; lifecycleScope.launch { repo.load() } }
}
