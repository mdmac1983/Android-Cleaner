package io.github.mdmac1983.leancleaner.dupes

import io.github.mdmac1983.leancleaner.core.Exclusions
import io.github.mdmac1983.leancleaner.core.children
import io.github.mdmac1983.leancleaner.core.isSymlink
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

data class DupeFile(val path: String, val size: Long, val lastModified: Long) {
    val name: String get() = path.substringAfterLast('/')
}

data class DupeGroup(val hash: String, val size: Long, val files: List<DupeFile>) {
    /** Space freed by keeping a single copy. */
    val wasted: Long get() = size * (files.size - 1)
}

enum class KeepStrategy(val label: String) {
    OLDEST("Keep oldest"),
    NEWEST("Keep newest"),
    SHORTEST_PATH("Keep shortest path"),
}

/** Paths to delete so exactly one copy (chosen by [strategy]) survives. */
fun DupeGroup.autoSelect(strategy: KeepStrategy): Set<String> {
    val keep = when (strategy) {
        KeepStrategy.OLDEST -> files.minWith(compareBy<DupeFile> { it.lastModified }.thenBy { it.path.length })
        KeepStrategy.NEWEST -> files.maxWith(compareBy<DupeFile> { it.lastModified }.thenByDescending { it.path.length })
        KeepStrategy.SHORTEST_PATH -> files.minWith(compareBy<DupeFile> { it.path.length }.thenBy { it.path })
    }
    return files.asSequence().filter { it !== keep }.map { it.path }.toSet()
}

data class DupeProgress(val phase: Phase, val done: Int, val total: Int) {
    enum class Phase { COLLECTING, COMPARING }
}

/**
 * Finds byte-identical files. Candidates are narrowed by size, then by a hash of the first and
 * last [PARTIAL_BYTES], and only then fully hashed with SHA-256, so most files are never read
 * completely.
 */
class DuplicateFinder(
    private val exclusions: Exclusions = Exclusions.NONE,
    private val minSize: Long = 1,
    private val skipHidden: Boolean = true,
) {

    suspend fun find(roots: List<File>, onProgress: (DupeProgress) -> Unit = {}): List<DupeGroup> {
        val files = ArrayList<File>()
        for (root in roots) {
            if (root.isDirectory) collect(root, root, files, onProgress)
        }

        val candidates = files.groupBy { it.length() }.filterValues { it.size > 1 }
        val total = candidates.values.sumOf { it.size }
        var done = 0
        val groups = ArrayList<DupeGroup>()
        val buffer = ByteArray(BUFFER_SIZE)

        for ((size, sameSize) in candidates) {
            val narrowed: Collection<List<File>> = if (size <= PARTIAL_BYTES * 2) {
                listOf(sameSize)
            } else {
                sameSize.groupBy { partialHash(it, size, buffer) }
                    .filterKeys { it != null }
                    .values
                    .filter { it.size > 1 }
            }
            // Files ruled out by size + partial hash are finished too.
            done += sameSize.size - narrowed.sumOf { it.size }
            for (group in narrowed) {
                val byHash = group.groupBy { file ->
                    currentCoroutineContext().ensureActive()
                    fullHash(file, buffer).also { onProgress(DupeProgress(DupeProgress.Phase.COMPARING, ++done, total)) }
                }
                for ((hash, same) in byHash) {
                    if (hash == null || same.size < 2) continue
                    groups += DupeGroup(
                        hash = hash,
                        size = size,
                        files = same.map { DupeFile(it.absolutePath, size, it.lastModified()) }.sortedBy { it.path },
                    )
                }
            }
            onProgress(DupeProgress(DupeProgress.Phase.COMPARING, done, total))
        }
        return groups.sortedByDescending { it.wasted }
    }

    private suspend fun collect(root: File, dir: File, out: MutableList<File>, onProgress: (DupeProgress) -> Unit) {
        currentCoroutineContext().ensureActive()
        for (child in dir.children()) {
            val name = child.name
            if (exclusions.isExcluded(child.absolutePath)) continue
            if (child.isDirectory) {
                if (child.isSymlink()) continue
                if (skipHidden && name.startsWith(".")) continue
                if (name.equals(".thumbnails", ignoreCase = true)) continue
                if (dir.parentFile == root && dir.name == "Android" && (name == "data" || name == "obb")) continue
                collect(root, child, out, onProgress)
            } else {
                if (name.startsWith(".trashed-") || name.startsWith(".pending-")) continue
                if (skipHidden && name.startsWith(".")) continue
                val length = child.length()
                if (length > 0 && length >= minSize) {
                    out += child
                    if (out.size % 500 == 0) onProgress(DupeProgress(DupeProgress.Phase.COLLECTING, out.size, 0))
                }
            }
        }
    }

    private fun partialHash(file: File, size: Long, buffer: ByteArray): String? = try {
        RandomAccessFile(file, "r").use { raf ->
            val digest = MessageDigest.getInstance("SHA-256")
            raf.readFully(buffer, 0, PARTIAL_BYTES)
            digest.update(buffer, 0, PARTIAL_BYTES)
            raf.seek(size - PARTIAL_BYTES)
            raf.readFully(buffer, 0, PARTIAL_BYTES)
            digest.update(buffer, 0, PARTIAL_BYTES)
            digest.digest().toHex()
        }
    } catch (_: IOException) {
        null
    }

    private suspend fun fullHash(file: File, buffer: ByteArray): String? = try {
        FileInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            var chunks = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                if (++chunks % 64 == 0) currentCoroutineContext().ensureActive()
            }
            digest.digest().toHex()
        }
    } catch (_: IOException) {
        null
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val PARTIAL_BYTES = 8 * 1024
        const val BUFFER_SIZE = 64 * 1024
    }
}
