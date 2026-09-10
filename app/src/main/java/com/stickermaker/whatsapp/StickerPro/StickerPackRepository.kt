package com.stickermaker.whatsapp.StickerPro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.stickermaker.whatsapp.sticker.StickerSpec
import com.stickermaker.whatsapp.utils.FileUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Keeps a single, ever-growing "My Stickers" pack in the app's private files dir
 * (`/files/stickers/`). Every sticker the user creates and exports is added to it.
 * All reads/writes stay on-device; nothing here touches the network.
 */
object StickerPackRepository {

    private const val TAG = "StickerPackRepo"
    private const val DEFAULT_PACK_IDENTIFIER = "my_stickers_pack"
    private const val METADATA_FILE = "pack_metadata.json"
    private const val TRAY_FILE = "tray_icon.png"

    /** WhatsApp rejects sticker packs with fewer than this many stickers. */
    const val MIN_STICKERS_PER_PACK = 3
    const val MAX_STICKERS_PER_PACK = 30

    /** Marker embedded in filenames of auto-generated padding stickers (see [addStickerToPack]). */
    private const val PAD_MARKER = "_pad"

    private fun baseDir(context: Context): File {
        val standardBase = FileUtils.stickersDir(context) // now filesDir
        val legacyCacheBase = File(context.cacheDir, "my_stickers")
        
        // Migrate data from cacheDir back to filesDir if legacy data exists
        if (legacyCacheBase.exists() && legacyCacheBase.isDirectory && legacyCacheBase.listFiles()?.isNotEmpty() == true) {
            Log.d(TAG, "Migrating sticker data from cacheDir to filesDir...")
            legacyCacheBase.copyRecursively(standardBase, overwrite = true)
            legacyCacheBase.deleteRecursively()
            Log.d(TAG, "Migration complete.")
        }
        
        return standardBase
    }
    
    private fun packDir(context: Context, identifier: String): File = 
        File(baseDir(context), identifier)

    private fun metadataFile(context: Context, identifier: String) = 
        File(packDir(context, identifier), METADATA_FILE)

    /**
     * Copies [stickerFile] into the pack directory, updates metadata, and (re)generates
     * the tray icon. Enforces the 30-sticker limit.
     *
     * @return the file the newly-created sticker was persisted to, or null if pack is full.
     */
    fun addStickerToPack(context: Context, stickerFile: File, isAnimated: Boolean, identifier: String = DEFAULT_PACK_IDENTIFIER): File? {
        val existing = loadPack(context, identifier)
        val realStickers = existing?.stickers?.filterNot { it.imageFileName.contains(PAD_MARKER) } ?: emptyList()
        
        if (realStickers.size >= MAX_STICKERS_PER_PACK) return null

        val dir = packDir(context, identifier)
        dir.mkdirs()

        val destName = "sticker_${java.util.UUID.randomUUID()}.webp"
        val destFile = File(dir, destName)
        
        // Copy the already-optimized sticker file directly. 
        // DO NOT re-compress it as it destroys animations.
        stickerFile.copyTo(destFile, overwrite = true)
        
        // Drop any previous padding stickers
        existing?.stickers?.filter { it.imageFileName.contains(PAD_MARKER) }?.forEach {
            File(dir, it.imageFileName).delete()
        }
        
        var stickers = realStickers + Sticker(destName, listOf("☕", "😊"))

        var padIndex = 0
        while (stickers.size < MIN_STICKERS_PER_PACK) {
            val dupName = "sticker_${java.util.UUID.randomUUID()}${PAD_MARKER}$padIndex.webp"
            destFile.copyTo(File(dir, dupName), overwrite = true)
            stickers = stickers + Sticker(dupName)
            padIndex++
        }

        ensureTrayIcon(context, identifier, destFile)

        val pack = StickerPack(
            identifier = identifier,
            name = existing?.name ?: "StickerPro Pack",
            publisher = "StickerPro",
            trayImageFile = TRAY_FILE,
            stickers = stickers,
            animatedStickerPack = existing?.animatedStickerPack == true || isAnimated
        )
        savePack(context, pack)
        Log.d(TAG, "Sticker added to pack $identifier. Total stickers: ${stickers.size}, animated: ${pack.animatedStickerPack}")
        return destFile
    }

    /** Clears all stickers from the specified pack. */
    fun clearPack(context: Context, identifier: String = DEFAULT_PACK_IDENTIFIER) {
        val dir = packDir(context, identifier)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    fun setPackName(context: Context, identifier: String, newName: String) {
        val existing = loadPack(context, identifier) ?: return
        val updated = StickerPack(
            identifier = existing.identifier,
            name = newName,
            publisher = existing.publisher,
            trayImageFile = existing.trayImageFile,
            stickers = existing.stickers,
            animatedStickerPack = existing.animatedStickerPack
        )
        savePack(context, updated)
    }

    /** Stickers meant to be shown to the user - excludes internal padding entries. */
    fun loadVisibleStickers(context: Context, identifier: String = DEFAULT_PACK_IDENTIFIER): List<Sticker> =
        loadPack(context, identifier)?.stickers?.filterNot { it.imageFileName.contains(PAD_MARKER) } ?: emptyList()

    private fun ensureTrayIcon(context: Context, identifier: String, sourceStickerFile: File) {
        val trayFile = File(packDir(context, identifier), TRAY_FILE)
        try {
            val bitmap = BitmapFactory.decodeFile(sourceStickerFile.absolutePath)
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode sticker for tray icon: ${sourceStickerFile.absolutePath}")
                return
            }
            
            val trayBitmap = Bitmap.createScaledBitmap(
                bitmap, StickerSpec.TRAY_ICON_DIMENSION, StickerSpec.TRAY_ICON_DIMENSION, true
            )
            
            FileOutputStream(trayFile).use {
                trayBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            
            if (trayBitmap !== bitmap) trayBitmap.recycle()
            bitmap.recycle()
            Log.d(TAG, "Tray icon ensured at ${trayFile.absolutePath}, size: ${trayFile.length()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring tray icon", e)
        }
    }

    fun loadAllPacks(context: Context): List<StickerPack> {
        val base = baseDir(context)
        if (!base.exists()) return emptyList()
        return base.listFiles()?.filter { it.isDirectory }?.mapNotNull { 
            loadPack(context, it.name)
        } ?: emptyList()
    }

    fun loadPack(context: Context, identifier: String = DEFAULT_PACK_IDENTIFIER): StickerPack? {
        val file = metadataFile(context, identifier)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val stickersArray = json.getJSONArray("stickers")
            val stickers = (0 until stickersArray.length()).map { i ->
                val obj = stickersArray.getJSONObject(i)
                val emojisArray = obj.optJSONArray("emojis")
                val emojis = if (emojisArray != null) {
                    (0 until emojisArray.length()).map { emojisArray.getString(it) }
                } else {
                    listOf("☕", "😊") // fallback
                }
                Sticker(
                    imageFileName = obj.getString("image_file"),
                    emojis = emojis
                )
            }
            StickerPack(
                identifier = json.getString("identifier"),
                name = json.getString("name"),
                publisher = json.getString("publisher"),
                trayImageFile = json.getString("tray_image_file"),
                stickers = stickers,
                animatedStickerPack = json.optBoolean("animated_pack", false)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun savePack(context: Context, pack: StickerPack) {
        val json = JSONObject().apply {
            put("identifier", pack.identifier)
            put("name", pack.name)
            put("publisher", pack.publisher)
            put("tray_image_file", pack.trayImageFile)
            put("animated_pack", pack.animatedStickerPack)
            put("stickers", JSONArray().apply {
                pack.stickers.forEach { sticker ->
                    put(JSONObject().apply {
                        put("image_file", sticker.imageFileName)
                        put("emojis", JSONArray(sticker.emojis))
                    })
                }
            })
        }
        val file = metadataFile(context, pack.identifier)
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
    }

    fun stickerFile(context: Context, identifier: String, fileName: String): File = 
        File(packDir(context, identifier), fileName)

    fun createNewPack(context: Context, name: String): String {
        val identifier = "pack_${System.currentTimeMillis()}"
        val pack = StickerPack(
            identifier = identifier,
            name = name,
            publisher = "StickerPro",
            trayImageFile = TRAY_FILE,
            stickers = emptyList(),
            animatedStickerPack = false
        )
        savePack(context, pack)
        return identifier
    }

    fun getActivePackIdentifier(context: Context): String {
        return context.getSharedPreferences("stickers", Context.MODE_PRIVATE)
            .getString("active_pack", DEFAULT_PACK_IDENTIFIER) ?: DEFAULT_PACK_IDENTIFIER
    }

    fun setActivePackIdentifier(context: Context, identifier: String) {
        context.getSharedPreferences("stickers", Context.MODE_PRIVATE)
            .edit()
            .putString("active_pack", identifier)
            .apply()
    }
}
