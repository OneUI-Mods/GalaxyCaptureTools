package dev.ryuya.galaxycapture

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.min

/* SurfaceControl affine transform, in its (dsdx, dtdx, dsdy, dtdy) order. */
object RecordingTransform {
    @JvmStatic
    fun fit(
        width: Int,
        height: Int,
        outputWidth: Int,
        outputHeight: Int,
        quarterTurns: Int,
    ): RecordingTransform.Transform? {
        require(!(width <= 0 || height <= 0 || outputWidth <= 0 || outputHeight <= 0)) {
            "Empty recording geometry"
        }
        val q = Math.floorMod(quarterTurns, 4)
        val rw: Int = if (q % 2 == 0) width else height
        val rh: Int = if (q % 2 == 0) height else width
        val s = min(outputWidth.toFloat() / rw, outputHeight.toFloat() / rh)
        val x: Float = (outputWidth - rw * s) / 2
        val y: Float = (outputHeight - rh * s) / 2
        return when (q) {
            1 -> RecordingTransform.Transform(0f, s, -s, 0f, x + height * s, y)
            2 -> RecordingTransform.Transform(-s, 0f, 0f, -s, x + width * s, y + height * s)
            3 -> RecordingTransform.Transform(0f, -s, s, 0f, x, y + width * s)
            else -> RecordingTransform.Transform(s, 0f, 0f, s, x, y)
        }
    }

    @JvmRecord
    data class Transform(
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        val x: Float,
        val y: Float,
    ) {
        fun mapX(x: Float, y: Float): Float {
            return a * x + c * y + this.x
        }

        fun mapY(x: Float, y: Float): Float {
            return b * x + d * y + this.y
        }

        override fun toString(): String = "Transform[a=$a, b=$b, c=$c, d=$d, x=$x, y=$y]"
    }
}
