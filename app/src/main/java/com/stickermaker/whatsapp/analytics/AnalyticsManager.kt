package com.stickermaker.whatsapp.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Thin wrapper around Firebase Analytics.
 *
 * IMPORTANT (privacy): only the anonymous, non-identifying events listed in the
 * spec are logged here. Never pass file paths, filenames, image/video bytes,
 * or any other user content as an event parameter.
 */
object AnalyticsManager {

    private const val EVENT_APP_OPENED = "app_opened"
    private const val EVENT_VIDEO_SELECTED = "video_selected"
    private const val EVENT_IMAGE_SELECTED = "image_selected"
    private const val EVENT_STICKER_CREATED = "sticker_created"
    private const val EVENT_STICKER_EXPORTED = "sticker_exported"
    private const val EVENT_REWARDED_AD_COMPLETED = "rewarded_ad_completed"

    private fun analytics(context: Context): FirebaseAnalytics =
        FirebaseAnalytics.getInstance(context.applicationContext)

    fun logAppOpened(context: Context) {
        analytics(context).logEvent(EVENT_APP_OPENED, null)
    }

    fun logVideoSelected(context: Context) {
        analytics(context).logEvent(EVENT_VIDEO_SELECTED, null)
    }

    fun logImageSelected(context: Context) {
        analytics(context).logEvent(EVENT_IMAGE_SELECTED, null)
    }

    /** [isAnimated] and [durationMs] are coarse, non-identifying metadata about the sticker type only. */
    fun logStickerCreated(context: Context, isAnimated: Boolean, durationMs: Long) {
        val bundle = Bundle().apply {
            putBoolean("is_animated", isAnimated)
            putLong("duration_ms_bucket", (durationMs / 1000) * 1000) // rounded to nearest second
        }
        analytics(context).logEvent(EVENT_STICKER_CREATED, bundle)
    }

    fun logStickerExported(context: Context) {
        analytics(context).logEvent(EVENT_STICKER_EXPORTED, null)
    }

    fun logRewardedAdCompleted(context: Context) {
        analytics(context).logEvent(EVENT_REWARDED_AD_COMPLETED, null)
    }

    /** Non-fatal breadcrumb for debugging crashes - never include user content. */
    fun logBreadcrumb(message: String) {
        FirebaseCrashlytics.getInstance().log(message)
    }

    fun recordException(t: Throwable) {
        FirebaseCrashlytics.getInstance().recordException(t)
    }
}
