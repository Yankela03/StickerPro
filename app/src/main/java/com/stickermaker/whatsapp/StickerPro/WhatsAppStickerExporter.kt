package com.stickermaker.whatsapp.StickerPro

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Activity

sealed class AddToWhatsAppResult {
    object Launched : AddToWhatsAppResult()
    object WhatsAppNotInstalled : AddToWhatsAppResult()
    data class Failed(val message: String) : AddToWhatsAppResult()
}

object WhatsAppStickerExporter {
    const val ADD_PACK_REQUEST_CODE = 200

    private const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    private const val EXTRA_STICKER_PACK_ID = "sticker_pack_id"
    private const val EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority"
    private const val EXTRA_STICKER_PACK_NAME = "sticker_pack_name"

    private const val WHATSAPP_PACKAGE = "com.whatsapp"
    private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

    /**
     * Hands the user's on-device sticker pack off to WhatsApp. The sticker itself is
     * already persisted (see [StickerPackRepository.addStickerToPack], called right
     * after conversion) - this only fires the intent that opens WhatsApp's own
     * "Add sticker pack" screen.
     */
    fun launchWhatsApp(context: Context, identifier: String): AddToWhatsAppResult {
        val pack = StickerPackRepository.loadPack(context, identifier)
            ?: return AddToWhatsAppResult.Failed("Sticker pack not found.")

        if (pack.stickers.size < StickerPackRepository.MIN_STICKERS_PER_PACK) {
            return AddToWhatsAppResult.Failed(
                "A WhatsApp sticker pack needs at least ${StickerPackRepository.MIN_STICKERS_PER_PACK} stickers."
            )
        }
        val missingAsset = pack.stickers.firstOrNull {
            !StickerPackRepository.stickerFile(context, identifier, it.imageFileName).isFile
        }
        if (missingAsset != null) {
            return AddToWhatsAppResult.Failed("A sticker file is missing. Please recreate the pack.")
        }
        if (!StickerPackRepository.stickerFile(context, identifier, pack.trayImageFile).isFile) {
            return AddToWhatsAppResult.Failed("The sticker pack icon is missing.")
        }

        val whatsAppPackage = getInstalledWhatsAppPackage(context)
            ?: return AddToWhatsAppResult.WhatsAppNotInstalled
        val authority = StickerContentProvider.AUTHORITY

        val intent = Intent().apply {
            action = ACTION_ENABLE_STICKER_PACK
            putExtra(EXTRA_STICKER_PACK_ID, pack.identifier)
            putExtra(EXTRA_STICKER_PACK_AUTHORITY, authority)
            putExtra(EXTRA_STICKER_PACK_NAME, pack.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(whatsAppPackage)
        }

        val resolvedActivity = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?: return AddToWhatsAppResult.Failed(
                "No installed WhatsApp version can handle sticker packs. Please update WhatsApp."
            )

        android.util.Log.d(
            "WhatsAppExporter",
            "Launching sticker confirmation: ${resolvedActivity.activityInfo.packageName}/${resolvedActivity.activityInfo.name}"
        )

        return try {
            if (context is Activity) {
                context.startActivityForResult(intent, ADD_PACK_REQUEST_CODE)
            } else {
                context.startActivity(intent)
            }
            AddToWhatsAppResult.Launched
        } catch (e: Exception) {
            android.util.Log.e("WhatsAppExporter", "Failed to launch WhatsApp", e)
            AddToWhatsAppResult.Failed("WhatsApp error: ${e.message}")
        }
    }

    private fun getInstalledWhatsAppPackage(context: Context): String? {
        val pm = context.packageManager
        if (isPackageInstalled(pm, WHATSAPP_PACKAGE)) return WHATSAPP_PACKAGE
        if (isPackageInstalled(pm, WHATSAPP_BUSINESS_PACKAGE)) return WHATSAPP_BUSINESS_PACKAGE
        return null
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean = try {
        pm.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
