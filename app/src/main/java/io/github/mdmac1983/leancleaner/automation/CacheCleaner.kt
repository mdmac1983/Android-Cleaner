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

    private val _preparing = MutableStateFlow(false)

    /** True while targets are being measured, before the run starts (takes a few seconds). */
    val preparing: StateFlow<Boolean> = _preparing.asStateFlow()

    private val _completedRuns = MutableStateFlow(0)

    /** Increments after every run, so screens can refresh cache totals. */
    val completedRuns: StateFlow<Int> = _completedRuns.asStateFlow()

    private class Pending(val packages: List<String>, val cacheBefore: Long?)

    private var pending: Pending? = null
    private var observing = false

    /** Clears every app with a meaningful cache. */
    suspend fun cleanAll(context: Context): StartResult {
        val app = context.applicationContext
        return prepare(app) {
            clearOwnCache(app)
            AppRepository(app).cacheCleanTargets()
        }
    }

    /** Clears the given apps only. */
    suspend fun clean(context: Context, packages: Collection<String>): StartResult {
        val app = context.applicationContext
        return prepare(app) { AppRepository(app).cacheCleanTargets(packages) }
    }

    private suspend fun prepare(app: Context, findTargets: () -> List<CleanTarget>): StartResult {
        precheck(app)?.let { return it }
        _preparing.value = true
        try {
            val targets = withContext(Dispatchers.IO) { findTargets() }
            return start(app, targets)
        } finally {
            _preparing.value = false
        }
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
        _preparing.value || CacheAutomation.state.value is CacheAutomation.State.Running -> StartResult.BUSY
        else -> null
    }

    private fun start(app: Context, targets: List<CleanTarget>): StartResult {
        // Re-check: the service may have been switched off while targets were being measured.
        if (!CacheAutomation.isEnabled(app)) return StartResult.NEEDS_SETUP
        if (!CacheAutomation.isConnected) return StartResult.NOT_CONNECTED
        if (CacheAutomation.state.value is CacheAutomation.State.Running) return StartResult.BUSY
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
        val result = state.result ?: return "Cache cleaning stopped: ${state.error ?: "unexpected error"}"
        if (result.unsupported) {
            return "Couldn't find \"Clear cache\" in this phone's Settings, so nothing was changed. " +
                "Use the system dialog or App info instead."
        }
        return buildString {
            when (result.stoppedBy) {
                null, StopReason.USER -> Unit
                StopReason.SCREEN_OFF -> append("Stopped because the screen turned off. ")
                StopReason.PHONE_CALL -> append("Stopped for a call. ")
                StopReason.LEFT_SETTINGS -> append("Stopped because another app took over the screen. ")
            }
            append("Cleared the cache of ${Format.count(result.cleared.size, "app")}")
            if (freed != null && freed > 0) append(" · freed ${Format.size(freed)}")
            if (result.unconfirmed.isNotEmpty()) append(" · ${result.unconfirmed.size} not confirmed")
            if (result.failed.isNotEmpty()) append(" · ${result.failed.size} skipped")
            if (result.stoppedBy == StopReason.USER) append(" · stopped early")
        }
    }

    private fun clearOwnCache(app: Context) {
        (listOf(app.cacheDir) + app.externalCacheDirs.filterNotNull())
            .forEach { dir -> dir.listFiles()?.forEach { it.deleteRecursively() } }
    }
}
