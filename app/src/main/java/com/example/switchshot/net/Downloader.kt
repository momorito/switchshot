package com.example.switchshot.net

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import kotlin.text.RegexOption

private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(30, TimeUnit.SECONDS)
    .build()

private val imageLinkRegex = Regex(".*\\.(jpg|jpeg|png|webp|gif|bmp)(\\?.*)?", RegexOption.IGNORE_CASE)

suspend fun downloadFromIndexOrImage(
    baseUrl: String,
    contentResolver: ContentResolver,
    relativePath: String
): Int = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(baseUrl).build()
    httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code} error for $baseUrl")
        }
        val body = response.body ?: throw IOException("No response body from $baseUrl")
        val mediaType = body.contentType()
        val isImage = mediaType?.type.equals("image", ignoreCase = true) ||
            (mediaType == null && looksLikeImageUrl(baseUrl))
        return@use if (isImage) {
            body.byteStream().use { stream ->
                val extension = guessExtension(mediaType, baseUrl)
                val mimeType = mediaType?.toString() ?: mimeTypeFromExtension(extension)
                saveStream(stream, contentResolver, relativePath, mimeType, extension)
            }
            1
        } else {
            val html = body.string()
            val document = Jsoup.parse(html, baseUrl)
            val urls = LinkedHashSet<String>()
            document.select("img[src]").forEach { element ->
                val abs = element.absUrl("src")
                if (abs.isNotBlank()) {
                    urls.add(abs)
                }
            }
            document.select("a[href]").forEach { element ->
                val abs = element.absUrl("href")
                if (abs.isNotBlank() && imageLinkRegex.matches(abs)) {
                    urls.add(abs)
                }
            }
            val limited = urls.take(min(10, urls.size))
            var saved = 0
            for (url in limited) {
                if (downloadImageUrl(url, contentResolver, relativePath)) {
                    saved++
                }
            }
            if (saved == 0) {
                throw IOException("No downloadable images were found on $baseUrl")
            }
            saved
        }
    }
}

private fun downloadImageUrl(
    url: String,
    contentResolver: ContentResolver,
    relativePath: String
): Boolean {
    val request = Request.Builder().url(url).build()
    return try {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            val mediaType = body.contentType()
            if (mediaType?.type?.lowercase(Locale.US) != "image" && !looksLikeImageUrl(url)) {
                return false
            }
            body.byteStream().use { stream ->
                val extension = guessExtension(mediaType, url)
                val mimeType = mediaType?.toString() ?: mimeTypeFromExtension(extension)
                saveStream(stream, contentResolver, relativePath, mimeType, extension)
            }
            true
        }
    } catch (error: Exception) {
        false
    }
}

private fun guessExtension(mediaType: MediaType?, source: String): String {
    val subtype = mediaType?.subtype?.lowercase(Locale.US)
    return when {
        subtype == null -> guessExtensionFromUrl(source)
        subtype.contains("png") -> "png"
        subtype.contains("webp") -> "webp"
        subtype.contains("gif") -> "gif"
        subtype.contains("bmp") -> "bmp"
        subtype.contains("jpeg") -> "jpg"
        subtype.contains("jpg") -> "jpg"
        else -> guessExtensionFromUrl(source)
    }.ifEmpty { "jpg" }
}

private fun guessExtensionFromUrl(url: String): String {
    val lower = url.lowercase(Locale.US)
    return when {
        lower.contains(".png") -> "png"
        lower.contains(".webp") -> "webp"
        lower.contains(".gif") -> "gif"
        lower.contains(".bmp") -> "bmp"
        lower.contains(".jpeg") -> "jpg"
        lower.contains(".jpg") -> "jpg"
        else -> "jpg"
    }
}

private fun mimeTypeFromExtension(extension: String): String {
    return when (extension.lowercase(Locale.US)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "image/jpeg"
    }
}

private fun looksLikeImageUrl(url: String): Boolean = imageLinkRegex.matches(url)

private fun saveStream(
    input: InputStream,
    resolver: ContentResolver,
    relativePath: String,
    mimeType: String,
    extension: String
) {
    val displayName = "switch_shot_${'$'}{System.currentTimeMillis()}.$extension"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        } else {
            val file = ensureLegacyFile(relativePath, displayName)
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
        }
    }
    val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val uri = resolver.insert(collection, values) ?: throw IOException("Failed to create MediaStore entry")
    var success = false
    try {
        resolver.openOutputStream(uri)?.use { output ->
            input.copyTo(output)
        } ?: throw IOException("Failed to open output stream")
        success = true
    } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val update = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, update, null, null)
        }
        if (!success) {
            resolver.delete(uri, null, null)
        }
    }
}

private fun ensureLegacyFile(relativePath: String, displayName: String): File {
    val segments = relativePath.split('/')
    val basePictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    var current = basePictures
    for (segment in segments.drop(1)) {
        if (segment.isBlank()) continue
        current = File(current, segment)
    }
    if (!current.exists()) {
        current.mkdirs()
    }
    return File(current, displayName)
}
