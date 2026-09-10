package com.stickermaker.whatsapp.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.stickermaker.whatsapp.sticker.StickerSpec
import kotlin.math.abs

/**
 * A stylized dual-handle trimmer that allows:
 * 1. Adjusting start/end edges to change duration.
 * 2. Dragging the center to shift the window (dragging the video).
 */
class VideoTrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var videoDurationMs: Long = 0L
        set(value) {
            field = value
            startMs = 0L
            endMs = value.coerceAtMost(StickerSpec.MAX_ANIMATION_DURATION_MS)
            invalidate()
        }

    var startMs: Long = 0L
        private set
    var endMs: Long = 0L
        private set

    // Screen coordinates for handles
    private var leftHandleX = 0f
    private var rightHandleX = 0f

    // Total milliseconds visible in the view's width
    private val visibleWindowMs = 10000f 

    var playbackPositionMs: Long = 0L
        set(value) {
            field = value
            invalidate()
        }

    private var thumbnails: List<android.graphics.Bitmap> = emptyList()
    private val thumbRect = RectF()

    fun setThumbnails(bitmaps: List<android.graphics.Bitmap>) {
        thumbnails = bitmaps
        invalidate()
    }

    private val dimPaint = Paint().apply { color = Color.parseColor("#CC000000") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#25D366")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#25D366") }
    private val gripperPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 4f
    }
    private val playheadPaint = Paint().apply {
        color = Color.parseColor("#25D366")
        strokeWidth = 5f
    }

    var onRangeChanged: ((startMs: Long, endMs: Long) -> Unit)? = null

    private var activeHandle = Handle.NONE
    private var lastTouchX = 0f

    private enum class Handle { NONE, START, END, MOVE }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val center = w / 2f
        val pixelsPerMs = w / visibleWindowMs
        val halfWidth = (StickerSpec.MAX_ANIMATION_DURATION_MS / 2f) * pixelsPerMs
        leftHandleX = (center - halfWidth).coerceAtLeast(60f)
        rightHandleX = (center + halfWidth).coerceAtMost(w - 60f)
    }

    private fun xForTime(timeMs: Long): Float {
        val pixelsPerMs = width / visibleWindowMs
        return leftHandleX + (timeMs - startMs) * pixelsPerMs
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (videoDurationMs <= 0) return

        val pixelsPerMs = width / visibleWindowMs

        // 1. Draw thumbnails background (scrolled)
        val scrollOffset = leftHandleX - (startMs * pixelsPerMs)
        if (thumbnails.isNotEmpty()) {
            val totalStripWidth = videoDurationMs * pixelsPerMs
            val thumbWidth = totalStripWidth / thumbnails.size
            for (i in thumbnails.indices) {
                val left = i * thumbWidth + scrollOffset
                if (left + thumbWidth < 0 || left > width) continue
                thumbRect.set(left, 0f, left + thumbWidth, height.toFloat())
                canvas.drawBitmap(thumbnails[i], null, thumbRect, null)
            }
        }

        // 2. Dim areas outside handles
        canvas.drawRect(0f, 0f, leftHandleX, height.toFloat(), dimPaint)
        canvas.drawRect(rightHandleX, 0f, width.toFloat(), height.toFloat(), dimPaint)

        // 3. Draw selection border
        canvas.drawRoundRect(leftHandleX, 2f, rightHandleX, height - 2f, 10f, 10f, borderPaint)

        // 4. Draw stylized handles
        val handleWidth = 48f
        canvas.drawRoundRect(leftHandleX - handleWidth, 0f, leftHandleX, height.toFloat(), 12f, 12f, handlePaint)
        canvas.drawRoundRect(rightHandleX, 0f, rightHandleX + handleWidth, height.toFloat(), 12f, 12f, handlePaint)

        // Gripper texture
        val centerY = height / 2f
        for (i in -1..1) {
            val offset = i * 16f
            canvas.drawLine(leftHandleX - handleWidth * 0.6f, centerY + offset - 10f, leftHandleX - handleWidth * 0.6f, centerY + offset + 10f, gripperPaint)
            canvas.drawLine(rightHandleX + handleWidth * 0.6f, centerY + offset - 10f, rightHandleX + handleWidth * 0.6f, centerY + offset + 10f, gripperPaint)
        }

        // 5. Draw Playhead
        if (videoDurationMs > 0 && playbackPositionMs in startMs..endMs) {
            val playheadX = xForTime(playbackPositionMs)
            canvas.drawLine(playheadX, 0f, playheadX, height.toFloat(), playheadPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (videoDurationMs <= 0) return false
        val x = event.x
        val pixelsPerMs = width / visibleWindowMs
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchX = x
                activeHandle = when {
                    abs(x - leftHandleX) <= 80f -> Handle.START
                    abs(x - rightHandleX) <= 80f -> Handle.END
                    x in leftHandleX..rightHandleX -> Handle.MOVE
                    else -> Handle.NONE
                }
                return activeHandle != Handle.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                if (abs(dx) < 1f) return true
                
                val dt = (dx / pixelsPerMs).toLong()

                when (activeHandle) {
                    Handle.START -> {
                        val newLeftX = (leftHandleX + dx).coerceIn(48f, rightHandleX - (StickerSpec.MIN_TRIM_DURATION_MS * pixelsPerMs))
                        val newDurationMs = ((rightHandleX - newLeftX) / pixelsPerMs).toLong()
                        if (newDurationMs <= StickerSpec.MAX_ANIMATION_DURATION_MS) {
                            leftHandleX = newLeftX
                            startMs = (startMs - dt).coerceIn(0, endMs - StickerSpec.MIN_TRIM_DURATION_MS)
                        }
                    }
                    Handle.END -> {
                        val newRightX = (rightHandleX + dx).coerceIn(leftHandleX + (StickerSpec.MIN_TRIM_DURATION_MS * pixelsPerMs), width - 48f)
                        val newDurationMs = ((newRightX - leftHandleX) / pixelsPerMs).toLong()
                        if (newDurationMs <= StickerSpec.MAX_ANIMATION_DURATION_MS) {
                            rightHandleX = newRightX
                            endMs = (endMs + dt).coerceIn(startMs + StickerSpec.MIN_TRIM_DURATION_MS, videoDurationMs)
                        }
                    }
                    Handle.MOVE -> {
                        val oldStart = startMs
                        startMs = (startMs - dt).coerceIn(0, videoDurationMs - (endMs - startMs))
                        endMs = startMs + ((rightHandleX - leftHandleX) / pixelsPerMs).toLong()
                        
                        if (startMs != oldStart) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                    }
                    else -> return false
                }
                
                lastTouchX = x
                onRangeChanged?.invoke(startMs, endMs)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = Handle.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
