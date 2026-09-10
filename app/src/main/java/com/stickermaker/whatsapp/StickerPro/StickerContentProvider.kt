package com.stickermaker.whatsapp.StickerPro

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** Implements WhatsApp's official four-endpoint Android sticker-pack contract. */
class StickerContentProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.stickermaker.whatsapp.stickerprovider"
        private const val METADATA = 1
        private const val SINGLE_PACK = 2
        private const val STICKERS = 3
        private const val ASSET = 4
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "metadata", METADATA)
        addURI(AUTHORITY, "metadata/*", SINGLE_PACK)
        addURI(AUTHORITY, "stickers/*", STICKERS)
        addURI(AUTHORITY, "stickers_asset/*/*", ASSET)
    }

    override fun onCreate(): Boolean {
        val packageName = context?.packageName ?: return false
        check(AUTHORITY.startsWith(packageName)) {
            "Sticker provider authority must begin with the application package name."
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor = when (matcher.match(uri)) {
        METADATA -> packCursor(uri, StickerPackRepository.loadAllPacks(requireNotNull(context)))
        SINGLE_PACK -> {
            val pack = StickerPackRepository.loadPack(requireNotNull(context), uri.lastPathSegment.orEmpty())
            packCursor(uri, listOfNotNull(pack))
        }
        STICKERS -> stickerCursor(uri, uri.lastPathSegment.orEmpty())
        else -> throw IllegalArgumentException("Unknown sticker provider URI: $uri")
    }

    private fun packCursor(uri: Uri, packs: List<StickerPack>): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                "sticker_pack_identifier",
                "sticker_pack_name",
                "sticker_pack_publisher",
                "sticker_pack_icon",
                "android_play_store_link",
                "ios_app_download_link",
                "sticker_pack_publisher_email",
                "sticker_pack_publisher_website",
                "sticker_pack_privacy_policy_website",
                "sticker_pack_license_agreement_website",
                "image_data_version",
                "whatsapp_will_not_cache_stickers",
                "animated_sticker_pack"
            )
        )
        packs.forEach { pack ->
            val newestAsset = (pack.stickers.map {
                StickerPackRepository.stickerFile(requireNotNull(context), pack.identifier, it.imageFileName).lastModified()
            } + StickerPackRepository.stickerFile(
                requireNotNull(context), pack.identifier, pack.trayImageFile
            ).lastModified()).maxOrNull() ?: 0L
            cursor.addRow(
                arrayOf<Any?>(
                    pack.identifier,
                    pack.name.take(128),
                    pack.publisher.take(128),
                    pack.trayImageFile,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    newestAsset.toString(),
                    0,
                    if (pack.animatedStickerPack) 1 else 0
                )
            )
        }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    private fun stickerCursor(uri: Uri, identifier: String): Cursor {
        val cursor = MatrixCursor(
            arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text")
        )
        StickerPackRepository.loadPack(requireNotNull(context), identifier)?.stickers?.forEach { sticker ->
            cursor.addRow(arrayOf(sticker.imageFileName, sticker.emojis.take(3).joinToString(","), "Sticker"))
        }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (mode != "r" || matcher.match(uri) != ASSET) return null
        val segments = uri.pathSegments
        if (segments.size != 3) throw IllegalArgumentException("Invalid sticker asset URI: $uri")
        val identifier = segments[1]
        val fileName = segments[2]
        val pack = StickerPackRepository.loadPack(requireNotNull(context), identifier) ?: return null
        val allowed = fileName == pack.trayImageFile || pack.stickers.any { it.imageFileName == fileName }
        if (!allowed) return null
        val file = StickerPackRepository.stickerFile(requireNotNull(context), identifier, fileName)
        if (!file.isFile || file.length() == 0L) return null
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        METADATA -> "vnd.android.cursor.dir/vnd.$AUTHORITY.metadata"
        SINGLE_PACK -> "vnd.android.cursor.item/vnd.$AUTHORITY.metadata"
        STICKERS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.stickers"
        ASSET -> if (uri.lastPathSegment?.endsWith(".png", true) == true) "image/png" else "image/webp"
        else -> throw IllegalArgumentException("Unknown sticker provider URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
