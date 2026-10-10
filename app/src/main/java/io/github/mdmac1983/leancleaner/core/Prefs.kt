package io.github.mdmac1983.leancleaner.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.mdmac1983.leancleaner.dupes.KeepStrategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tiny observable settings store on top of SharedPreferences (no DataStore dependency needed). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _exclusions = MutableStateFlow(sp.getStringSet(KEY_EXCLUSIONS, null)?.toSet() ?: emptySet())
    val exclusions: StateFlow<Set<String>> = _exclusions.asStateFlow()

    private val _dupesMinSize = MutableStateFlow(sp.getLong(KEY_DUPES_MIN_SIZE, 100_000L))
    val dupesMinSize: StateFlow<Long> = _dupesMinSize.asStateFlow()

    private val _dupesSkipHidden = MutableStateFlow(sp.getBoolean(KEY_DUPES_SKIP_HIDDEN, true))
    val dupesSkipHidden: StateFlow<Boolean> = _dupesSkipHidden.asStateFlow()

    private val _dupesKeep = MutableStateFlow(
        KeepStrategy.entries.firstOrNull { it.name == sp.getString(KEY_DUPES_KEEP, null) } ?: KeepStrategy.OLDEST,
    )
    val dupesKeep: StateFlow<KeepStrategy> = _dupesKeep.asStateFlow()

    private val _largeFileThreshold = MutableStateFlow(sp.getLong(KEY_LARGE_THRESHOLD, 50_000_000L))
    val largeFileThreshold: StateFlow<Long> = _largeFileThreshold.asStateFlow()

    private val _totalFreed = MutableStateFlow(sp.getLong(KEY_TOTAL_FREED, 0L))
    val totalFreed: StateFlow<Long> = _totalFreed.asStateFlow()

    fun exclusionsSnapshot(): Exclusions = Exclusions(_exclusions.value)

    fun addExclusion(path: String) {
        val clean = path.trim().trimEnd('/')
        if (clean.isEmpty()) return
        update(_exclusions, _exclusions.value + clean) { putStringSet(KEY_EXCLUSIONS, it) }
    }

    fun removeExclusion(path: String) =
        update(_exclusions, _exclusions.value - path) { putStringSet(KEY_EXCLUSIONS, it) }

    fun setDupesMinSize(bytes: Long) = update(_dupesMinSize, bytes) { putLong(KEY_DUPES_MIN_SIZE, it) }

    fun setDupesSkipHidden(skip: Boolean) = update(_dupesSkipHidden, skip) { putBoolean(KEY_DUPES_SKIP_HIDDEN, it) }

    fun setDupesKeep(strategy: KeepStrategy) = update(_dupesKeep, strategy) { putString(KEY_DUPES_KEEP, it.name) }

    fun setLargeFileThreshold(bytes: Long) = update(_largeFileThreshold, bytes) { putLong(KEY_LARGE_THRESHOLD, it) }

    fun addFreed(bytes: Long) {
        if (bytes > 0) update(_totalFreed, _totalFreed.value + bytes) { putLong(KEY_TOTAL_FREED, it) }
    }

    private inline fun <T> update(
        flow: MutableStateFlow<T>,
        value: T,
        crossinline write: SharedPreferences.Editor.(T) -> Unit,
    ) {
        flow.value = value
        sp.edit { write(value) }
    }

    private companion object {
        const val KEY_EXCLUSIONS = "exclusions"
        const val KEY_DUPES_MIN_SIZE = "dupes_min_size"
        const val KEY_DUPES_SKIP_HIDDEN = "dupes_skip_hidden"
        const val KEY_DUPES_KEEP = "dupes_keep"
        const val KEY_LARGE_THRESHOLD = "large_file_threshold"
        const val KEY_TOTAL_FREED = "total_freed"
    }
}
