package app.ultimate.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
        setContent {
            UltimateTheme {
                LauncherHome(repo, presses.intValue, onOpenUltimate = { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onOpenTool = { tool, source -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_TOOL, tool).also { i -> source?.let { i.putExtra(MainActivity.EXTRA_SOURCE, it) } }) })
            }
        }
    }

    /** Home pressed while already home: close search and the letter panel. */
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); presses.intValue++ }

    /** Apps may have been installed or removed while away. */
    override fun onResume() { super.onResume(); presses.intValue++; lifecycleScope.launch { repo.load() } }

    @Deprecated("Home has nowhere to go back to.")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() = Unit
}
