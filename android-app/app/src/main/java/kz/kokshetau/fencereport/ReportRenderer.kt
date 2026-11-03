package kz.kokshetau.fencereport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import kotlin.math.min

/** Рисует подпись с координатами и маркеры сварки. */
object ReportRenderer {

    /** Радиус маркера в пикселях итогового фото. */
    fun markerRadius(width: Int, height: Int): Float = min(width, height) * 0.035f

    /** Возвращает копию фото с плашкой внизу: координаты, дата, ссылка 2ГИС. */
    fun withInfoBar(src: Bitmap, info: PhotoInfo): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()

        val baseSize = min(w, h) * 0.034f
        val lines = listOf(
            "Координаты: ${info.coordinatesText}",
            info.dateText,
            info.twoGisLink,
        )
        val sizes = listOf(baseSize, baseSize * 0.85f, baseSize * 0.7f)

        val pad = baseSize * 0.6f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // Подгоняем размер шрифта, чтобы строка влезла по ширине.
        val fitted = lines.mapIndexed { i, text ->
            var size = sizes[i]
            textPaint.textSize = size
            val maxWidth = w - 2 * pad
            val measured = textPaint.measureText(text)
            if (measured > maxWidth) size *= maxWidth / measured
            size
        }

        val lineGap = baseSize * 0.35f
        val barHeight = fitted.sum() + lineGap * (lines.size - 1) + 2 * pad + baseSize * 0.3f
        val barPaint = Paint().apply { color = Color.argb(170, 0, 0, 0) }
        canvas.drawRect(0f, h - barHeight, w, h, barPaint)

        var y = h - barHeight + pad
        lines.forEachIndexed { i, text ->
            textPaint.textSize = fitted[i]
            y += fitted[i]
            canvas.drawText(text, pad, y, textPaint)
            y += lineGap
        }
        return out
    }

    /** Итоговое фото: плашка + маркеры. */
    fun withMarkers(base: Bitmap, markers: List<PointF>): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val r = markerRadius(out.width, out.height)
        markers.forEachIndexed { i, p -> drawMarker(canvas, p.x, p.y, r, i + 1) }
        return out
    }

    /** Маркер: кольцо с перекрестием и номер. Используется и на экране, и в файле. */
    fun drawMarker(canvas: Canvas, x: Float, y: Float, r: Float, number: Int) {
        // Кисти создаём на каждый вызов: метод вызывается и из UI, и из фонового потока.
        val ringWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
        }
        val ringRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.rgb(230, 20, 20)
            strokeCap = Paint.Cap.ROUND
        }
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val labelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 20, 20); typeface = bold }
        val labelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE; typeface = bold
        }
        ringWhite.strokeWidth = r * 0.32f
        ringRed.strokeWidth = r * 0.16f

        for (p in listOf(ringWhite, ringRed)) {
            canvas.drawCircle(x, y, r, p)
            // перекрестие снаружи кольца
            canvas.drawLine(x - r * 1.6f, y, x - r * 1.15f, y, p)
            canvas.drawLine(x + r * 1.15f, y, x + r * 1.6f, y, p)
            canvas.drawLine(x, y - r * 1.6f, x, y - r * 1.15f, p)
            canvas.drawLine(x, y + r * 1.15f, x, y + r * 1.6f, p)
        }

        val text = number.toString()
        labelFill.textSize = r * 1.2f
        labelStroke.textSize = r * 1.2f
        labelStroke.strokeWidth = r * 0.25f
        val tx = x + r * 1.25f
        val ty = y - r * 1.25f
        canvas.drawText(text, tx, ty, labelStroke)
        canvas.drawText(text, tx, ty, labelFill)
    }
}
