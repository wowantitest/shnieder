package kz.kokshetau.fencereport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.min

/**
 * Показывает фото целиком и позволяет ставить/перетаскивать маркеры.
 * Координаты маркеров хранятся в пикселях фото.
 */
class MarkerImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    val markers = mutableListOf<PointF>()
    var onMarkersChanged: (() -> Unit)? = null

    private val drawMatrix = Matrix()
    private val inverse = Matrix()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var dragIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private fun updateMatrix() {
        val bmp = bitmap ?: return
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dx = (width - bmp.width * scale) / 2f
        val dy = (height - bmp.height * scale) / 2f
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)
        drawMatrix.invert(inverse)
    }

    private fun scale(): Float {
        val bmp = bitmap ?: return 1f
        return min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        updateMatrix()
        canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)

        val r = ReportRenderer.markerRadius(bmp.width, bmp.height) * scale()
        val pts = FloatArray(2)
        markers.forEachIndexed { i, p ->
            pts[0] = p.x; pts[1] = p.y
            drawMatrix.mapPoints(pts)
            ReportRenderer.drawMarker(canvas, pts[0], pts[1], r, i + 1)
        }
    }

    private fun toBitmap(x: Float, y: Float): PointF? {
        val bmp = bitmap ?: return null
        updateMatrix()
        val pts = floatArrayOf(x, y)
        inverse.mapPoints(pts)
        if (pts[0] < 0 || pts[1] < 0 || pts[0] > bmp.width || pts[1] > bmp.height) return null
        return PointF(pts[0], pts[1])
    }

    private fun findMarkerNear(x: Float, y: Float): Int {
        val bmp = bitmap ?: return -1
        val hitRadius = ReportRenderer.markerRadius(bmp.width, bmp.height) * scale() * 1.8f
        val pts = FloatArray(2)
        var best = -1
        var bestDist = Float.MAX_VALUE
        markers.forEachIndexed { i, p ->
            pts[0] = p.x; pts[1] = p.y
            drawMatrix.mapPoints(pts)
            val d = hypot(pts[0] - x, pts[1] - y)
            if (d < hitRadius && d < bestDist) {
                best = i; bestDist = d
            }
        }
        return best
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                updateMatrix()
                downX = event.x; downY = event.y
                moved = false
                dragIndex = findMarkerNear(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > touchSlop) moved = true
                if (dragIndex >= 0 && moved) {
                    toBitmap(event.x, event.y)?.let {
                        markers[dragIndex] = it
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragIndex >= 0) {
                    if (moved) onMarkersChanged?.invoke()
                } else if (!moved) {
                    toBitmap(event.x, event.y)?.let {
                        markers.add(it)
                        invalidate()
                        onMarkersChanged?.invoke()
                    }
                }
                dragIndex = -1
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragIndex = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    fun undo() {
        if (markers.isNotEmpty()) {
            markers.removeAt(markers.lastIndex)
            invalidate()
            onMarkersChanged?.invoke()
        }
    }
}
