package app.ultimate.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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

@Composable
fun UltimateRoot(app: UltimateApp) {
    val shell = app.shell
    val route by shell.route.collectAsStateWithLifecycle()
    val incoming by shell.incoming.collectAsStateWithLifecycle()
    val context = LocalContext.current
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { app.insights.pause() }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) { app.insights.resume() }
    val standalone = app.tools.size == 1
    val nav = remember { ShellNav(standalone, openLibrary = shell::showLibraryForCurrentTool, openActivity = shell::showActivity, tools = app.tools, send = { t, uris -> shell.open(t, ToolLaunch(uris)) }) }

    CompositionLocalProvider(LocalShellNav provides nav) {
        if (standalone) StandaloneRoot(app, route, onExitApp = { (context as? Activity)?.finish() }) else SuiteRoot(app, route)
    }

    incoming?.let { pending -> HandSheet(app, pending) }

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
            val toolRoute = (if (intoTool) targetState else initialState) as? Route.InTool
            val origin = toolRoute?.origin ?: EntryOrigin.OTHER
            val ms = if (!intoTool && app.shell.lastVisitMs < 8_000L) 220 else 320
            val spec = tween<androidx.compose.ui.unit.IntOffset>(ms, easing = EaseOutCubic)
            val entering = when (origin) {
                EntryOrigin.ORB_LEFT -> slideInHorizontally(spec) { -it / 3 }
                EntryOrigin.ORB_RIGHT -> slideInHorizontally(spec) { it / 3 }
                else -> slideInVertically(spec) { it / 5 }
            }
            val leaving = when (origin) {
                EntryOrigin.ORB_LEFT -> slideOutHorizontally(spec) { -it / 3 }
                EntryOrigin.ORB_RIGHT -> slideOutHorizontally(spec) { it / 3 }
                else -> slideOutVertically(spec) { it / 5 }
            }
            if (intoTool) (entering + fadeIn(tween(ms))) togetherWith (scaleOut(tween(ms), targetScale = 0.97f) + fadeOut(tween(ms)))
            else (scaleIn(tween(ms), initialScale = 0.97f) + fadeIn(tween(ms))) togetherWith (leaving + fadeOut(tween(ms)))
        },
    ) { shown ->
        when (shown) {
            is Route.InTool -> {
                BackHandler(onBack = shell::exitTool)
                val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
                    if (!keyboardOpen) SuiteNavigation(app, shown, active)
                }) { padding ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding).fillMaxSize()) {
                        androidx.compose.runtime.key(shown.tool.id) { shown.tool.Screen(onExit = shell::exitTool, launch = shown.launch) }
                        ToolSwitcher(app, shown.tool)
                    }
                }
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
        bottomBar = { SuiteNavigation(app, route, active) },
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

/** Consistent, labeled destinations, also reachable from inside a tool. */
@Composable
private fun SuiteNavigation(app: UltimateApp, route: Route, active: Boolean) {
    val shell = app.shell
    NavigationBar {
        NavigationBarItem(route == Route.Home, shell::showHome, modifier = Modifier.testTag("nav-home"), icon = { BouncyIcon(Icons.Rounded.Home, route == Route.Home) }, label = { Text("Home") })
        NavigationBarItem(route == Route.Library, { shell.showLibrary() }, modifier = Modifier.testTag("nav-library"), icon = { BouncyIcon(Icons.Rounded.VideoLibrary, route == Route.Library) }, label = { Text("Library") })
        app.tools.firstOrNull { it.id == "terminal" }?.let { terminal ->
            val selected = (route as? Route.InTool)?.tool?.id == terminal.id
            NavigationBarItem(selected, { if (!selected) shell.open(terminal) }, modifier = Modifier.testTag("nav-terminal"), icon = { BouncyIcon(Icons.Rounded.Terminal, selected) }, label = { Text("Terminal") })
        }
        NavigationBarItem(route == Route.Activity, shell::showActivity, modifier = Modifier.testTag("nav-activity"),
            icon = { BadgedBox(badge = { if (active) Badge() }) { BouncyIcon(Icons.Rounded.History, route == Route.Activity) } },
            label = { Text("Activity") })
    }
}

/** The selected tab's icon hops and grows a touch, so the bar answers the tap. */
@Composable
private fun BouncyIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean) {
    val scale by animateFloatAsState(if (selected) 1.15f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow), label = "tabIcon")
    val lift by animateFloatAsState(if (selected) -2f else 0f, spring(Spring.DampingRatioMediumBouncy), label = "tabLift")
    Icon(icon, null, androidx.compose.ui.Modifier.graphicsLayer { scaleX = scale; scaleY = scale; translationY = lift * density })
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
