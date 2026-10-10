package io.github.mdmac1983.leancleaner.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * @param contentsOnly delete what is inside the directory but keep the directory itself
 * (used for folders the system or an app expects to exist, like LOST.DIR or cache dirs).
 */
data class DeleteRequest(val path: String, val contentsOnly: Boolean = false)

data class DeleteResult(
    val freedBytes: Long,
    val deleted: Set<String>,
    val failed: List<String>,
) {
    companion object {
        val EMPTY = DeleteResult(0, emptySet(), emptyList())
    }
}

class Deleter(private val guard: SafetyGuard) {

    suspend fun delete(
        requests: List<DeleteRequest>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): DeleteResult {
        var freed = 0L
        val deleted = LinkedHashSet<String>()
        val failed = ArrayList<String>()
        requests.forEachIndexed { index, request ->
            currentCoroutineContext().ensureActive()
            onProgress(index, requests.size)
            val file = File(request.path)
            if (!guard.canDelete(file)) {
                failed += request.path
                return@forEachIndexed
            }
            if (!file.exists()) {
                // Already gone (e.g. its parent was deleted earlier in this batch).
                deleted += request.path
                return@forEachIndexed
            }
            val before = sizeOf(file)
            val ok = if (request.contentsOnly && file.isDirectory) {
                file.children().map { deleteTree(it) }.all { it }
            } else {
                deleteTree(file)
            }
            val remaining = if (file.exists()) sizeOf(file) else 0L
            freed += (before - remaining).coerceAtLeast(0)
            if (ok) deleted += request.path else failed += request.path
        }
        onProgress(requests.size, requests.size)
        return DeleteResult(freed, deleted, failed)
    }

    /** Like [File.deleteRecursively] but never follows symlinks into other trees. */
    private fun deleteTree(file: File): Boolean {
        if (file.isDirectory && !file.isSymlink()) {
            var ok = true
            for (child in file.children()) ok = deleteTree(child) && ok
            return file.delete() && ok
        }
        return file.delete()
    }
}
