package com.stickermaker.whatsapp.utils

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * All working files live in the app's private cache/files directories - never on
 * shared/public storage - so nothing here is visible to other apps or persists
 * beyond what this app controls.
 */
object FileUtils {

    private const val TEMP_DIR_NAME = "temp"
    private const val STICKERS_DIR_NAME = "my_stickers"

    fun tempDir(context: Context): File =
        File(context.cacheDir, TEMP_DIR_NAME).apply { mkdirs() }

    fun stickersDir(context: Context): File =
        File(context.filesDir, STICKERS_DIR_NAME).apply { mkdirs() }

    fun newTempFile(context: Context, extension: String): File =
        File(tempDir(context), "${UUID.randomUUID()}.$extension")

    /** Copies the content behind [uri] into a private temp file this app fully controls. */
    fun copyUriToTemp(context: Context, uri: Uri, extension: String): File? {
        return try {
            val outFile = newTempFile(context, extension)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            outFile
        } catch (e: Exception) {
            null
        }
    }

    /** Deletes every file under the temp working directory. Called on app start and after each conversion. */
    fun clearTempDirectory(context: Context) {
        tempDir(context).listFiles()?.forEach { it.delete() }
    }

    fun deleteQuietly(file: File?) {
        try {
            file?.delete()
        } catch (_: Exception) {
        }
    }

    fun humanReadableSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }

    /**
     * Saves a copy of the [sourceFile] to the phone's public "Pictures/StickerPro" folder
     * so it appears in the device's Gallery app.
     */
    fun saveToPublicGallery(context: android.content.Context, sourceFile: File, fileName: String) {
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/webp")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/StickerPro")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
            } catch (e: Exception) {
                // Silently fail or log
            }
        }
    }
}
