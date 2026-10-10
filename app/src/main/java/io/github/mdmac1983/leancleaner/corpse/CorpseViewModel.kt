package io.github.mdmac1983.leancleaner.corpse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mdmac1983.leancleaner.apps.AppRepository
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

sealed interface CorpseState {
    data object Idle : CorpseState
    data class Scanning(val path: String) : CorpseState
    data class Results(val corpses: List<Corpse>) : CorpseState
    data class Deleting(val done: Int, val total: Int) : CorpseState
}

class CorpseViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<CorpseState>(CorpseState.Idle)
    val state: StateFlow<CorpseState> = _state.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var job: Job? = null

    fun scan() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = CorpseState.Scanning("")
            val context = getApplication<Application>()
            val corpses = withContext(Dispatchers.IO) {
                val installed = AppRepository(context).installedPackageNames() + context.packageName
                CorpseFinder({ it in installed }, context.prefs.exclusionsSnapshot())
                    .find(Volumes.roots(context)) { _state.value = CorpseState.Scanning(it) }
            }
            _selected.value = corpses.filter { it.preselected }.mapTo(HashSet()) { it.path }
            _state.value = CorpseState.Results(corpses)
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = CorpseState.Idle
    }

    fun toggle(path: String) {
        _selected.value = _selected.value.let { if (path in it) it - path else it + path }
    }

    fun selectAll(select: Boolean) {
        _selected.value = if (select) results().mapTo(HashSet()) { it.path } else emptySet()
    }

    fun exclude(path: String) {
        getApplication<Application>().prefs.addExclusion(path)
        _selected.value = _selected.value - path
        _state.value = CorpseState.Results(results().filterNot { it.path == path })
        _messages.trySend("Excluded from future scans")
    }

    fun deleteSelected() {
        val all = results()
        val chosen = all.filter { it.path in _selected.value }
        if (chosen.isEmpty()) return
        job = viewModelScope.launch {
            _state.value = CorpseState.Deleting(0, chosen.size)
            val result = deleteFiles(getApplication(), chosen.map { DeleteRequest(it.path) }) { done, total ->
                _state.value = CorpseState.Deleting(done, total)
            }
            _selected.value = _selected.value - result.deleted
            _state.value = CorpseState.Results(all.filterNot { it.path in result.deleted })
            _messages.send(result.summary())
        }
    }

    private fun results(): List<Corpse> = (_state.value as? CorpseState.Results)?.corpses.orEmpty()
}
