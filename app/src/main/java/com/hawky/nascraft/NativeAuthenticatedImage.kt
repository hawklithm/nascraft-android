package com.hawky.nascraft

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.net.URI

/** Loads previews through the signed business API. It keeps no disk cache of protected media. */
@Composable
fun NativeAuthenticatedImage(
    nativeBusiness: NativeBusinessClient?,
    apiPath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val authEvent by (nativeBusiness?.authEvents ?: kotlinx.coroutines.flow.MutableStateFlow(0L)).collectAsState()
    var image by remember(apiPath) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(apiPath) { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) image = null
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(nativeBusiness, apiPath, authEvent) {
        image = null
        if (nativeBusiness == null || apiPath == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        image = try {
            val response = nativeBusiness.requestSelected("GET", apiPath)
            withContext(Dispatchers.Default) { decodeForPreview(response.body)?.asImageBitmap() }
        } catch (_: Exception) {
            null
        } finally {
            loading = false
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } ?: if (loading) {
            CircularProgressIndicator()
        } else {
            Unit
        }
    }
}

fun safeThumbnailApiPath(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.isAbsolute || uri.rawUserInfo != null || uri.rawFragment != null) return null
    val path = uri.rawPath ?: return null
    if (!path.startsWith("/api/thumbnail/") || path.startsWith("//") ||
        path.split('/').any { it == "." || it == ".." } ||
        Regex("%2f|%5c", RegexOption.IGNORE_CASE).containsMatchIn(path)) return null
    return path + (uri.rawQuery?.let { "?$it" } ?: "")
}

fun safeDownloadApiPath(fileId: String): String? {
    if (!Regex("[A-Za-z0-9_-]{8,160}").matches(fileId)) return null
    return "/api/download/$fileId"
}

private fun decodeForPreview(bytes: ByteArray): android.graphics.Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val largest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    val sourcePixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
    while (largest / sample > MAX_PREVIEW_DIMENSION || sourcePixels / (sample.toLong() * sample) > MAX_PREVIEW_PIXELS) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private const val MAX_PREVIEW_DIMENSION = 2048
private const val MAX_PREVIEW_PIXELS = 4_194_304L
