package app.ultimate.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.LocalShellNav
import app.ultimate.core.ShellNav
import app.ultimate.core.ToolLaunch
import kotlinx.coroutines.flow.combine
import app.ultimate.core.GooeyNavBar
import app.ultimate.core.GooeyNavItem

@Composable
fun UltimateRoot(app: UltimateApp) {
    val shell = app.shell
    val route by shell.route.collectAsStateWithLifecycle()
    val incoming by shell.incoming.collectAsStateWithLifecycle()
    val context = LocalContext.current
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { app.insights.pause() }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) { app.insights.resume() }
    val standalone = app.tools.size == 1
    val nav = remember { ShellNav(standalone, openLibrary = shell::showLibraryForCurrentTool, openActivity = shell::showActivity, openAbout = shell::showAbout, tools = app.tools, send = { t, uris -> shell.open(t, ToolLaunch(uris)) }) }

    CompositionLocalProvider(LocalShellNav provides nav) {
        if (standalone) StandaloneRoot(app, route, onExitApp = { (context as? Activity)?.finish() }) else SuiteRoot(app, route)
    }

    incoming?.let { pending -> HandSheet(app, pending) }
    val showAbout by shell.about.collectAsStateWithLifecycle()
    if (showAbout) AboutScreen(app, onClose = shell::hideAbout)
    WhatsNewOnUpdate(app)

}

/** The full app: Home, Library and Activity tabs, with tools opening full screen. */
@Composable
private fun SuiteRoot(app: UltimateApp, route: Route) {
    val shell = app.shell
    val active by remember { combine(app.work.sources.map { it.work }) { s -> s.any { it.active } } }.collectAsStateWithLifecycle(false)
    // A tool zooms up out of Home and settles back into it; the tabs slide towards the side they sit on.
    AnimatedContent(
        route, label = "route",
        contentKey = { r -> if (r is Route.InTool) "tool:${r.tool.id}" else "tabs" },
        transitionSpec = {
            val intoTool = targetState is Route.InTool
            if (intoTool) (scaleIn(tween(320, easing = EaseOutBack), initialScale = 0.9f) + fadeIn(tween(220))) togetherWith (scaleOut(tween(260), targetScale = 1.04f) + fadeOut(tween(180)))
            else (scaleIn(tween(300), initialScale = 1.04f) + fadeIn(tween(240))) togetherWith (scaleOut(tween(240), targetScale = 0.92f) + fadeOut(tween(200)))
        },
    ) { shown ->
        when (shown) {
            is Route.InTool -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                // Back from any tool returns to where you came from (Home, Library or Activity). Tools with their own
                // steps (an editor, a sub-screen) register their own Back after this one, so theirs comes first.
                BackHandler(onBack = shell::exitTool)
                // Each tool keeps its own state while the user hops between them.
                androidx.compose.runtime.key(shown.tool.id) { shown.tool.Screen(onExit = shell::exitTool, launch = shown.launch) }
                ToolSwitcher(app, shown.tool)
            }
            else -> Tabs(app, if (route is Route.InTool) shown else route, active)
        }
    }
}

private fun tabIndex(r: Route) = when (r) { Route.Library -> 1; Route.Activity -> 2; else -> 0 }

@Composable
private fun Tabs(app: UltimateApp, route: Route, active: Boolean) {
    val shell = app.shell
    BackHandler(enabled = route != Route.Home, onBack = shell::showHome)
    Scaffold(
        bottomBar = {
            // The highlight is a drop of liquid that stretches across to the tab you pick and lands with a squish.
            GooeyNavBar(
                listOf(GooeyNavItem("Home", Icons.Rounded.Home), GooeyNavItem("Library", Icons.Rounded.VideoLibrary), GooeyNavItem("Activity", Icons.Rounded.History, badge = active)),
                tabIndex(route),
                onSelect = { i -> when (i) { 0 -> shell.showHome(); 1 -> shell.showLibrary(); else -> shell.showActivity() } },
            )
        },
    ) { pad ->
        AnimatedContent(
            route, label = "tab",
            transitionSpec = {
                val dir = if (tabIndex(targetState) > tabIndex(initialState)) 1 else -1
                (slideInHorizontally(tween(320, easing = EaseOutCubic)) { it / 5 * dir } + fadeIn(tween(260))) togetherWith
                    (slideOutHorizontally(tween(260)) { -it / 5 * dir } + fadeOut(tween(180)))
            },
        ) { r ->
            when (r) {
                Route.Library -> LibraryScreen(app, pad)
                Route.Activity -> ActivityScreen(app, pad)
                else -> HomeScreen(app, pad)
            }
        }
    }
}

/** A build with one tool: the tool is the home screen; Library and Activity open from its top bar. */
@Composable
private fun StandaloneRoot(app: UltimateApp, route: Route, onExitApp: () -> Unit) {
    val shell = app.shell
    val tool = app.tools.single()
    when (route) {
        Route.Library, Route.Activity -> {
            BackHandler(onBack = shell::showHome)
            androidx.compose.material3.Scaffold(topBar = { PageBar(if (route == Route.Library) "Library" else "Activity", shell::showHome) }) { pad ->
                // Standalone bars already give a back arrow, so the screens drop their own top padding duplication.
                val inner = PaddingValues(top = pad.calculateTopPadding())
                if (route == Route.Library) LibraryScreen(app, inner) else ActivityScreen(app, inner)
            }
        }
        is Route.InTool -> tool.Screen(onExit = shell::exitTool, launch = route.launch)
        else -> tool.Screen(onExit = onExitApp, launch = remember { ToolLaunch() })
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PageBar(title: String, onBack: () -> Unit) {
    androidx.compose.material3.TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            androidx.compose.material3.IconButton(onBack) {
                Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
        },
    )
}
