package io.github.mdmac1983.leancleaner.dupes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mdmac1983.leancleaner.core.DeleteRequest
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DupesState {
    data object Idle : DupesState
    data class Scanning(val progress: DupeProgress?) : DupesState
    data class Results(val groups: List<DupeGroup>) : DupesState
    data class Deleting(val done: Int, val total: Int) : DupesState
}

class DupesViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.prefs

    private val _state = MutableStateFlow<DupesState>(DupesState.Idle)
    val state: StateFlow<DupesState> = _state.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    val keepStrategy = prefs.dupesKeep
    val minSize = prefs.dupesMinSize
    val skipHidden = prefs.dupesSkipHidden

    private var job: Job? = null

    fun scan() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = DupesState.Scanning(null)
            val context = getApplication<Application>()
            val groups = withContext(Dispatchers.IO) {
                DuplicateFinder(prefs.exclusionsSnapshot(), prefs.dupesMinSize.value, prefs.dupesSkipHidden.value)
                    .find(Volumes.roots(context)) { _state.value = DupesState.Scanning(it) }
            }
            _state.value = DupesState.Results(groups)
            autoSelect(prefs.dupesKeep.value)
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = DupesState.Idle
    }

    fun setMinSize(bytes: Long) = prefs.setDupesMinSize(bytes)

    fun setSkipHidden(skip: Boolean) = prefs.setDupesSkipHidden(skip)

    fun autoSelect(strategy: KeepStrategy) {
        prefs.setDupesKeep(strategy)
        _selected.value = groups().flatMapTo(HashSet()) { it.autoSelect(strategy) }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun toggle(group: DupeGroup, path: String) {
        val current = _selected.value
        if (path in current) {
            _selected.value = current - path
            return
        }
        val keptAfter = group.files.count { it.path !in current && it.path != path }
        if (keptAfter == 0) {
            _messages.trySend("Keep at least one copy")
            return
        }
        _selected.value = current + path
    }

    fun deleteSelected() {
        val groups = groups()
        val selected = _selected.value
        // Defensive: never delete every copy of a file, whatever the selection says.
        val chosen = groups.flatMap { group ->
            val picked = group.files.filter { it.path in selected }
            if (picked.size == group.files.size) picked.drop(1) else picked
        }
        if (chosen.isEmpty()) return
        job = viewModelScope.launch {
            _state.value = DupesState.Deleting(0, chosen.size)
            val result = deleteFiles(getApplication(), chosen.map { DeleteRequest(it.path) }) { done, total ->
                _state.value = DupesState.Deleting(done, total)
            }
            val remaining = groups.mapNotNull { group ->
                val files = group.files.filterNot { it.path in result.deleted }
                if (files.size > 1) group.copy(files = files) else null
            }
            _selected.value = _selected.value - result.deleted
            _state.value = DupesState.Results(remaining)
            _messages.send(result.summary())
        }
    }

    private fun groups(): List<DupeGroup> = (_state.value as? DupesState.Results)?.groups.orEmpty()
}
