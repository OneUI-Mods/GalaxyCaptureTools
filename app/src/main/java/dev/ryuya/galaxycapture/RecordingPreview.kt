package dev.ryuya.galaxycapture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.google.android.material.color.MaterialColors

/* Illustrates rotation correction without using any captured screen content. */
class RecordingPreview(context: Context?) : View(context) {
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = getResources().getDisplayMetrics().density
        val cx: Float = getWidth() / 2f
        val cy: Float = getHeight() / 2f
        val ink = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        val surface =
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer)
        paint.setColor(ink)
        canvas.drawRoundRect(
            cx - 126 * d,
            cy - 26 * d,
            cx - 34 * d,
            cy + 26 * d,
            12 * d,
            12 * d,
            paint,
        )
        paint.setColor(surface)
        canvas.drawRoundRect(
            cx - 121 * d,
            cy - 21 * d,
            cx - 39 * d,
            cy + 21 * d,
            8 * d,
            8 * d,
            paint,
        )
        paint.setColor(ink)
        canvas.drawCircle(cx - 98 * d, cy - 7 * d, 6 * d, paint)
        paint.setStrokeWidth(2 * d)
        canvas.drawLine(cx - 10 * d, cy, cx + 18 * d, cy, paint)
        canvas.drawLine(cx + 10 * d, cy - 8 * d, cx + 18 * d, cy, paint)
        canvas.drawLine(cx + 10 * d, cy + 8 * d, cx + 18 * d, cy, paint)
        canvas.drawRoundRect(
            cx + 48 * d,
            cy - 44 * d,
            cx + 100 * d,
            cy + 44 * d,
            12 * d,
            12 * d,
            paint,
        )
        val preferences = getContext().getSharedPreferences("config", 0)
        val corrected: Boolean =
            preferences.getBoolean("enabled", false) && preferences.getBoolean("rotation", true)
        paint.setColor(if (corrected) surface else Color.BLACK)
        canvas.drawRoundRect(
            cx + 53 * d,
            cy - 39 * d,
            cx + 95 * d,
            cy + 39 * d,
            8 * d,
            8 * d,
            paint,
        )
        if (!corrected) {
            paint.setColor(surface)
            canvas.drawRect(cx + 53 * d, cy - 13 * d, cx + 95 * d, cy + 13 * d, paint)
        }
        paint.setColor(ink)
        canvas.drawCircle(
            cx + 65 * d,
            cy - (if (corrected) 21 else 5) * d,
            if (corrected) 6 * d else 3 * d,
            paint,
        )
    }
}
