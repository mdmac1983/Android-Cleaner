package io.github.mdmac1983.leancleaner.automation

import android.content.Context
import io.github.mdmac1983.leancleaner.apps.AppRepository
import io.github.mdmac1983.leancleaner.core.Format
import io.github.mdmac1983.leancleaner.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Starts automated cache-clearing runs and reports how they went (including how much was freed,
 * when usage access lets us measure it).
 */
object CacheCleaner {

    enum class StartResult { STARTED, NEEDS_SETUP, NOT_CONNECTED, NOTHING_TO_CLEAN, BUSY }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _messages = Channel<String>(Channel.BUFFERED)

    /** One-off summaries ("Cleared the cache of 42 apps · freed 1.2 GB") for whichever screen is visible. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _completedRuns = MutableStateFlow(0)

    /** Increments after every run, so screens can refresh cache totals. */
    val completedRuns: StateFlow<Int> = _completedRuns.asStateFlow()

    private class Pending(val packages: List<String>, val cacheBefore: Long?)

    private var pending: Pending? = null
    private var observing = false

    /** Clears every app with a meaningful cache. */
    suspend fun cleanAll(context: Context): StartResult {
        val app = context.applicationContext
        precheck(app)?.let { return it }
        val targets = withContext(Dispatchers.IO) {
            clearOwnCache(app)
            AppRepository(app).cacheCleanTargets()
        }
        return start(app, targets)
    }

    /** Clears the given apps only. */
    suspend fun clean(context: Context, packages: Collection<String>): StartResult {
        val app = context.applicationContext
        precheck(app)?.let { return it }
        val targets = withContext(Dispatchers.IO) { AppRepository(app).cacheCleanTargets(packages) }
        return start(app, targets)
    }

    fun message(result: StartResult): String? = when (result) {
        StartResult.STARTED -> null
        StartResult.NEEDS_SETUP -> "Turn on the accessibility service first"
        StartResult.NOT_CONNECTED -> "The accessibility service isn't running. Turn it off and on again in Settings."
        StartResult.NOTHING_TO_CLEAN -> "No app caches worth clearing right now"
        StartResult.BUSY -> "A cleaning run is already in progress"
    }

    private fun precheck(app: Context): StartResult? = when {
        !CacheAutomation.isEnabled(app) -> StartResult.NEEDS_SETUP
        !CacheAutomation.isConnected -> StartResult.NOT_CONNECTED
        CacheAutomation.state.value is CacheAutomation.State.Running -> StartResult.BUSY
        else -> null
    }

    private fun start(app: Context, targets: List<CleanTarget>): StartResult {
        precheck(app)?.let { return it }
        if (targets.isEmpty()) return StartResult.NOTHING_TO_CLEAN
        observe(app)
        val known = targets.mapNotNull { it.cacheBytes }
        pending = Pending(targets.map { it.packageName }, if (known.size == targets.size) known.sum() else null)
        if (!CacheAutomation.start(targets)) {
            pending = null
            return StartResult.NOT_CONNECTED
        }
        return StartResult.STARTED
    }

    private fun observe(app: Context) {
        if (observing) return
        observing = true
        scope.launch {
            CacheAutomation.state.collect { state ->
                if (state is CacheAutomation.State.Finished) onFinished(app, state)
            }
        }
    }

    private suspend fun onFinished(app: Context, state: CacheAutomation.State.Finished) {
        val run = pending
        pending = null
        val freed = if (run?.cacheBefore != null) {
            val after = withContext(Dispatchers.IO) { AppRepository(app).cacheSizes(run.packages) }.values.sum()
            (run.cacheBefore - after).coerceAtLeast(0)
        } else {
            null
        }
        if (freed != null) app.prefs.addFreed(freed)
        _messages.send(summary(state, freed))
        CacheAutomation.acknowledge()
        _completedRuns.value++
    }

    private fun summary(state: CacheAutomation.State.Finished, freed: Long?): String {
        val result = state.result ?: return "Cache cleaning stopped: ${state.error ?: "unknown error"}"
        if (result.unsupported) {
            return "Couldn't find \"Clear cache\" in this phone's Settings, so nothing was changed. " +
                "Use the system dialog or App info instead."
        }
        return buildString {
            append("Cleared the cache of ${Format.count(result.cleared.size, "app")}")
            if (freed != null && freed > 0) append(" · freed ${Format.size(freed)}")
            if (result.failed.isNotEmpty()) append(" · ${result.failed.size} skipped")
            if (result.cancelled) append(" · stopped early")
        }
    }

    private fun clearOwnCache(app: Context) {
        (listOf(app.cacheDir) + app.externalCacheDirs.filterNotNull())
            .forEach { dir -> dir.listFiles()?.forEach { it.deleteRecursively() } }
    }
}
