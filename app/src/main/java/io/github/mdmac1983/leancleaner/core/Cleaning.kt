package io.github.mdmac1983.leancleaner.core

import android.content.Context
import io.github.mdmac1983.leancleaner.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Deletes files on behalf of any tool, keeping MediaStore and the "freed so far" stat in sync. */
suspend fun deleteFiles(
    context: Context,
    requests: List<DeleteRequest>,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): DeleteResult = withContext(Dispatchers.IO) {
    val result = Deleter(SafetyGuard(Volumes.roots(context))).delete(requests, onProgress)
    MediaIndex.notifyDeleted(context, result.deleted)
    context.prefs.addFreed(result.freedBytes)
    result
}

fun DeleteResult.summary(): String = buildString {
    append("Freed ${Format.size(freedBytes)}")
    if (failed.isNotEmpty()) append(" · ${Format.count(failed.size, "item")} could not be deleted")
}
