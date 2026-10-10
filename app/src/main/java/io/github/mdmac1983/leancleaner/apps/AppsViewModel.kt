package io.github.mdmac1983.leancleaner.apps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mdmac1983.leancleaner.core.Format
import io.github.mdmac1983.leancleaner.core.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

enum class AppFilter(val label: String) { USER("Installed"), SYSTEM("System"), ALL("All") }

enum class AppSort(val label: String) {
    NAME("Name"),
    SIZE("Total size"),
    CACHE("Cache size"),
    LAST_USED("Least recently used"),
    INSTALLED("Recently installed"),
}

class AppsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppRepository(app)

    private val _apps = MutableStateFlow<List<AppInfo>?>(null)
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()
    val isLoaded: StateFlow<Boolean> = _apps.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _hasUsageAccess = MutableStateFlow(Permissions.hasUsageAccess(app))
    val hasUsageAccess: StateFlow<Boolean> = _hasUsageAccess.asStateFlow()

    val filter = MutableStateFlow(AppFilter.USER)
    val sort = MutableStateFlow(AppSort.NAME)
    val query = MutableStateFlow("")
    val unusedOnly = MutableStateFlow(false)

    val visible: StateFlow<List<AppInfo>> = combine(_apps, filter, sort, query, unusedOnly) { apps, filter, sort, query, unused ->
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(UNUSED_DAYS)
        apps.orEmpty()
            .asSequence()
            .filter {
                when (filter) {
                    AppFilter.USER -> !it.isSystem
                    AppFilter.SYSTEM -> it.isSystem
                    AppFilter.ALL -> true
                }
            }
            .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            .filter { !unused || (it.lastUsed ?: 0L) < cutoff }
            .sortedWith(comparator(sort))
            .toList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _uninstallQueue = MutableStateFlow<List<String>>(emptyList())
    val uninstallQueue: StateFlow<List<String>> = _uninstallQueue.asStateFlow()
    private var uninstallInFlight: String? = null

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun load(force: Boolean = false) {
        val access = Permissions.hasUsageAccess(getApplication())
        val accessChanged = access != _hasUsageAccess.value
        _hasUsageAccess.value = access
        if (_apps.value != null && !force && !accessChanged) return
        viewModelScope.launch {
            _progress.value = 0f
            _apps.value = withContext(Dispatchers.IO) {
                repo.loadApps { done, total -> _progress.value = if (total > 0) done.toFloat() / total else 0f }
            }
        }
    }

    fun find(packageName: String): AppInfo? = _apps.value?.firstOrNull { it.packageName == packageName }

    fun toggle(packageName: String) {
        _selected.value = _selected.value.let { if (packageName in it) it - packageName else it + packageName }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun uninstall(packages: Collection<String>) {
        val userApps = packages.filter { pkg -> find(pkg)?.isSystem == false }
        _uninstallQueue.value = _uninstallQueue.value + userApps.filterNot { it in _uninstallQueue.value }
    }

    /** Next package whose system uninstall dialog should be shown, or null if one is already showing. */
    fun nextUninstall(): String? {
        val head = _uninstallQueue.value.firstOrNull() ?: return null
        if (uninstallInFlight != null) return null
        uninstallInFlight = head
        return head
    }

    fun onUninstallDialogClosed() {
        val pkg = uninstallInFlight ?: return
        uninstallInFlight = null
        _uninstallQueue.value = _uninstallQueue.value.drop(1)
        if (!repo.isInstalled(pkg)) {
            _apps.value = _apps.value?.filterNot { it.packageName == pkg }
            _selected.value = _selected.value - pkg
        }
    }

    fun export(packages: Collection<String>) {
        val apps = packages.mapNotNull(::find)
        if (apps.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            var saved = 0
            var location = ""
            for (app in apps) {
                runCatching { ApkExporter.export(getApplication(), app) }
                    .onSuccess {
                        saved++
                        location = it.substringBeforeLast('/')
                    }
                    .onFailure { _messages.send("Couldn't export ${app.label}: ${it.message}") }
            }
            _busy.value = false
            if (saved > 0) _messages.send("Saved ${Format.count(saved, "app")} to $location")
        }
    }

    private fun comparator(sort: AppSort): Comparator<AppInfo> {
        val byName = compareBy<AppInfo, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
        return when (sort) {
            AppSort.NAME -> byName
            AppSort.SIZE -> compareByDescending<AppInfo> { it.totalBytes ?: -1 }.then(byName)
            AppSort.CACHE -> compareByDescending<AppInfo> { it.cacheBytes ?: -1 }.then(byName)
            AppSort.LAST_USED -> compareBy<AppInfo> { it.lastUsed ?: 0L }.then(byName)
            AppSort.INSTALLED -> compareByDescending<AppInfo> { it.installTime }.then(byName)
        }
    }

    companion object {
        const val UNUSED_DAYS = 30L
    }
}
