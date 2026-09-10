package com.stickermaker.whatsapp.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.stickermaker.whatsapp.media.BitmapUtils
import com.stickermaker.whatsapp.media.FrameExtractor
import com.stickermaker.whatsapp.utils.FileUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StickerResult(val file: File, val isAnimated: Boolean, val durationMs: Long)

sealed class ConversionOutcome {
    data class Success(val result: StickerResult) : ConversionOutcome()
    data class Error(val message: String) : ConversionOutcome()
}

private const val TAG = "StickerConverter"

/** Full-frame crop (no-op): the default when the user hasn't adjusted the crop overlay. */
val FULL_FRAME_CROP = RectF(0f, 0f, 1f, 1f)

/**
 * Converts a source image or a trimmed video segment into a WhatsApp-compliant
 * WebP sticker file. All work happens off the main thread and only ever touches
 * files inside this app's private storage.
 */
object StickerConverter {

    suspend fun convertImage(
        context: Context,
        sourceUri: Uri,
        cropRect: RectF = FULL_FRAME_CROP,
        removeBackground: Boolean = false
    ): ConversionOutcome =
        withContext(Dispatchers.Default) {
            try {
                val input = context.contentResolver.openInputStream(sourceUri)
                    ?: return@withContext ConversionOutcome.Error("Unable to open image")
                val original = input.use { BitmapFactory.decodeStream(it) }
                    ?: return@withContext ConversionOutcome.Error("Unable to decode image")

                val cropped = BitmapUtils.cropNormalized(original, cropRect)
                if (cropped !== original) original.recycle()

                val processed = if (removeBackground) {
                    val rb = BitmapUtils.removeBackground(cropped)
                    if (rb !== cropped) cropped.recycle()
                    rb
                } else {
                    cropped
                }

                val square = BitmapUtils.padToSquare(
                    BitmapUtils.scaleDown(processed, StickerSpec.STICKER_DIMENSION),
                    StickerSpec.STICKER_DIMENSION
                )
                if (square !== processed) processed.recycle()

                val bytes = compressStaticWithSizeLimit(square, StickerSpec.MAX_STATIC_SIZE_BYTES)
                square.recycle()

                val outFile = FileUtils.newTempFile(context, "webp")
                FileOutputStream(outFile).use { it.write(bytes) }
                
                Log.d(TAG, "Image conversion successful: ${outFile.absolutePath}, size: ${outFile.length()}")

                ConversionOutcome.Success(StickerResult(outFile, isAnimated = false, durationMs = 0))
            } catch (e: Exception) {
                Log.e(TAG, "Image conversion failed", e)
                ConversionOutcome.Error(e.message ?: "Conversion failed")
            }
        }

    /**
     * @param asStatic if true, only the first frame of the trimmed range is used
     *                 (per the "make static sticker" checkbox in the editor).
     * @param cropRect normalized (0f-1f) crop applied to every extracted frame before
     *                 it's padded into the square sticker canvas.
     */
    suspend fun convertVideo(
        context: Context,
        videoFile: File,
        startMs: Long,
        endMs: Long,
        asStatic: Boolean,
        cropRect: RectF = FULL_FRAME_CROP,
        removeBackground: Boolean = false
    ): ConversionOutcome = withContext(Dispatchers.Default) {
        try {
            if (asStatic) {
                val frame = FrameExtractor.extractSingleFrame(videoFile, startMs, StickerSpec.STICKER_DIMENSION)
                    ?: return@withContext ConversionOutcome.Error("Unable to extract frame")
                val cropped = BitmapUtils.cropNormalized(frame, cropRect)
                if (cropped !== frame) frame.recycle()

                val processed = if (removeBackground) {
                    val rb = BitmapUtils.removeBackground(cropped)
                    if (rb !== cropped) cropped.recycle()
                    rb
                } else {
                    cropped
                }

                val square = BitmapUtils.padToSquare(processed, StickerSpec.STICKER_DIMENSION)
                if (square !== processed) processed.recycle()

                val bytes = compressStaticWithSizeLimit(square, StickerSpec.MAX_STATIC_SIZE_BYTES)
                square.recycle()

                val outFile = FileUtils.newTempFile(context, "webp")
                FileOutputStream(outFile).use { it.write(bytes) }
                return@withContext ConversionOutcome.Success(
                    StickerResult(outFile, isAnimated = false, durationMs = 0)
                )
            }

            val clampedEnd = (startMs + StickerSpec.MAX_ANIMATION_DURATION_MS).coerceAtMost(endMs)
            val selectedDurationMs = (clampedEnd - startMs).coerceAtLeast(1L)
            
            val rawFrames = FrameExtractor.extractFrames(
                videoFile,
                startMs,
                clampedEnd,
                targetFps = StickerSpec.TARGET_FPS,
                maxFrames = StickerSpec.MAX_FRAMES,
                maxDimension = StickerSpec.STICKER_DIMENSION
            )
            if (rawFrames.isEmpty()) {
                return@withContext ConversionOutcome.Error("Unable to extract any frames from this video")
            }

            val squaredFrames = rawFrames.map { raw ->
                val cropped = BitmapUtils.cropNormalized(raw.bitmap, cropRect)
                if (cropped !== raw.bitmap) raw.bitmap.recycle()

                val processed = if (removeBackground) {
                    val rb = BitmapUtils.removeBackground(cropped)
                    if (rb !== cropped) cropped.recycle()
                    rb
                } else {
                    cropped
                }

                val squared = BitmapUtils.padToSquare(processed, StickerSpec.STICKER_DIMENSION)
                if (squared !== processed) processed.recycle()
                squared
            }

            // Calculate exact frame duration to match original video speed perfectly.
            val frameDurationMs = (selectedDurationMs / squaredFrames.size).toInt().coerceAtLeast(10)
            val bytes = compressAnimatedWithSizeLimit(
                squaredFrames,
                frameDurationMs,
                StickerSpec.MAX_ANIMATED_SIZE_BYTES
            )
            squaredFrames.forEach { it.recycle() }

            val outFile = FileUtils.newTempFile(context, "webp")
            FileOutputStream(outFile).use { it.write(bytes) }

            Log.d(TAG, "Video conversion successful: ${outFile.absolutePath}, size: ${outFile.length()}, frames: ${squaredFrames.size}, frameDuration: ${frameDurationMs}ms")
            
            ConversionOutcome.Success(StickerResult(outFile, isAnimated = true, durationMs = selectedDurationMs))
        } catch (e: Exception) {
            Log.e(TAG, "Video conversion failed", e)
            ConversionOutcome.Error(e.message ?: "Conversion failed")
        }
    }

    /** Repeatedly lowers quality until the static sticker fits WhatsApp's size limit. */
    private fun compressStaticWithSizeLimit(bitmap: Bitmap, maxBytes: Int): ByteArray {
        var quality = 90
        var bytes: ByteArray
        do {
            val baos = ByteArrayOutputStream()
            val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            bitmap.compress(format, quality, baos)
            bytes = baos.toByteArray()
            quality -= 10
        } while (bytes.size > maxBytes && quality > 10)
        return bytes
    }

    /** Repeatedly lowers quality, then drops frames, until the animation fits the size limit. */
    private fun compressAnimatedWithSizeLimit(
        frames: List<Bitmap>,
        frameDurationMs: Int,
        maxBytes: Int
    ): ByteArray {
        var quality = 80
        var workingFrames = frames
        var workingDurationMs = frameDurationMs
        var bytes: ByteArray

        while (true) {
            bytes = AnimatedWebPEncoder.encode(workingFrames, workingDurationMs, quality)
            if (bytes.size <= maxBytes || (quality <= 20 && workingFrames.size <= 4)) break

            if (quality > 20) {
                quality -= 15
            } else if (workingFrames.size > 4) {
                // Drop every other frame to roughly halve frame count while staying smooth-ish.
                // We MUST double the frame duration so the video doesn't play at double speed!
                workingFrames = workingFrames.filterIndexed { index, _ -> index % 2 == 0 }
                workingDurationMs *= 2
            } else {
                break
            }
        }
        return bytes
    }
}
