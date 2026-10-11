package io.github.mdmac1983.leancleaner.apps

import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import io.github.mdmac1983.leancleaner.automation.CleanTarget
import io.github.mdmac1983.leancleaner.core.Permissions
import io.github.mdmac1983.leancleaner.junk.ApkInfo
import io.github.mdmac1983.leancleaner.junk.ApkInspector
import java.io.File
import java.util.concurrent.TimeUnit

data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val installTime: Long,
    val updateTime: Long,
    val apkPath: String,
    val splitPaths: List<String>,
    val targetSdk: Int,
    val minSdk: Int,
    /** APK + code size. Null without usage access. */
    val appBytes: Long?,
    /** All private data, including cache. Null without usage access. */
    val dataBytes: Long?,
    val cacheBytes: Long?,
    /** Last time the app was in the foreground, or null if unknown / never. */
    val lastUsed: Long?,
    val isLaunchable: Boolean,
) {
    val totalBytes: Long? get() = if (appBytes == null) null else appBytes + (dataBytes ?: 0)
}

class AppRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    fun installedPackageNames(): Set<String> {
        // Include apps uninstalled with "keep data" and archived apps: their folders are not leftovers.
        var flags = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) flags = flags or PackageManager.MATCH_ARCHIVED_PACKAGES
        return installedPackages(flags).mapTo(HashSet()) { it.packageName }
    }

    fun loadApps(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<AppInfo> {
        val packages = installedPackages(0)
        val usageAccess = Permissions.hasUsageAccess(context)
        val lastUsed = if (usageAccess) lastUsedTimes() else emptyMap()
        val storage = context.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()

        return packages.mapIndexedNotNull { index, info ->
            onProgress(index, packages.size)
            val ai = info.applicationInfo ?: return@mapIndexedNotNull null
            val stats = if (usageAccess) {
                runCatching { storage.queryStatsForPackage(ai.storageUuid, info.packageName, user) }.getOrNull()
            } else {
                null
            }
            AppInfo(
                packageName = info.packageName,
                label = ai.loadLabel(pm).toString(),
                versionName = info.versionName,
                versionCode = info.versionCodeCompat,
                isSystem = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                isEnabled = ai.enabled,
                installTime = info.firstInstallTime,
                updateTime = info.lastUpdateTime,
                apkPath = ai.sourceDir,
                splitPaths = ai.splitSourceDirs?.toList().orEmpty(),
                targetSdk = ai.targetSdkVersion,
                minSdk = ai.minSdkVersion,
                appBytes = stats?.appBytes,
                dataBytes = stats?.dataBytes,
                cacheBytes = stats?.cacheBytes,
                lastUsed = lastUsed[info.packageName],
                isLaunchable = pm.getLaunchIntentForPackage(info.packageName) != null,
            )
        }
    }

    fun isInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
    }.isSuccess

    /** Sum of cache bytes across all apps (needs usage access), for the "clear all caches" card. */
    fun totalCacheBytes(): Long? {
        if (!Permissions.hasUsageAccess(context)) return null
        val storage = context.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        return installedPackages(0).sumOf { info ->
            val ai = info.applicationInfo ?: return@sumOf 0L
            runCatching { storage.queryStatsForPackage(ai.storageUuid, info.packageName, user).cacheBytes }.getOrDefault(0L)
        }
    }

    /**
     * Apps whose cache is worth clearing, largest first. Without usage access sizes are unknown, so
     * every app with a launcher icon is included instead. This app itself is never included.
     */
    fun cacheCleanTargets(minCacheBytes: Long = 50_000): List<CleanTarget> {
        val own = context.packageName
        if (Permissions.hasUsageAccess(context)) {
            val storage = context.getSystemService(StorageStatsManager::class.java)
            val user = Process.myUserHandle()
            return installedPackages(0).mapNotNull { info ->
                val ai = info.applicationInfo ?: return@mapNotNull null
                if (info.packageName == own) return@mapNotNull null
                val cache = runCatching { storage.queryStatsForPackage(ai.storageUuid, info.packageName, user).cacheBytes }
                    .getOrNull() ?: return@mapNotNull null
                if (cache < minCacheBytes) return@mapNotNull null
                CleanTarget(info.packageName, ai.loadLabel(pm).toString(), cache)
            }.sortedByDescending { it.cacheBytes }
        }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
        }
        return activities.map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != own }
            .map { CleanTarget(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    /** Clean targets for specific packages (e.g. a selection in the app list). */
    fun cacheCleanTargets(packages: Collection<String>): List<CleanTarget> {
        val sizes = if (Permissions.hasUsageAccess(context)) cacheSizes(packages) else emptyMap()
        return packages.filter { it != context.packageName }.mapNotNull { pkg ->
            val ai = applicationInfo(pkg) ?: return@mapNotNull null
            CleanTarget(pkg, ai.loadLabel(pm).toString(), sizes[pkg])
        }
    }

    /** Current cache size per package (needs usage access; packages that can't be measured are omitted). */
    fun cacheSizes(packages: Collection<String>): Map<String, Long> {
        val storage = context.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        return packages.mapNotNull { pkg ->
            val ai = applicationInfo(pkg) ?: return@mapNotNull null
            runCatching { storage.queryStatsForPackage(ai.storageUuid, pkg, user).cacheBytes }.getOrNull()?.let { pkg to it }
        }.toMap()
    }

    private fun lastUsedTimes(): Map<String, Long> {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        return runCatching {
            usm.queryAndAggregateUsageStats(now - TimeUnit.DAYS.toMillis(2 * 365), now)
                .mapValues { it.value.lastTimeUsed }
                .filterValues { it > 0 }
        }.getOrDefault(emptyMap())
    }

    private fun applicationInfo(pkg: String): ApplicationInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }
    }.getOrNull()

    private fun installedPackages(flags: Long): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags.toInt())
        }
}

/** Reads APK metadata through PackageManager so the junk scan can tell stale installers apart. */
class PackageManagerApkInspector(context: Context) : ApkInspector {
    private val pm = context.packageManager

    override fun inspect(file: File): ApkInfo? {
        val archive = archiveInfo(file.absolutePath) ?: return null
        val ai = archive.applicationInfo?.apply {
            sourceDir = file.absolutePath
            publicSourceDir = file.absolutePath
        }
        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(archive.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(archive.packageName, 0)
            }
        }.getOrNull()
        return ApkInfo(
            label = runCatching { ai?.loadLabel(pm)?.toString() }.getOrNull(),
            packageName = archive.packageName,
            versionName = archive.versionName,
            versionCode = archive.versionCodeCompat,
            installedVersionCode = installed?.versionCodeCompat,
        )
    }

    private fun archiveInfo(path: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, 0)
        }
    }.getOrNull()
}

private val PackageInfo.versionCodeCompat: Long
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }
