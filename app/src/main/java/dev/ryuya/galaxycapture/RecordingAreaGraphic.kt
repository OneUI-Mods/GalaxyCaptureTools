package dev.ryuya.galaxycapture

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Paint.Style
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/* Original glyphs within Samsung's native phone outline and selected-state background. */
class RecordingAreaGraphic(window: Boolean) : Drawable() {
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val window: Boolean

    init {
        this.window = window
        paint.setColor(Color.WHITE)
    }

    override fun draw(canvas: Canvas) {
        val b = getBounds()
        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 48f, b.height() / 96f)
        paint.setStyle(Style.STROKE)
        paint.setStrokeWidth(1.8f)
        if (window) {
            // One isolated window, unlike an application's multiple screens.
            canvas.drawRoundRect(9f, 34f, 39f, 68f, 3f, 3f, paint)
            canvas.drawLine(9f, 42f, 39f, 42f, paint)
            canvas.drawLine(13f, 38f, 15f, 38f, paint)
        } else {
            canvas.drawRoundRect(8f, 16f, 40f, 80f, 3f, 3f, paint)
            canvas.drawLine(8f, 26f, 40f, 26f, paint)
            paint.setStyle(Style.FILL)
            canvas.drawRoundRect(13f, 33f, 35f, 45f, 2f, 2f, paint)
            canvas.drawRoundRect(13f, 50f, 22f, 63f, 2f, 2f, paint)
            canvas.drawRoundRect(26f, 50f, 35f, 63f, 2f, 2f, paint)
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        paint.setAlpha(alpha)
        invalidateSelf()
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.setColorFilter(filter)
        invalidateSelf()
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }
}
