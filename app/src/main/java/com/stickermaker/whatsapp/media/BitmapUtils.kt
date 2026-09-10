package com.stickermaker.whatsapp.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.tasks.await

object BitmapUtils {

    /** Scales [bitmap] down so its longest side is at most [maxDimension]. No-op if already smaller. */
    fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longest = maxOf(width, height)
        if (longest <= maxDimension) return bitmap

        val scale = maxDimension.toFloat() / longest
        val matrix = Matrix().apply { postScale(scale, scale) }
        return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
    }

    /**
     * Crops [bitmap] to [normalizedRect], whose left/top/right/bottom are fractions
     * (0f-1f) of the bitmap's width/height. A full-frame rect (0,0,1,1) is a no-op
     * copy-free pass-through of [bitmap] itself.
     */
    fun cropNormalized(bitmap: Bitmap, normalizedRect: RectF): Bitmap {
        if (normalizedRect.left <= 0f && normalizedRect.top <= 0f &&
            normalizedRect.right >= 1f && normalizedRect.bottom >= 1f
        ) {
            return bitmap
        }
        val left = (normalizedRect.left.coerceIn(0f, 1f) * bitmap.width).toInt()
        val top = (normalizedRect.top.coerceIn(0f, 1f) * bitmap.height).toInt()
        val right = (normalizedRect.right.coerceIn(0f, 1f) * bitmap.width).toInt().coerceAtLeast(left + 1)
        val bottom = (normalizedRect.bottom.coerceIn(0f, 1f) * bitmap.height).toInt().coerceAtLeast(top + 1)
        val width = (right - left).coerceAtMost(bitmap.width - left)
        val height = (bottom - top).coerceAtMost(bitmap.height - top)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    /**
     * Computes the rectangle (in [containerWidth]x[containerHeight] pixel space) that
     * [contentWidth]x[contentHeight] content occupies when displayed with "fit center"
     * scaling (like ImageView's fitCenter or PlayerView's default video scaling) -
     * i.e. the letterboxed content bounds. Used to align a crop overlay exactly with
     * the visible image/video content rather than the full (possibly letterboxed) view.
     */
    fun computeContentRect(
        containerWidth: Int,
        containerHeight: Int,
        contentWidth: Int,
        contentHeight: Int
    ): RectF {
        if (containerWidth <= 0 || containerHeight <= 0 || contentWidth <= 0 || contentHeight <= 0) {
            return RectF(0f, 0f, containerWidth.toFloat(), containerHeight.toFloat())
        }
        val containerAspect = containerWidth.toFloat() / containerHeight
        val contentAspect = contentWidth.toFloat() / contentHeight

        return if (contentAspect > containerAspect) {
            // content is relatively wider than the container - full width, letterboxed top/bottom
            val displayedHeight = containerWidth / contentAspect
            val top = (containerHeight - displayedHeight) / 2f
            RectF(0f, top, containerWidth.toFloat(), top + displayedHeight)
        } else {
            // content is relatively taller than the container - full height, letterboxed sides
            val displayedWidth = containerHeight * contentAspect
            val left = (containerWidth - displayedWidth) / 2f
            RectF(left, 0f, left + displayedWidth, containerHeight.toFloat())
        }
    }

    /**
     * WhatsApp stickers must be exactly square (512x512 recommended). This fits [source]
     * inside a transparent square canvas of [size]x[size], preserving aspect ratio - the
     * same approach WhatsApp's own sample sticker apps use.
     */
    fun padToSquare(source: Bitmap, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val scale = size.toFloat() / maxOf(source.width, source.height)
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)

        val left = (size - scaledWidth) / 2f
        val top = (size - scaledHeight) / 2f
        canvas.drawBitmap(scaled, left, top, null)
        if (scaled !== source) scaled.recycle()
        return output
    }

    /**
     * Uses ML Kit Selfie Segmentation to remove the background from [bitmap].
     * Returns a new bitmap with the background made transparent.
     */
    suspend fun removeBackground(bitmap: Bitmap): Bitmap {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        val segmenter = Segmentation.getClient(options)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        return try {
            val mask = segmenter.process(inputImage).await()
            val maskBuffer = mask.buffer
            val maskWidth = mask.width
            val maskHeight = mask.height

            val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resultBitmap)
            canvas.drawBitmap(bitmap, 0f, 0f, null)

            val paint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }

            val alphaMask = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
            for (y in 0 until maskHeight) {
                for (x in 0 until maskWidth) {
                    val confidence = maskBuffer.float
                    val alpha = (confidence * 255).toInt()
                    alphaMask.setPixel(x, y, Color.argb(alpha, 0, 0, 0))
                }
            }

            // Scale mask to match original bitmap size if necessary
            val scaledMask = if (maskWidth != bitmap.width || maskHeight != bitmap.height) {
                Bitmap.createScaledBitmap(alphaMask, bitmap.width, bitmap.height, true)
            } else {
                alphaMask
            }

            canvas.drawBitmap(scaledMask, 0f, 0f, paint)

            if (scaledMask !== alphaMask) alphaMask.recycle()
            if (scaledMask !== resultBitmap) scaledMask.recycle()

            resultBitmap
        } finally {
            segmenter.close()
        }
    }
}
