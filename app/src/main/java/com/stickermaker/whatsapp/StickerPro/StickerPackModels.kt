package com.stickermaker.whatsapp.StickerPro

/**
 * Minimal models matching the schema WhatsApp expects from a 3rd-party sticker
 * ContentProvider. See: https://github.com/WhatsApp/stickers (Android sample) for
 * the reference implementation this mirrors.
 */
data class StickerPack(
    val identifier: String,
    val name: String,
    val publisher: String,
    val trayImageFile: String,
    val stickers: List<Sticker>,
    val animatedStickerPack: Boolean
)

data class Sticker(
    val imageFileName: String,
    val emojis: List<String> = listOf("\uD83D\uDE00") // 😀 default emoji
)
