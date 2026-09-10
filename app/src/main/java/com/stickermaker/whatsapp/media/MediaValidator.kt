package com.stickermaker.whatsapp.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File

sealed class MediaType {
    data class Image(val uri: Uri) : MediaType()
    data class Video(val uri: Uri, val durationMs: Long) : MediaType()
}

sealed class ValidationResult {
    data class Success(val mediaType: MediaType) : ValidationResult()
    data class Failure(val reasonResId: Int) : ValidationResult()
}

/**
 * Validates incoming media (from Share intents or the system picker) before any
 * heavy processing starts, so malformed or unsupported files fail fast with a
 * clear message instead of crashing mid-conversion.
 */
object MediaValidator {

    private const val MAX_VIDEO_BYTES = 200L * 1024 * 1024 // 200 MB safety ceiling
    private val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    private val SUPPORTED_VIDEO_TYPES = setOf("video/mp4", "video/3gpp", "video/x-matroska", "video/webm")

    fun validate(context: Context, uri: Uri, mimeTypeHint: String?): ValidationResult {
        val resolver = context.contentResolver
        val mimeType = mimeTypeHint
            ?: resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            )

        if (mimeType == null) {
            return ValidationResult.Failure(com.stickermaker.whatsapp.R.string.error_invalid_file)
        }

        return when {
            SUPPORTED_IMAGE_TYPES.any { mimeType.startsWith("image/") } && isImageMime(mimeType) -> {
                ValidationResult.Success(MediaType.Image(uri))
            }
            isVideoMime(mimeType) -> validateVideo(context, uri)
            else -> ValidationResult.Failure(com.stickermaker.whatsapp.R.string.error_invalid_file)
        }
    }

    private fun isImageMime(mime: String) = mime.startsWith("image/")
    private fun isVideoMime(mime: String) = mime.startsWith("video/")

    private fun validateVideo(context: Context, uri: Uri): ValidationResult {
        // Size check first (cheap) to avoid opening huge files on low-RAM devices.
        val sizeBytes = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) {
            -1L
        }
        if (sizeBytes > MAX_VIDEO_BYTES) {
            return ValidationResult.Failure(com.stickermaker.whatsapp.R.string.error_video_too_large)
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L
            if (duration <= 0L) {
                ValidationResult.Failure(com.stickermaker.whatsapp.R.string.error_invalid_file)
            } else {
                ValidationResult.Success(MediaType.Video(uri, duration))
            }
        } catch (e: Exception) {
            ValidationResult.Failure(com.stickermaker.whatsapp.R.string.error_invalid_file)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    fun isFileReadable(file: File): Boolean = file.exists() && file.length() > 0
}
