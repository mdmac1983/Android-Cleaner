package io.github.mdmac1983.leancleaner.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import io.github.mdmac1983.leancleaner.core.FileKind
import io.github.mdmac1983.leancleaner.ui.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Small in-memory cache so scrolling long lists doesn't decode the same thumbnails again. */
private object ImageCache {
    private val cache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    operator fun get(key: String): ImageBitmap? = cache.get(key)

    operator fun set(key: String, value: ImageBitmap) {
        cache.put(key, value)
    }
}

/** Thumbnail for photos, videos, album art and APK icons, falling back to a file-type icon. */
@Composable
fun FileThumbnail(path: String, kind: FileKind, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image by produceState(ImageCache[path], path) {
        if (value == null && (kind.isMedia || kind == FileKind.APK)) {
            value = withContext(Dispatchers.IO) { loadThumbnail(context, path, kind, px) }
                ?.asImageBitmap()
                ?.also { ImageCache[path] = it }
        }
    }
    ThumbnailBox(image, kind.icon(), modifier, size)
}

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val key = "app:$packageName"
    val image by produceState(ImageCache[key], packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(px) }.getOrNull()
            }?.asImageBitmap()?.also { ImageCache[key] = it }
        }
    }
    ThumbnailBox(image, AppIcons.Android, modifier, size, rounded = false)
}

@Composable
private fun ThumbnailBox(image: ImageBitmap?, fallback: ImageVector, modifier: Modifier, size: Dp, rounded: Boolean = true) {
    val shape = RoundedCornerShape(if (rounded) 8.dp else 0.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .then(if (image == null) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(fallback, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun loadThumbnail(context: Context, path: String, kind: FileKind, px: Int): Bitmap? = runCatching {
    val file = File(path)
    val size = Size(px, px)
    when (kind) {
        FileKind.IMAGE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThumbnailUtils.createImageThumbnail(file, size, null)
        } else {
            decodeSampled(path, px)
        }
        FileKind.VIDEO -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThumbnailUtils.createVideoThumbnail(file, size, null)
        } else {
            @Suppress("DEPRECATION")
            ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND)
        }
        FileKind.AUDIO -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThumbnailUtils.createAudioThumbnail(file, size, null)
        } else {
            null
        }
        FileKind.APK -> apkIcon(context, path)?.toBitmap(px)
        else -> null
    }
}.getOrNull()

private fun decodeSampled(path: String, px: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun apkIcon(context: Context, path: String): Drawable? {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageArchiveInfo(path, 0)
    }
    val ai = info?.applicationInfo ?: return null
    ai.sourceDir = path
    ai.publicSourceDir = path
    return ai.loadIcon(pm)
}

private fun Drawable.toBitmap(px: Int): Bitmap {
    val bitmap = createBitmap(px, px)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, px, px)
    draw(canvas)
    return bitmap
}
