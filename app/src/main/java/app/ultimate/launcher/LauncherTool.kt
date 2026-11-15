package app.ultimate.launcher

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.core.GlassCard
import app.ultimate.core.LibraryItem
import app.ultimate.core.Liquid
import app.ultimate.core.LiquidButton
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where you turn Ultimate's home screen on (and off again). Android itself asks, with its own dialog. */
class LauncherTool(private val context: android.content.Context) : Tool {
    override val id = "launcher"
    override val title = "Home screen"
    override val tagline = "A calm home with a liquid letter rail"
    override val icon = Icons.Rounded.Home
    override val headline: StateFlow<String> = MutableStateFlow("Make Ultimate your home screen")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override val acceptedMimePrefixes: List<String> = emptyList()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit

    private fun isHome(): Boolean = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.name == HomeActivity::class.java.name

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        val ctx = LocalContext.current
        var tick by remember { mutableIntStateOf(0) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
        val home = remember(tick) { isHome() }
        LaunchedEffect(Unit) { tick++ }
        Scaffold(containerColor = Liquid.bg, topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Liquid.bg, titleContentColor = Color.White, navigationIconContentColor = Color.White),
                title = { Text("Home screen") }, navigationIcon = { IconButton(onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
        }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(Liquid.gutter()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GlassCard(title = if (home) "Ultimate is your home screen" else "A calmer home screen", live = home) {
                    Text("The time, a few favourites, and a liquid letter rail down the right edge. Slide your thumb down it: the letters swell out around your thumb, the letter pops into a bubble, and its apps spring in beside it. Slide onto one and let go to open it. Swipe up anywhere to search. Long-press an app to make it a favourite.",
                        color = Liquid.dim, fontSize = 14.sp, lineHeight = 21.sp)
                    LiquidButton("Try it", { ctx.startActivity(Intent(ctx, HomeActivity::class.java)) }, Modifier.fillMaxWidth())
                    if (!home) LiquidButton("Set as my home screen", {
                        val rm = if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(RoleManager::class.java) else null
                        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) ask.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                        else ask.launch(Intent(Settings.ACTION_HOME_SETTINGS))
                    }, Modifier.fillMaxWidth())
                    else Text("Pressing Home now brings you here.", color = Liquid.teal, fontSize = 14.sp)
                    Text(if (home) "To go back to your old home screen, choose it in Android's home app settings." else "Android asks you to confirm. You can switch back any time.", color = Liquid.dim, fontSize = 12.sp)
                    if (home) LiquidButton("Switch back", { ask.launch(Intent(Settings.ACTION_HOME_SETTINGS)) }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
