package com.stickermaker.whatsapp.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * A freeform crop rectangle the user can move and resize (via corner handles) within
 * the bounds of the actual displayed image/video content ([contentRect]) - not the
 * full (possibly letterboxed) preview container. Left untouched, the crop covers the
 * entire content, so cropping is purely optional: converting without adjusting this
 * behaves exactly as before (full frame, padded to a square sticker canvas).
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var contentRect = RectF()
    private var cropRect = RectF()

    private val dimPaint = Paint().apply { color = Color.parseColor("#99000000") }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val handlePaint = Paint().apply {
        color = Color.parseColor("#25D366")
        style = Paint.Style.FILL
    }

    private val handleRadiusPx = 18f
    private val touchSlopPx = 48f
    private val minCropSizePx = 60f

    private enum class DragMode { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private var dragMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    /** Call once the container's real content bounds are known (post-layout). Resets the crop to full-frame. */
    fun setContentRect(rect: RectF) {
        contentRect = RectF(rect)
        cropRect = RectF(rect)
        invalidate()
    }
    private val moveHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC25D366")
        style = Paint.Style.FILL
    }
    private val moveGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    fun resetCrop() {
        cropRect = RectF(contentRect)
        invalidate()
    }

    /** Returns the current crop as fractions (0f-1f) of the underlying image/video content. */
    fun getNormalizedCropRect(): RectF {
        if (contentRect.width() <= 0f || contentRect.height() <= 0f) return RectF(0f, 0f, 1f, 1f)
        return RectF(
            (cropRect.left - contentRect.left) / contentRect.width(),
            (cropRect.top - contentRect.top) / contentRect.height(),
            (cropRect.right - contentRect.left) / contentRect.width(),
            (cropRect.bottom - contentRect.top) / contentRect.height()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (contentRect.width() <= 0f) return

        // Dim everything outside the crop rect.
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, dimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, dimPaint)

        canvas.drawRect(cropRect, borderPaint)
        canvas.drawCircle(cropRect.left, cropRect.top, handleRadiusPx, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.top, handleRadiusPx, handlePaint)
        canvas.drawCircle(cropRect.left, cropRect.bottom, handleRadiusPx, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.bottom, handleRadiusPx, handlePaint)

        // Visible grab control: dragging anywhere inside still moves the box,
        // while this makes that interaction discoverable.
        val cx = cropRect.centerX()
        val cy = cropRect.centerY()
        canvas.drawCircle(cx, cy, 24f, moveHandlePaint)
        canvas.drawLine(cx - 11f, cy, cx + 11f, cy, moveGlyphPaint)
        canvas.drawLine(cx, cy - 11f, cx, cy + 11f, moveGlyphPaint)
        canvas.drawLine(cx - 11f, cy, cx - 5f, cy - 6f, moveGlyphPaint)
        canvas.drawLine(cx - 11f, cy, cx - 5f, cy + 6f, moveGlyphPaint)
        canvas.drawLine(cx + 11f, cy, cx + 5f, cy - 6f, moveGlyphPaint)
        canvas.drawLine(cx + 11f, cy, cx + 5f, cy + 6f, moveGlyphPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (contentRect.width() <= 0f) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = detectDragMode(event.x, event.y)
                if (dragMode != DragMode.NONE) parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchX = event.x
                lastTouchY = event.y
                return dragMode != DragMode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                applyDrag(dx, dy)
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun detectDragMode(x: Float, y: Float): DragMode {
        fun near(px: Float, py: Float) = abs(x - px) <= touchSlopPx && abs(y - py) <= touchSlopPx
        return when {
            near(cropRect.left, cropRect.top) -> DragMode.TOP_LEFT
            near(cropRect.right, cropRect.top) -> DragMode.TOP_RIGHT
            near(cropRect.left, cropRect.bottom) -> DragMode.BOTTOM_LEFT
            near(cropRect.right, cropRect.bottom) -> DragMode.BOTTOM_RIGHT
            cropRect.contains(x, y) -> DragMode.MOVE
            else -> DragMode.NONE
        }
    }

    private fun applyDrag(dx: Float, dy: Float) {
        when (dragMode) {
            DragMode.MOVE -> {
                var newLeft = cropRect.left + dx
                var newTop = cropRect.top + dy
                newLeft = newLeft.coerceIn(contentRect.left, contentRect.right - cropRect.width())
                newTop = newTop.coerceIn(contentRect.top, contentRect.bottom - cropRect.height())
                cropRect.offsetTo(newLeft, newTop)
            }
            DragMode.TOP_LEFT -> {
                val newLeft = (cropRect.left + dx).coerceIn(contentRect.left, cropRect.right - minCropSizePx)
                val newTop = (cropRect.top + dy).coerceIn(contentRect.top, cropRect.bottom - minCropSizePx)
                cropRect.left = newLeft
                cropRect.top = newTop
            }
            DragMode.TOP_RIGHT -> {
                val newRight = (cropRect.right + dx).coerceIn(cropRect.left + minCropSizePx, contentRect.right)
                val newTop = (cropRect.top + dy).coerceIn(contentRect.top, cropRect.bottom - minCropSizePx)
                cropRect.right = newRight
                cropRect.top = newTop
            }
            DragMode.BOTTOM_LEFT -> {
                val newLeft = (cropRect.left + dx).coerceIn(contentRect.left, cropRect.right - minCropSizePx)
                val newBottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minCropSizePx, contentRect.bottom)
                cropRect.left = newLeft
                cropRect.bottom = newBottom
            }
            DragMode.BOTTOM_RIGHT -> {
                val newRight = (cropRect.right + dx).coerceIn(cropRect.left + minCropSizePx, contentRect.right)
                val newBottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minCropSizePx, contentRect.bottom)
                cropRect.right = newRight
                cropRect.bottom = newBottom
            }
            DragMode.NONE -> {}
        }
    }
}
