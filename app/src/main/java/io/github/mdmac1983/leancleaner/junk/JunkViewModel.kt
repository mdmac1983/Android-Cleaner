package io.github.mdmac1983.leancleaner.junk

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mdmac1983.leancleaner.apps.AppRepository
import io.github.mdmac1983.leancleaner.apps.PackageManagerApkInspector
import io.github.mdmac1983.leancleaner.automation.CacheCleaner
import io.github.mdmac1983.leancleaner.core.DeleteRequest
import io.github.mdmac1983.leancleaner.core.Format
import io.github.mdmac1983.leancleaner.core.Volumes
import io.github.mdmac1983.leancleaner.core.deleteFiles
import io.github.mdmac1983.leancleaner.core.summary
import io.github.mdmac1983.leancleaner.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface JunkState {
    data object Idle : JunkState
    data class Scanning(val path: String) : JunkState
    data class Results(val items: List<JunkItem>) : JunkState
    data class Deleting(val done: Int, val total: Int) : JunkState
}

class JunkViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<JunkState>(JunkState.Idle)
    val state: StateFlow<JunkState> = _state.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    /** Total internal cache of all apps, when usage access allows measuring it. */
    private val _appCacheBytes = MutableStateFlow<Long?>(null)
    val appCacheBytes: StateFlow<Long?> = _appCacheBytes.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var job: Job? = null

    init {
        // Refresh the cache total after an automated cache-clearing run.
        viewModelScope.launch { CacheCleaner.completedRuns.drop(1).collect { refreshAppCache() } }
    }

    fun scan() {
        job?.cancel()
        refreshAppCache()
        job = viewModelScope.launch {
            _state.value = JunkState.Scanning("")
            val context = getApplication<Application>()
            val items = withContext(Dispatchers.IO) {
                JunkScanner(context.prefs.exclusionsSnapshot(), PackageManagerApkInspector(context))
                    .scan(Volumes.roots(context)) { _state.value = JunkState.Scanning(it) }
            }
            _selected.value = items.filter { it.preselected }.mapTo(HashSet()) { it.path }
            _state.value = JunkState.Results(items)
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = JunkState.Idle
    }

    fun toggle(path: String) {
        _selected.value = _selected.value.let { if (path in it) it - path else it + path }
    }

    fun setCategory(category: JunkCategory, select: Boolean) {
        val paths = results().filter { it.category == category }.map { it.path }
        _selected.value = if (select) _selected.value + paths else _selected.value - paths.toSet()
    }

    fun exclude(path: String) {
        getApplication<Application>().prefs.addExclusion(path)
        val remaining = results().filterNot { it.path == path || it.path.startsWith("$path/") }
        val remainingPaths = remaining.mapTo(HashSet()) { it.path }
        _selected.value = _selected.value.filterTo(HashSet()) { it in remainingPaths }
        _state.value = JunkState.Results(remaining)
        _messages.trySend("Excluded from future scans")
    }

    fun deleteSelected() {
        val items = results()
        val chosen = items.filter { it.path in _selected.value }
        if (chosen.isEmpty()) return
        job = viewModelScope.launch {
            _state.value = JunkState.Deleting(0, chosen.size)
            val result = deleteFiles(
                getApplication(),
                chosen.map { DeleteRequest(it.path, it.contentsOnly) },
            ) { done, total -> _state.value = JunkState.Deleting(done, total) }
            _selected.value = _selected.value - result.deleted
            _state.value = JunkState.Results(items.filterNot { it.path in result.deleted })
            _messages.send(result.summary())
        }
    }

    /** Called after the system "clear all app caches" dialog returns. */
    fun onSystemCacheCleared(cleared: Boolean) {
        val before = _appCacheBytes.value
        viewModelScope.launch {
            val after = withContext(Dispatchers.IO) { AppRepository(getApplication()).totalCacheBytes() }
            _appCacheBytes.value = after
            if (!cleared) return@launch
            val freed = if (before != null && after != null) (before - after).coerceAtLeast(0) else 0
            getApplication<Application>().prefs.addFreed(freed)
            _messages.send(if (freed > 0) "Cleared app caches · freed ${Format.size(freed)}" else "App caches cleared")
        }
    }

    fun showMessage(text: String) {
        _messages.trySend(text)
    }

    fun refreshAppCache() {
        viewModelScope.launch {
            _appCacheBytes.value = withContext(Dispatchers.IO) { AppRepository(getApplication()).totalCacheBytes() }
        }
    }

    private fun results(): List<JunkItem> = (_state.value as? JunkState.Results)?.items.orEmpty()
}
