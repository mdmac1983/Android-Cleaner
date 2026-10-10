package io.github.mdmac1983.leancleaner.core

import java.io.File
import java.nio.file.Files

/** Shared knowledge about the layout of Android's shared storage. */
object StorageLayout {
    /** Folders Android creates in every storage root. They are never offered as "empty folders". */
    val STANDARD_DIRS = setOf(
        "Alarms", "Android", "Audiobooks", "DCIM", "Documents", "Download", "Movies", "Music",
        "Notifications", "Pictures", "Podcasts", "Recordings", "Ringtones",
    )

    /** App-private areas inside `<root>/Android`. */
    val APP_AREAS = setOf("data", "obb", "media")

    fun relativePath(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).trimStart('/')
}

internal fun File.children(): Array<File> = listFiles() ?: emptyArray()

internal fun File.isSymlink(): Boolean =
    try {
        Files.isSymbolicLink(toPath())
    } catch (_: Exception) {
        false
    }

class TreeStats(var bytes: Long = 0, var files: Int = 0, var mediaFiles: Int = 0)

/** Size and file counts of a file or directory tree. Symlinks are not followed. */
fun measure(file: File, stats: TreeStats = TreeStats()): TreeStats {
    if (file.isDirectory) {
        if (file.isSymlink()) return stats
        for (child in file.children()) measure(child, stats)
    } else if (file.exists()) {
        stats.bytes += file.length()
        stats.files++
        if (FileKind.of(file.name).isMedia) stats.mediaFiles++
    }
    return stats
}

fun sizeOf(file: File): Long = measure(file).bytes

/**
 * Last line of defence against deleting something structural, no matter which tool asks.
 * Storage roots and `<root>/Android[/data|/obb|/media]` themselves can never be deleted,
 * and nothing outside a known storage root can be deleted at all.
 */
class SafetyGuard(roots: List<File>) {
    private val rootPaths = roots.map { it.absolutePath.trimEnd('/') }
    private val protectedPaths: Set<String> = buildSet {
        for (root in rootPaths) {
            add(root)
            add("$root/Android")
            StorageLayout.APP_AREAS.forEach { add("$root/Android/$it") }
        }
    }

    fun canDelete(file: File): Boolean {
        val path = file.absolutePath.trimEnd('/')
        if (path.contains("/../") || path.endsWith("/..")) return false
        if (path in protectedPaths) return false
        return rootPaths.any { path.startsWith("$it/") }
    }
}
