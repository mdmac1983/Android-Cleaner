package io.github.mdmac1983.leancleaner.apps

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Saves an installed app's APK to Download/LeanCleaner. Split apps are bundled into a single
 * `.apks` archive (base + splits) that tools like SAI can install again.
 */
object ApkExporter {
    private const val FOLDER = "LeanCleaner"

    /** @return a human readable location of the exported file. */
    suspend fun export(context: Context, app: AppInfo): String = withContext(Dispatchers.IO) {
        val isSplit = app.splitPaths.isNotEmpty()
        val baseName = "${app.label}_${app.versionName ?: app.versionCode}".replace(Regex("[^A-Za-z0-9._ -]"), "_").trim()
        val fileName = if (isSplit) "$baseName.apks" else "$baseName.apk"
        val mime = if (isSplit) "application/zip" else "application/vnd.android.package-archive"

        writeToDownloads(context, fileName, mime) { out ->
            if (isSplit) {
                ZipOutputStream(out).use { zip ->
                    (listOf(app.apkPath) + app.splitPaths).forEach { path ->
                        val source = File(path)
                        val entryName = if (path == app.apkPath) "base.apk" else source.name
                        zip.putNextEntry(ZipEntry(entryName))
                        source.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } else {
                File(app.apkPath).inputStream().use { it.copyTo(out) }
            }
        }
        "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$fileName"
    }

    private fun writeToDownloads(context: Context, fileName: String, mime: String, write: (OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create $fileName")
            try {
                resolver.openOutputStream(uri)?.use(write) ?: throw IOException("Could not open $fileName")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.path}")
            FileOutputStream(File(dir, fileName)).use(write)
        }
    }
}
