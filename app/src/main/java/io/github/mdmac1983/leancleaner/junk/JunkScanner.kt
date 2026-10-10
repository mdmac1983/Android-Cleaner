package io.github.mdmac1983.leancleaner.junk

import io.github.mdmac1983.leancleaner.core.Exclusions
import io.github.mdmac1983.leancleaner.core.StorageLayout
import io.github.mdmac1983.leancleaner.core.children
import io.github.mdmac1983.leancleaner.core.isSymlink
import io.github.mdmac1983.leancleaner.core.sizeOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

enum class JunkCategory(
    val title: String,
    val description: String,
    val selectedByDefault: Boolean,
) {
    APP_CACHE(
        "App caches",
        "Cache folders apps keep on shared storage. Apps rebuild them when needed.",
        true,
    ),
    THUMBNAILS(
        "Thumbnail caches",
        "Preview images (.thumbnails) that galleries regenerate on demand.",
        true,
    ),
    TEMP_FILES(
        "Temporary & log files",
        "*.tmp, *.temp and *.log files left behind by apps.",
        true,
    ),
    EMPTY_FOLDERS(
        "Empty folders",
        "Folders with nothing inside. Standard folders like DCIM or Download are never listed.",
        true,
    ),
    LOST_FOUND(
        "Recovered fragments",
        "Pieces of files recovered by disk checks (LOST.DIR, FOUND.000).",
        true,
    ),
    DESKTOP_JUNK(
        "PC & Mac leftovers",
        ".DS_Store, ._ files, Thumbs.db, desktop.ini and trash folders created by computers.",
        true,
    ),
    APK_FILES(
        "Installer files (APK)",
        "Downloaded app installers. Ones for apps that are already installed are pre-selected.",
        false,
    ),
    TRASHED(
        "Trashed media",
        "Items in the system trash. Android empties it after 30 days anyway.",
        false,
    ),
}

data class JunkItem(
    val path: String,
    val size: Long,
    val category: JunkCategory,
    val isDirectory: Boolean,
    val contentsOnly: Boolean = false,
    val detail: String? = null,
    val preselected: Boolean = category.selectedByDefault,
) {
    val name: String get() = path.substringAfterLast('/')
}

/** What the system knows about an APK file found on storage. */
data class ApkInfo(
    val label: String?,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    /** Version code of the installed app with the same package name, or null if not installed. */
    val installedVersionCode: Long?,
) {
    val isRedundant: Boolean get() = installedVersionCode != null && installedVersionCode >= versionCode
}

fun interface ApkInspector {
    fun inspect(file: File): ApkInfo?
}

/**
 * Single-pass scan of shared storage for clutter that is safe to remove.
 * Everything under `<root>/Android` is left alone except `Android/data/<pkg>/cache`.
 */
class JunkScanner(
    private val exclusions: Exclusions = Exclusions.NONE,
    private val apkInspector: ApkInspector? = null,
) {

    suspend fun scan(roots: List<File>, onProgress: (String) -> Unit = {}): List<JunkItem> {
        val out = ArrayList<JunkItem>()
        for (root in roots) {
            if (!root.isDirectory) continue
            walk(root, depth = 0, out, onProgress)
        }
        return out
    }

    /** @return true when [dir] contains nothing but (recursively) empty, removable folders. */
    private suspend fun walk(
        dir: File,
        depth: Int,
        out: MutableList<JunkItem>,
        onProgress: (String) -> Unit,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        onProgress(dir.absolutePath)
        val children = dir.listFiles() ?: return false // unreadable: treat as not empty
        var allEmpty = true
        val emptyChildren = ArrayList<File>()

        for (child in children) {
            if (exclusions.isExcluded(child.absolutePath)) {
                allEmpty = false
                continue
            }
            if (child.isDirectory) {
                if (child.isSymlink()) {
                    allEmpty = false
                    continue
                }
                if (depth == 0 && child.name == "Android") {
                    scanAndroidDir(child, out)
                    allEmpty = false
                    continue
                }
                val whole = classifyDirectory(child, depth + 1)
                if (whole != null) {
                    allEmpty = false
                    out.addIfNotEmpty(child, whole)
                    continue
                }
                if (walk(child, depth + 1, out, onProgress)) {
                    emptyChildren += child
                } else {
                    allEmpty = false
                }
            } else {
                allEmpty = false
                classifyFile(child)?.let(out::add)
            }
        }

        if (allEmpty && isRemovableWhenEmpty(dir, depth)) {
            return true // Let the parent report this folder (or an even higher empty ancestor).
        }
        for (empty in emptyChildren) {
            out += JunkItem(empty.absolutePath, 0, JunkCategory.EMPTY_FOLDERS, isDirectory = true)
        }
        return false
    }

    private fun isRemovableWhenEmpty(dir: File, depth: Int): Boolean = when {
        depth == 0 -> false
        depth == 1 && dir.name in StorageLayout.STANDARD_DIRS -> false
        else -> true
    }

    private fun MutableList<JunkItem>.addIfNotEmpty(dir: File, kind: DirectoryJunk) {
        val hasContent = dir.children().isNotEmpty()
        if (kind.contentsOnly && !hasContent) return
        add(
            JunkItem(
                path = dir.absolutePath,
                size = sizeOf(dir),
                category = kind.category,
                isDirectory = true,
                contentsOnly = kind.contentsOnly,
            ),
        )
    }

    private class DirectoryJunk(val category: JunkCategory, val contentsOnly: Boolean = false)

    private fun classifyDirectory(dir: File, depth: Int): DirectoryJunk? {
        val name = dir.name
        return when {
            name.equals(".thumbnails", ignoreCase = true) -> DirectoryJunk(JunkCategory.THUMBNAILS)
            depth == 1 && name == "LOST.DIR" -> DirectoryJunk(JunkCategory.LOST_FOUND, contentsOnly = true)
            depth == 1 && FOUND_DIR.matches(name) -> DirectoryJunk(JunkCategory.LOST_FOUND)
            name in DESKTOP_JUNK_DIRS || name.startsWith(".Trash-") -> DirectoryJunk(JunkCategory.DESKTOP_JUNK)
            else -> null
        }
    }

    private fun classifyFile(file: File): JunkItem? {
        val name = file.name
        val lower = name.lowercase()
        val category = when {
            name.startsWith(".trashed-") -> JunkCategory.TRASHED
            name in DESKTOP_JUNK_FILES || lower == "thumbs.db" || lower == "desktop.ini" ||
                lower == "ehthumbs.db" || name.startsWith("._") -> JunkCategory.DESKTOP_JUNK
            lower.endsWith(".tmp") || lower.endsWith(".temp") || lower.endsWith(".log") -> JunkCategory.TEMP_FILES
            APK_EXTENSIONS.any { lower.endsWith(it) } -> JunkCategory.APK_FILES
            else -> return null
        }
        if (category == JunkCategory.APK_FILES) return apkItem(file)
        return JunkItem(file.absolutePath, file.length(), category, isDirectory = false)
    }

    private fun apkItem(file: File): JunkItem {
        val info = if (file.name.lowercase().endsWith(".apk")) apkInspector?.inspect(file) else null
        val detail = when {
            info == null -> null
            info.installedVersionCode == null -> "${info.label ?: info.packageName} · not installed"
            info.isRedundant -> "${info.label ?: info.packageName} · already installed"
            else -> "${info.label ?: info.packageName} · newer than installed"
        }
        return JunkItem(
            path = file.absolutePath,
            size = file.length(),
            category = JunkCategory.APK_FILES,
            isDirectory = false,
            detail = detail,
            preselected = info?.isRedundant == true,
        )
    }

    /** Only `Android/data/<pkg>/cache` is touched; the rest belongs to apps. */
    private fun scanAndroidDir(androidDir: File, out: MutableList<JunkItem>) {
        val data = File(androidDir, "data")
        for (pkgDir in data.children()) {
            if (!pkgDir.isDirectory || pkgDir.isSymlink()) continue
            val cache = File(pkgDir, "cache")
            if (!cache.isDirectory || exclusions.isExcluded(cache.absolutePath)) continue
            val size = sizeOf(cache)
            if (size <= 0) continue
            out += JunkItem(
                path = cache.absolutePath,
                size = size,
                category = JunkCategory.APP_CACHE,
                isDirectory = true,
                contentsOnly = true,
                detail = pkgDir.name,
            )
        }
    }

    private companion object {
        val FOUND_DIR = Regex("FOUND\\.\\d{3}")
        val DESKTOP_JUNK_DIRS = setOf(".Spotlight-V100", ".fseventsd", ".Trashes", ".TemporaryItems")
        val DESKTOP_JUNK_FILES = setOf(".DS_Store")
        val APK_EXTENSIONS = listOf(".apk", ".apks", ".apkm", ".xapk")
    }
}
