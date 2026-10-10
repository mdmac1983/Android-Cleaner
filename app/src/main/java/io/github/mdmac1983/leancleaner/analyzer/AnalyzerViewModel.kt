package io.github.mdmac1983.leancleaner.analyzer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mdmac1983.leancleaner.core.DeleteRequest
import io.github.mdmac1983.leancleaner.core.FileKind
import io.github.mdmac1983.leancleaner.core.Volume
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
import java.io.File

sealed interface AnalyzerState {
    data object Idle : AnalyzerState
    data class Scanning(val path: String) : AnalyzerState
    class Ready(val result: AnalyzerResult, val volumes: List<Volume>, val largeFiles: List<LargeFile>) : AnalyzerState
    data class Deleting(val done: Int, val total: Int) : AnalyzerState
}

/** A row in the folder browser: a sub-folder (with its scanned size) or a file. */
data class Entry(val path: String, val size: Long, val node: DirNode?, val fileCount: Int) {
    val name: String get() = path.substringAfterLast('/')
    val isDirectory: Boolean get() = node != null
    val kind: FileKind get() = FileKind.of(name)
}

class AnalyzerViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<AnalyzerState>(AnalyzerState.Idle)
    val state: StateFlow<AnalyzerState> = _state.asStateFlow()

    /** Folder shown in the browser; null means the list of storage volumes. */
    private val _folder = MutableStateFlow<DirNode?>(null)
    val folder: StateFlow<DirNode?> = _folder.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var job: Job? = null
    private var listJob: Job? = null

    fun scan() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = AnalyzerState.Scanning("")
            val context = getApplication<Application>()
            val (result, volumes) = withContext(Dispatchers.IO) {
                val analyzer = StorageAnalyzer(context.prefs.largeFileThreshold.value)
                analyzer.analyze(Volumes.roots(context)) { _state.value = AnalyzerState.Scanning(it) } to Volumes.list(context)
            }
            _selected.value = emptySet()
            _state.value = AnalyzerState.Ready(result, volumes, result.largeFiles)
            open(result.roots.singleOrNull())
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = AnalyzerState.Idle
    }

    fun open(node: DirNode?) {
        _folder.value = node
        _selected.value = emptySet()
        listJob?.cancel()
        if (node == null) {
            _entries.value = emptyList()
            return
        }
        listJob = viewModelScope.launch {
            _entries.value = withContext(Dispatchers.IO) { listEntries(node) }
        }
    }

    /** @return false when already at the top. */
    fun up(): Boolean {
        val current = _folder.value ?: return false
        val roots = (state.value as? AnalyzerState.Ready)?.result?.roots.orEmpty()
        val parent = current.parent
        if (parent != null) {
            open(parent)
            return true
        }
        if (roots.size > 1) {
            open(null)
            return true
        }
        return false
    }

    fun toggle(path: String) {
        _selected.value = _selected.value.let { if (path in it) it - path else it + path }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /** Deletes the selection from either the folder browser or the large-files list. */
    fun deleteSelected(sizes: Map<String, Long>) {
        val ready = state.value as? AnalyzerState.Ready ?: return
        val paths = _selected.value.toList()
        if (paths.isEmpty()) return
        job = viewModelScope.launch {
            _state.value = AnalyzerState.Deleting(0, paths.size)
            val result = deleteFiles(getApplication(), paths.map { DeleteRequest(it) }) { done, total ->
                _state.value = AnalyzerState.Deleting(done, total)
            }
            for (path in result.deleted) removeFromTree(ready.result.roots, path, sizes[path] ?: 0L)
            _selected.value = emptySet()
            _state.value = AnalyzerState.Ready(
                ready.result,
                withContext(Dispatchers.IO) { Volumes.list(getApplication()) },
                ready.largeFiles.filterNot { file -> result.deleted.any { file.path == it || file.path.startsWith("$it/") } },
            )
            _folder.value?.let { open(it) }
            _messages.send(result.summary())
        }
    }

    private fun removeFromTree(roots: List<DirNode>, path: String, size: Long) {
        val parentPath = path.substringBeforeLast('/')
        val parent = findNode(roots, parentPath) ?: return
        val child = parent.children.firstOrNull { it.path == path }
        if (child != null) parent.removeChild(child) else parent.shrink(size, 1)
    }

    private fun findNode(roots: List<DirNode>, path: String): DirNode? {
        var node = roots.firstOrNull { path == it.path || path.startsWith(it.path + "/") } ?: return null
        while (node.path != path) {
            node = node.children.firstOrNull { path == it.path || path.startsWith(it.path + "/") } ?: return null
        }
        return node
    }

    private fun listEntries(node: DirNode): List<Entry> {
        val dirs = node.children.map { Entry(it.path, it.size, it, it.fileCount) }
        val files = File(node.path).listFiles()
            ?.filter { it.isFile }
            ?.map { Entry(it.absolutePath, it.length(), null, 1) }
            .orEmpty()
        return (dirs + files).sortedByDescending { it.size }
    }
}
