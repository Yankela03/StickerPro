package com.stickermaker.whatsapp.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File

data class ExtractedFrame(val bitmap: Bitmap, val timestampMs: Long)

/**
 * Extracts a bounded number of frames from a video file within [startMs, endMs),
 * using [MediaMetadataRetriever]. This is intentionally simple (frame-seek based,
 * not a full decode pipeline) so it stays lightweight and reliable across the wide
 * range of codecs/devices this app needs to support, including low-RAM phones.
 */
object FrameExtractor {

    /**
     * @param targetFps how many frames per second of output animation to sample.
     * @param maxFrames hard ceiling on frame count regardless of duration/fps, to bound
     *                  memory usage on low-end devices.
     */
    fun extractFrames(
        videoFile: File,
        startMs: Long,
        endMs: Long,
        targetFps: Int,
        maxFrames: Int,
        maxDimension: Int
    ): List<ExtractedFrame> {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<ExtractedFrame>()
        try {
            retriever.setDataSource(videoFile.absolutePath)

            val durationMs = (endMs - startMs).coerceAtLeast(1L)
            val frameIntervalMs = (1000.0 / targetFps).toLong().coerceAtLeast(33L)
            var count = ((durationMs / frameIntervalMs).toInt()).coerceAtLeast(1)
            count = count.coerceAtMost(maxFrames)

            for (i in 0 until count) {
                val timeMs = startMs + i * frameIntervalMs
                val frame = retriever.getFrameAtTime(
                    timeMs * 1000, // microseconds
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                val scaled = BitmapUtils.scaleDown(frame, maxDimension)
                if (scaled !== frame) frame.recycle()
                frames.add(ExtractedFrame(scaled, timeMs - startMs))
            }
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
        return frames
    }

    /** Grabs a single representative frame - used for static stickers made from a video. */
    fun extractSingleFrame(videoFile: File, atMs: Long, maxDimension: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val frame = retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val scaled = BitmapUtils.scaleDown(frame, maxDimension)
            if (scaled !== frame) frame.recycle()
            scaled
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /** Extracts a sequence of thumbnails for the trimmer background. */
    fun extractThumbnails(videoFile: File, count: Int, height: Int): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        val thumbnails = mutableListOf<Bitmap>()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLong() ?: 0L
            if (durationMs <= 0) return emptyList()

            val intervalMs = durationMs / count
            for (i in 0 until count) {
                val timeUs = (i * intervalMs) * 1000
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                
                // Scale to match the trimmer height, maintaining aspect ratio.
                val aspect = frame.width.toFloat() / frame.height
                val targetWidth = (height * aspect).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(frame, targetWidth, height, true)
                if (scaled !== frame) frame.recycle()
                thumbnails.add(scaled)
            }
        } catch (e: Exception) {
            // Log or ignore
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
        return thumbnails
    }
}
