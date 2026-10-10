package io.github.mdmac1983.leancleaner.corpse

import io.github.mdmac1983.leancleaner.core.Exclusions
import io.github.mdmac1983.leancleaner.core.StorageLayout
import io.github.mdmac1983.leancleaner.core.children
import io.github.mdmac1983.leancleaner.core.isSymlink
import io.github.mdmac1983.leancleaner.core.measure
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

enum class CorpseLocation(val label: String) {
    APP_DATA("Android/data"),
    APP_OBB("Android/obb"),
    APP_MEDIA("Android/media"),
    SHARED("Shared storage"),
}

/** A folder left behind by an app that is no longer installed. */
data class Corpse(
    val path: String,
    val location: CorpseLocation,
    /** Package name, or app name for well-known folders. */
    val owner: String,
    val size: Long,
    val fileCount: Int,
    val mediaCount: Int,
) {
    /** Leftovers holding photos, videos or audio may be precious, so they are never pre-selected. */
    val preselected: Boolean get() = mediaCount == 0
}

/** A folder an app is known to create in the root of shared storage. */
data class KnownFolder(val relativePath: String, val appName: String, val packages: List<String>)

class CorpseFinder(
    private val isInstalled: (String) -> Boolean,
    private val exclusions: Exclusions = Exclusions.NONE,
    private val knownFolders: List<KnownFolder> = KnownFolders.ALL,
) {

    suspend fun find(roots: List<File>, onProgress: (String) -> Unit = {}): List<Corpse> {
        val out = ArrayList<Corpse>()
        for (root in roots) {
            if (!root.isDirectory) continue
            val android = File(root, "Android")
            scanAppArea(File(android, "data"), CorpseLocation.APP_DATA, out, onProgress)
            scanAppArea(File(android, "obb"), CorpseLocation.APP_OBB, out, onProgress)
            scanAppArea(File(android, "media"), CorpseLocation.APP_MEDIA, out, onProgress)
            scanSharedRoot(root, out, onProgress)
        }
        return out.sortedByDescending { it.size }
    }

    /** `Android/{data,obb,media}/<package>` folders whose package is gone. */
    private suspend fun scanAppArea(
        area: File,
        location: CorpseLocation,
        out: MutableList<Corpse>,
        onProgress: (String) -> Unit,
    ) {
        for (dir in area.children()) {
            currentCoroutineContext().ensureActive()
            if (!dir.isDirectory || dir.isSymlink()) continue
            val pkg = dir.name
            if (!looksLikePackage(pkg) || isInstalled(pkg)) continue
            if (exclusions.isExcluded(dir.absolutePath)) continue
            onProgress(dir.absolutePath)
            out += corpse(dir, location, pkg)
        }
    }

    /** Top-level folders named after a package, plus folders known to belong to specific apps. */
    private suspend fun scanSharedRoot(root: File, out: MutableList<Corpse>, onProgress: (String) -> Unit) {
        val reported = HashSet<String>()
        for (known in knownFolders) {
            currentCoroutineContext().ensureActive()
            val dir = resolveIgnoreCase(root, known.relativePath) ?: continue
            if (dir.isSymlink() || known.packages.any(isInstalled)) continue
            if (exclusions.isExcluded(dir.absolutePath)) continue
            onProgress(dir.absolutePath)
            if (reported.add(dir.absolutePath)) out += corpse(dir, CorpseLocation.SHARED, known.appName)
        }
        for (dir in root.children()) {
            currentCoroutineContext().ensureActive()
            if (!dir.isDirectory || dir.isSymlink()) continue
            val name = dir.name
            if (name in StorageLayout.STANDARD_DIRS || !looksLikePublicPackageFolder(name)) continue
            if (isInstalled(name) || exclusions.isExcluded(dir.absolutePath)) continue
            onProgress(dir.absolutePath)
            if (reported.add(dir.absolutePath)) out += corpse(dir, CorpseLocation.SHARED, name)
        }
    }

    private fun corpse(dir: File, location: CorpseLocation, owner: String): Corpse {
        val stats = measure(dir)
        return Corpse(dir.absolutePath, location, owner, stats.bytes, stats.files, stats.mediaFiles)
    }

    /** Folder names on shared storage are matched case-insensitively, like FAT/exFAT cards. */
    private fun resolveIgnoreCase(root: File, relativePath: String): File? {
        var current = root
        for (segment in relativePath.split('/')) {
            current = current.children().firstOrNull { it.isDirectory && it.name.equals(segment, ignoreCase = true) }
                ?: return null
        }
        return current
    }

    companion object {
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        // First segments that make a top-level "a.b" folder name very likely to be a package name.
        private val PACKAGE_PREFIXES = setOf(
            "com", "org", "net", "io", "de", "me", "app", "dev", "co", "uk", "ru", "cn", "jp", "kr",
            "fr", "br", "in", "tv", "info", "biz", "mobi", "eu", "us", "ca", "au", "nl", "it", "es",
            "pl", "se", "ch", "at", "be", "cz", "vn", "tw", "hk", "sg", "id", "tr", "ua", "xyz", "ai",
        )

        fun looksLikePackage(name: String): Boolean = PACKAGE_NAME.matches(name)

        /** Stricter than [looksLikePackage] so a folder like "my.photos" is not mistaken for an app. */
        fun looksLikePublicPackageFolder(name: String): Boolean {
            if (!looksLikePackage(name)) return false
            val segments = name.split('.')
            return segments.size >= 3 || segments.first().lowercase() in PACKAGE_PREFIXES
        }
    }
}
