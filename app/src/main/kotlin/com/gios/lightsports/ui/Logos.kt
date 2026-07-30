package com.gios.lightsports.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gios.lightsports.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Team crests in the feed.
 *
 * Hand-rolled rather than Coil or Glide: this needs one thing — a small PNG, cached
 * forever, decoded once — and an image-loading library is a lot of APK for that. A
 * crest never changes, so the disk copy is never revalidated.
 *
 * The panel renders greyscale through a system colour matrix, so club colours arrive
 * as luminance. That is fine, and it's why the dark-background variant of each crest
 * is the one requested upstream: the light variants are mostly white outlines that
 * vanish on black.
 */
object Logos {

    /**
     * ESPN serves crests at 500x500, which decodes to a megabyte of ARGB_8888 for a
     * 24dp view. Everything is downsampled to this on the way in.
     */
    private const val TARGET_PX = 96

    /**
     * Decoded crests, bounded by bytes rather than count — a count-based cache sized
     * for thumbnails is a memory leak the first time a provider ships a big image.
     */
    private val memory = object : LruCache<String, ImageBitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /**
     * Largest power-of-two reduction that still leaves the image at or above the target.
     * BitmapFactory only honours powers of two, so anything else is rounded down.
     */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= TARGET_PX && h / 2 >= TARGET_PX) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun cacheFile(dir: File, url: String): File {
        // The URL is the identity; hashCode keeps the filename short and legal.
        val name = url.substringAfterLast('/').take(24)
        return File(dir, "${url.hashCode().toUInt().toString(16)}-$name")
    }

    suspend fun load(dir: File, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        memory.get(url)?.let { return@withContext it }
        val file = cacheFile(dir, url)
        val bytes = if (file.exists() && file.length() > 0) {
            runCatching { file.readBytes() }.getOrNull()
        } else {
            Http.bytes(url)?.also { fetched ->
                runCatching { file.parentFile?.mkdirs(); file.writeBytes(fetched) }
            }
        } ?: return@withContext null

        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull() ?: return@withContext null
        bitmap.asImageBitmap().also { memory.put(url, it) }
    }
}

/**
 * A crest, or an empty box the same size while it loads. The box is always present so
 * a row never reflows when the image arrives.
 */
@Composable
fun TeamLogo(url: String?, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = url?.let { Logos.load(File(context.filesDir, "logos"), it) }
    }
    val image = bitmap
    if (image == null) {
        Box(modifier.size(size))
    } else {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
    }
}
