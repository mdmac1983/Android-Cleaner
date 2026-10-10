package io.github.mdmac1983.leancleaner.analyzer

import io.github.mdmac1983.leancleaner.core.FileKind
import io.github.mdmac1983.leancleaner.core.isSymlink
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.PriorityQueue

/**
 * A directory with the total size of everything below it. Only directories are kept in memory;
 * files are listed on demand when browsing, which keeps big scans cheap.
 */
class DirNode(val path: String, val parent: DirNode?) {
    val name: String get() = path.substringAfterLast('/')
    var size: Long = 0
        private set
    var fileCount: Int = 0
        private set
    var unreadable: Boolean = false
        internal set
    val children: MutableList<DirNode> = ArrayList()

    internal fun addFile(bytes: Long) {
        size += bytes
        fileCount++
    }

    internal fun addChild(child: DirNode) {
        children += child
        size += child.size
        fileCount += child.fileCount
    }

    /** Applies a size change caused by deleting something below this node to all ancestors. */
    fun shrink(bytes: Long, files: Int) {
        var node: DirNode? = this
        while (node != null) {
            node.size = (node.size - bytes).coerceAtLeast(0)
            node.fileCount = (node.fileCount - files).coerceAtLeast(0)
            node = node.parent
        }
    }

    fun removeChild(child: DirNode) {
        if (children.remove(child)) shrink(child.size, child.fileCount)
    }
}

data class LargeFile(val path: String, val size: Long, val lastModified: Long, val kind: FileKind) {
    val name: String get() = path.substringAfterLast('/')
}

class AnalyzerResult(
    val roots: List<DirNode>,
    val bytesByKind: Map<FileKind, Long>,
    val countByKind: Map<FileKind, Int>,
    /** Largest files first. */
    val largeFiles: List<LargeFile>,
) {
    val scannedBytes: Long get() = roots.sumOf { it.size }
}

class StorageAnalyzer(
    private val largeFileThreshold: Long,
    private val maxLargeFiles: Int = 300,
) {
    private val bytesByKind = HashMap<FileKind, Long>()
    private val countByKind = HashMap<FileKind, Int>()
    private val largest = PriorityQueue<LargeFile>(compareBy { it.size })
    private var scannedDirs = 0

    suspend fun analyze(roots: List<File>, onProgress: (String) -> Unit = {}): AnalyzerResult {
        val nodes = roots.filter { it.isDirectory }.map { root ->
            DirNode(root.absolutePath, parent = null).also { walk(root, it, onProgress) }
        }
        return AnalyzerResult(
            roots = nodes,
            bytesByKind = bytesByKind.toMap(),
            countByKind = countByKind.toMap(),
            largeFiles = largest.sortedByDescending { it.size },
        )
    }

    private suspend fun walk(dir: File, node: DirNode, onProgress: (String) -> Unit) {
        currentCoroutineContext().ensureActive()
        if (++scannedDirs % 50 == 0) onProgress(dir.absolutePath)
        val children = dir.listFiles()
        if (children == null) {
            node.unreadable = true
            return
        }
        for (child in children) {
            if (child.isDirectory) {
                if (child.isSymlink()) continue
                val childNode = DirNode(child.absolutePath, node)
                walk(child, childNode, onProgress)
                node.addChild(childNode)
            } else {
                val length = child.length()
                node.addFile(length)
                val kind = FileKind.of(child.name)
                bytesByKind[kind] = (bytesByKind[kind] ?: 0L) + length
                countByKind[kind] = (countByKind[kind] ?: 0) + 1
                if (length >= largeFileThreshold) offerLarge(LargeFile(child.absolutePath, length, child.lastModified(), kind))
            }
        }
        node.children.sortByDescending { it.size }
    }

    private fun offerLarge(file: LargeFile) {
        if (largest.size < maxLargeFiles) {
            largest += file
        } else if (file.size > largest.peek()!!.size) {
            largest.poll()
            largest += file
        }
    }
}
