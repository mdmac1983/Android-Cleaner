package io.github.mdmac1983.leancleaner

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mdmac1983.leancleaner.analyzer.AnalyzerScreen
import io.github.mdmac1983.leancleaner.apps.AppsScreen
import io.github.mdmac1983.leancleaner.automation.AccessibilitySetupDialog
import io.github.mdmac1983.leancleaner.automation.CacheCleaner
import io.github.mdmac1983.leancleaner.core.Permissions
import io.github.mdmac1983.leancleaner.corpse.CorpseScreen
import io.github.mdmac1983.leancleaner.dashboard.DashboardScreen
import io.github.mdmac1983.leancleaner.dupes.DupesScreen
import io.github.mdmac1983.leancleaner.junk.JunkScreen
import io.github.mdmac1983.leancleaner.junk.JunkState
import io.github.mdmac1983.leancleaner.junk.JunkViewModel
import io.github.mdmac1983.leancleaner.monitor.MonitorScreen
import io.github.mdmac1983.leancleaner.settings.SettingsScreen
import io.github.mdmac1983.leancleaner.ui.theme.LeanCleanerTheme
import io.github.mdmac1983.leancleaner.widget.Widgets
import kotlinx.coroutines.flow.MutableStateFlow

enum class Route { DASHBOARD, JUNK, LEFTOVERS, DUPLICATES, ANALYZER, APPS, MONITOR, SETTINGS }

class MainActivity : ComponentActivity() {
    /** A pending action from a home screen widget button. */
    private val widgetAction = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Not again after rotation: the launch intent stays the same.
        if (savedInstanceState == null) widgetAction.value = Widgets.actionOf(intent)
        setContent {
            LeanCleanerTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val action by widgetAction.collectAsState()
                    AppRoot(action, onWidgetActionHandled = { widgetAction.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        widgetAction.value = Widgets.actionOf(intent)
    }
}

private val BackStackSaver = Saver<List<Route>, ArrayList<String>>(
    save = { stack -> ArrayList(stack.map { it.name }) },
    restore = { names -> names.map { Route.valueOf(it) } },
)

/**
 * A tiny back stack instead of the navigation library: eight screens don't need more.
 * Tool ViewModels are activity-scoped, so scan results survive going back to the dashboard.
 */
@Composable
private fun AppRoot(widgetAction: String?, onWidgetActionHandled: () -> Unit) {
    val context = LocalContext.current
    var stack by rememberSaveable(stateSaver = BackStackSaver) { mutableStateOf(listOf(Route.DASHBOARD)) }
    val holder = rememberSaveableStateHolder()
    val junk: JunkViewModel = viewModel()
    var cacheSetup by rememberSaveable { mutableStateOf(false) }
    if (cacheSetup) AccessibilitySetupDialog(onDismiss = { cacheSetup = false })

    fun open(route: Route) {
        stack = stack + route
    }

    fun quickClean() {
        if (Permissions.hasStorageAccess(context) && junk.state.value !is JunkState.Scanning) junk.scan()
    }

    LaunchedEffect(widgetAction) {
        when (widgetAction) {
            Widgets.ACTION_QUICK_CLEAN -> {
                quickClean()
                stack = listOf(Route.DASHBOARD, Route.JUNK)
            }
            Widgets.ACTION_CLEAR_CACHES -> {
                stack = listOf(Route.DASHBOARD, Route.JUNK)
                when (val result = CacheCleaner.cleanAll(context)) {
                    CacheCleaner.StartResult.NEEDS_SETUP -> cacheSetup = true
                    else -> CacheCleaner.message(result)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                }
            }
            Widgets.ACTION_MONITOR -> stack = listOf(Route.DASHBOARD, Route.MONITOR)
            Widgets.ACTION_OPEN -> Unit
            else -> return@LaunchedEffect
        }
        onWidgetActionHandled()
    }

    fun back() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    BackHandler(enabled = stack.size > 1) { back() }

    AnimatedContent(
        targetState = stack.last(),
        transitionSpec = {
            val forward = targetState != Route.DASHBOARD
            (slideInHorizontally { if (forward) it / 6 else -it / 6 } + fadeIn()) togetherWith
                (slideOutHorizontally { if (forward) -it / 6 else it / 6 } + fadeOut())
        },
        label = "screen",
    ) { route ->
        holder.SaveableStateProvider(route.name) {
            when (route) {
                Route.DASHBOARD -> DashboardScreen(
                    onOpen = ::open,
                    onQuickClean = {
                        quickClean()
                        open(Route.JUNK)
                    },
                )
                Route.JUNK -> JunkScreen(onBack = ::back)
                Route.LEFTOVERS -> CorpseScreen(onBack = ::back)
                Route.DUPLICATES -> DupesScreen(onBack = ::back)
                Route.ANALYZER -> AnalyzerScreen(onBack = ::back)
                Route.APPS -> AppsScreen(onBack = ::back)
                Route.MONITOR -> MonitorScreen(onBack = ::back)
                Route.SETTINGS -> SettingsScreen(onBack = ::back)
            }
        }
    }
}
