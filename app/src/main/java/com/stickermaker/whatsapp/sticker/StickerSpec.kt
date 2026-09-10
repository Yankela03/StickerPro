package com.stickermaker.whatsapp.sticker

/**
 * Constants reflecting WhatsApp's published sticker requirements
 * (see https://developers.facebook.com/docs/whatsapp/stickers/create). These are
 * app-side conservative defaults - WhatsApp's exact limits have changed over time,
 * so values are kept in one place for easy adjustment.
 */
object StickerSpec {
    const val STICKER_DIMENSION = 512 // stickers must be exactly 512x512 px

    const val MAX_STATIC_SIZE_BYTES = 100 * 1024 // 100 KB
    const val MAX_ANIMATED_SIZE_BYTES = 500 * 1024 // 500 KB
    const val TRAY_ICON_DIMENSION = 96

    // Animation constraints this app enforces for compatibility + performance on low-end phones.
    const val MAX_ANIMATION_DURATION_MS = 5000L // cap trim length to 5s
    const val TARGET_FPS = 15
    const val MAX_FRAMES = 75 // 5s * 15fps

    const val MIN_TRIM_DURATION_MS = 500L
}
