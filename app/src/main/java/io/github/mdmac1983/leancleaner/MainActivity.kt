package io.github.mdmac1983.leancleaner

import android.os.Bundle
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

enum class Route { DASHBOARD, JUNK, LEFTOVERS, DUPLICATES, ANALYZER, APPS, MONITOR, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LeanCleanerTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
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
private fun AppRoot() {
    val context = LocalContext.current
    var stack by rememberSaveable(stateSaver = BackStackSaver) { mutableStateOf(listOf(Route.DASHBOARD)) }
    val holder = rememberSaveableStateHolder()
    val junk: JunkViewModel = viewModel()

    fun open(route: Route) {
        stack = stack + route
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
                        if (Permissions.hasStorageAccess(context) && junk.state.value !is JunkState.Scanning) junk.scan()
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
