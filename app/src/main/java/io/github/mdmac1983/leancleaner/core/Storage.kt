package io.github.mdmac1983.leancleaner.core

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import java.io.File

data class Volume(
    val root: File,
    val label: String,
    val isPrimary: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
}

object Volumes {

    /** Mounted shared-storage roots: internal storage first, then SD cards / USB drives. */
    fun roots(context: Context): List<File> {
        val roots = context.getExternalFilesDirs(null).filterNotNull().mapNotNull { dir ->
            val path = dir.absolutePath
            val index = path.indexOf("/Android/data/")
            if (index > 0) File(path.substring(0, index)) else null
        }.distinct()
        @Suppress("DEPRECATION")
        return roots.ifEmpty { listOf(Environment.getExternalStorageDirectory()) }
    }

    fun list(context: Context): List<Volume> {
        val sm = context.getSystemService(StorageManager::class.java)
        val stats = context.getSystemService(StorageStatsManager::class.java)
        return roots(context).mapIndexed { index, root ->
            val volume = sm.getStorageVolume(root)
            val primary = volume?.isPrimary ?: (index == 0)
            // For internal storage the system reports the advertised capacity (e.g. 128 GB),
            // matching the Settings app; StatFs would only see the data partition.
            val (total, free) = if (primary) {
                runCatching {
                    stats.getTotalBytes(StorageManager.UUID_DEFAULT) to stats.getFreeBytes(StorageManager.UUID_DEFAULT)
                }.getOrElse { statFs(root) }
            } else {
                statFs(root)
            }
            Volume(
                root = root,
                label = volume?.getDescription(context) ?: if (primary) "Internal storage" else root.name,
                isPrimary = primary,
                totalBytes = total,
                freeBytes = free,
            )
        }
    }

    private fun statFs(root: File): Pair<Long, Long> = runCatching {
        val fs = StatFs(root.path)
        fs.totalBytes to fs.availableBytes
    }.getOrDefault(0L to 0L)
}

object Permissions {

    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** Android 8-10 runtime permissions; on 11+ use [allFilesAccessIntent] instead. */
    val legacyStoragePermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    @RequiresApi(Build.VERSION_CODES.R)
    fun allFilesAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        // Available on every supported version (un-deprecated again in API 37).
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun usageAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:${context.packageName}".toUri())

    fun appDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
}

/** Starts [intent], falling back to [fallback] (or nothing) if no activity handles it. */
fun Context.startActivitySafely(intent: Intent, fallback: Intent? = null): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    fallback?.let { startActivitySafely(it) } ?: false
} catch (_: SecurityException) {
    fallback?.let { startActivitySafely(it) } ?: false
}

object SystemCacheCleaner {
    /**
     * Android 11+ system dialog that clears the cache of every installed app at once.
     * Requires "All files access". This is the only non-root way to clear other apps' internal caches.
     */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    @RequiresApi(Build.VERSION_CODES.R)
    fun intent(): Intent = Intent(StorageManager.ACTION_CLEAR_APP_CACHE)
}

object MediaIndex {
    /**
     * Android 11+ updates MediaStore automatically when files are deleted through the filesystem.
     * Older versions keep stale rows, so galleries would show ghosts of deleted photos; remove
     * the rows for the deleted paths (and everything below deleted folders). Call off the main thread.
     */
    @Suppress("DEPRECATION") // MediaColumns.DATA is the only way to match by path on Android 8-10.
    fun notifyDeleted(context: Context, paths: Collection<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || paths.isEmpty()) return
        val uri = MediaStore.Files.getContentUri("external")
        val column = MediaStore.MediaColumns.DATA
        for (path in paths) {
            runCatching {
                context.contentResolver.delete(uri, "$column = ? OR $column LIKE ?", arrayOf(path, "$path/%"))
            }
        }
    }
}
