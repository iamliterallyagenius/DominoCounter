package com.example.dominocounter.ui.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.example.dominocounter.R
import com.example.dominocounter.detect.ReviewPip
import kotlin.math.max
import kotlin.math.min

/**
 * The scanned photo with a marker on every pip found. Pinch to zoom, drag to pan, tap a marker
 * to remove it or tap bare table to add one.
 *
 * Zoom matters: on a phone screen, the pips of a five-pattern sit a few millimetres apart, so
 * at full-photo scale a fingertip can't tell them apart.
 *
 * Marker positions are in pixels of the *scanned* photo ([showPhoto]'s source size), which can
 * be larger than the bitmap that is actually drawn; both map onto the same on-screen rectangle.
 */
class ScanReviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** A tap's position in photo pixels, plus a fingertip-sized radius in the same units. */
    var onTapAt: ((x: Float, y: Float, hitRadius: Float) -> Unit)? = null

    private var photo: Bitmap? = null
    private var sourceW = 0
    private var sourceH = 0
    private var pips: List<ReviewPip> = emptyList()

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private val density = resources.displayMetrics.density
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * density
        color = 0xCC000000.toInt()
    }
    private val detectedPaint = ringPaint(R.color.scan_pip_detected)
    private val manualPaint = ringPaint(R.color.brass)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 16f * density
        isFakeBoldText = true
        setShadowLayer(3f * density, 0f, 0f, 0xFF000000.toInt())
    }
    private val markerRect = RectF()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomAround(detector.focusX, detector.focusY, detector.scaleFactor)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                panX -= distanceX
                panY -= distanceY
                clampPan()
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                performClick()
                handleTap(e.x, e.y)
                return true
            }
        }
    )

    private fun ringPaint(colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = ContextCompat.getColor(context, colorRes)
    }

    /**
     * @param sourceWidth,sourceHeight size of the photo the markers' coordinates refer to;
     *        [bitmap] may be a downscaled copy of it. Re-showing the same bitmap keeps the zoom.
     */
    fun showPhoto(bitmap: Bitmap, sourceWidth: Int, sourceHeight: Int) {
        if (bitmap === photo) return
        photo = bitmap
        sourceW = sourceWidth
        sourceH = sourceHeight
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    fun setPips(pips: List<ReviewPip>) {
        this.pips = pips
        invalidate()
    }

    // ------------------------------------------------------------------ geometry

    private fun scale(): Float {
        if (sourceW == 0 || sourceH == 0 || width == 0 || height == 0) return 1f
        return min(width / sourceW.toFloat(), height / sourceH.toFloat()) * zoom
    }

    private fun left(scale: Float) = (width - sourceW * scale) / 2f + panX
    private fun top(scale: Float) = (height - sourceH * scale) / 2f + panY

    /** Zooms so the photo point under ([focusX], [focusY]) stays under the fingers. */
    private fun zoomAround(focusX: Float, focusY: Float, factor: Float) {
        val oldScale = scale()
        val photoX = (focusX - left(oldScale)) / oldScale
        val photoY = (focusY - top(oldScale)) / oldScale

        zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)

        val newScale = scale()
        panX = focusX - photoX * newScale - (width - sourceW * newScale) / 2f
        panY = focusY - photoY * newScale - (height - sourceH * newScale) / 2f
        clampPan()
        invalidate()
    }

    /** Keeps the photo covering the view once zoomed, and centred while it still fits. */
    private fun clampPan() {
        val s = scale()
        val slackX = max(0f, (sourceW * s - width) / 2f)
        val slackY = max(0f, (sourceH * s - height) / 2f)
        panX = panX.coerceIn(-slackX, slackX)
        panY = panY.coerceIn(-slackY, slackY)
    }

    private fun handleTap(x: Float, y: Float) {
        if (photo == null) return
        val s = scale()
        val photoX = (x - left(s)) / s
        val photoY = (y - top(s)) / s
        if (photoX !in 0f..sourceW.toFloat() || photoY !in 0f..sourceH.toFloat()) return
        onTapAt?.invoke(photoX, photoY, TAP_RADIUS_DP * density / s)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val bitmap = photo ?: return
        val s = scale()
        val left = left(s)
        val top = top(s)
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + sourceW * s, top + sourceH * s), photoPaint)

        for (pip in pips) {
            val paint = if (pip.manual) manualPaint else detectedPaint
            val cx = left + pip.x * s
            val cy = top + pip.y * s
            if (pip.value == 1) {
                // Never smaller than a visible dot, however tiny the pip is on screen.
                val radius = max(max(pip.w, pip.h) / 2f * s + 2f * density, MIN_MARKER_DP * density)
                canvas.drawCircle(cx, cy, radius, haloPaint)
                canvas.drawCircle(cx, cy, radius, paint)
            } else {
                markerRect.set(cx - pip.w / 2f * s, cy - pip.h / 2f * s, cx + pip.w / 2f * s, cy + pip.h / 2f * s)
                canvas.drawRoundRect(markerRect, 4f * density, 4f * density, haloPaint)
                canvas.drawRoundRect(markerRect, 4f * density, 4f * density, paint)
                labelPaint.color = paint.color
                canvas.drawText(pip.value.toString(), cx, cy + labelPaint.textSize / 3f, labelPaint)
            }
        }
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val MAX_ZOOM = 8f
        const val TAP_RADIUS_DP = 22f
        const val MIN_MARKER_DP = 5f
    }
}
