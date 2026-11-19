package dev.ryuya.galaxycapture

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.view.View
import android.view.WindowManager
import dev.ryuya.galaxycapture.hook.Hooks
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Samples only the area below Samsung's own recording dialog, using normal capture policy. */
internal class RecordingDialogBackdrop(
    private val panel: View,
    private val main: Handler,
    private val radius: Float,
    private val density: Float,
    private val capture: WindowBackdropCapture,
    private val onSourceChanged: () -> Unit,
) : Drawable() {
    private data class Region(val display: Int, val rotation: Int, val type: Int, val tag: String, val crop: Rect)

    private val location = IntArray(2)
    private val keyguard = panel.context.getSystemService(KeyguardManager::class.java)
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lens = RuntimeShader(REFRACTION)
    private var source: Bitmap? = null
    private var sourceRegion: Region? = null
    private var enabled = false
    private var inFlight = false
    private var revision = 0
    private var rejections = 0
    private var drawn = false
    private var unavailable = false
    private val readNext = Runnable { read() }

    val ready: Boolean get() = source != null
    var dark = false
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    fun start() {
        if (enabled || unavailable) return
        enabled = true
        rejections = 0
        main.post(readNext)
    }

    fun stop() {
        enabled = false
        revision++
        main.removeCallbacks(readNext)
        replaceSource(null, null, notify = false)
    }

    private fun currentRegion(): Region? {
        if (!panel.isAttachedToWindow || !panel.isShown || panel.width <= 0 || panel.height <= 0 ||
            keyguard?.isKeyguardLocked != false) return null
        val display = panel.display ?: return null
        val params = panel.rootView.layoutParams as? WindowManager.LayoutParams ?: return null
        if (display.displayId != 0 || params.type !in RECORDING_DIALOG_TYPES ||
            params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) return null
        panel.getLocationOnScreen(location)
        val frame = Rect()
        panel.getWindowVisibleDisplayFrame(frame)
        val crop = Rect(location[0], location[1], location[0] + panel.width, location[1] + panel.height)
        if (!frame.contains(crop)) return null
        val tag = params.title?.toString().orEmpty().ifBlank { panel.context.packageName }
        return Region(display.displayId, display.rotation, params.type, tag, crop)
    }

    private fun read() {
        if (!enabled || inFlight) return
        val region = currentRegion()
        if (region == null) {
            replaceSource(null, null)
            main.postDelayed(readNext, 250)
            return
        }
        if (sourceRegion != region) replaceSource(null, null)
        val request = revision
        inFlight = true
        worker.execute {
            val result = capture.read(region.display, region.type, region.tag, region.crop)
            main.post {
                inFlight = false
                when {
                    !enabled || request != revision || currentRegion() != region -> {
                        result?.recycle()
                        if (enabled) main.post(readNext)
                    }
                    result == null -> {
                        rejections++
                        replaceSource(null, null)
                        if (rejections <= 3) main.postDelayed(readNext, 400)
                    }
                    else -> {
                        rejections = 0
                        try {
                            replaceSource(result, region)
                            main.postDelayed(readNext, 83)
                        } catch (error: RuntimeException) {
                            fail(error)
                        }
                    }
                }
            }
        }
    }

    private fun replaceSource(bitmap: Bitmap?, region: Region?, notify: Boolean = true) {
        if (bitmap == null && source == null) return
        val wasReady = ready
        source = bitmap
        sourceRegion = region
        paint.shader = null
        if (bitmap != null && region != null) {
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            matrix.setScale(region.crop.width().toFloat() / bitmap.width, region.crop.height().toFloat() / bitmap.height)
            shader.setLocalMatrix(matrix)
            lens.setInputShader("backdrop", shader)
            lens.setFloatUniform("size", region.crop.width().toFloat(), region.crop.height().toFloat())
            lens.setFloatUniform("radius", radius)
            lens.setFloatUniform("diffusion", (5f * density).coerceAtLeast(2f))
            paint.shader = lens
        } else {
            // Release the last image held by the shader without retaining a dialog frame.
            lens.setInputShader("backdrop", android.graphics.LinearGradient(
                0f, 0f, 1f, 0f, 0, 0, Shader.TileMode.CLAMP))
        }
        if (notify && wasReady != ready) onSourceChanged()
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (!ready || !canvas.isHardwareAccelerated || bounds.isEmpty) return
        val r = radius.coerceAtMost(minOf(bounds.width(), bounds.height()) * .5f)
        try {
            lens.setFloatUniform("night", if (dark) 1f else 0f)
            canvas.drawRoundRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), r, r, paint)
        } catch (error: RuntimeException) {
            main.post { fail(error) }
            return
        }
        if (!drawn) {
            drawn = true
            Hooks.log("CAPTURE_RECORDING_START_GLASS_REFRACTED")
        }
    }

    private fun fail(error: RuntimeException) {
        if (unavailable) return
        unavailable = true
        stop()
        onSourceChanged()
        Hooks.log("CAPTURE_RECORDING_START_GLASS_RENDERER_UNAVAILABLE ${error.javaClass.simpleName}")
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Required by Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        val RECORDING_DIALOG_TYPES = setOf(2038, 2012)
        val worker = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { work ->
            Thread(work, "RecordingDialogGlass")
        }
        const val REFRACTION = """
            uniform shader backdrop;
            uniform float2 size;
            uniform float radius;
            uniform float diffusion;
            uniform float night;

            half4 main(float2 p) {
                float r = min(radius, min(size.x, size.y) * 0.5);
                float2 local = p - size * 0.5;
                float2 corner = abs(local) - (size * 0.5 - r);
                float2 outer = max(corner, float2(0));
                float distance = length(outer) + min(max(corner.x, corner.y), 0.0) - r;
                float edge = 1.0 - smoothstep(0.0, diffusion * 3.0, -distance);
                float2 direction = normalize(local + float2(0.001));
                if (max(corner.x, corner.y) == corner.x && corner.y <= 0.0)
                    direction = float2(sign(local.x), 0.0);
                else if (corner.x <= 0.0)
                    direction = float2(0.0, sign(local.y));
                else direction = normalize(outer + float2(0.001)) * sign(local);

                float2 q = p - direction * edge * edge * diffusion * 2.6;
                float2 d = float2(diffusion, diffusion);
                half3 color = backdrop.eval(q).rgb * 0.28;
                color += (backdrop.eval(q + d).rgb + backdrop.eval(q - d).rgb +
                          backdrop.eval(q + float2(d.x, -d.y)).rgb +
                          backdrop.eval(q + float2(-d.x, d.y)).rgb) * 0.18;
                half3 tint = mix(half3(0.91, 0.95, 0.99), half3(0.075, 0.095, 0.13), half(night));
                half3 surface = mix(color, tint, half(mix(0.78, 0.67, night) - edge * 0.22));
                return half4(surface, 1.0);
            }
        """
    }
}

/** Requires the compositor to identify this very dialog before using the returned image. */
internal class WindowBackdropCapture {
    private val manager = requireNotNull(Class.forName("android.view.WindowManagerGlobal")
        .getMethod("getWindowManagerService").invoke(null))
    private val takeScreenshot = Class.forName("android.view.IWindowManager").getMethod(
        "takeScreenshotToTargetWindow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType, Rect::class.java, Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
    )
    private val failedReason = takeScreenshot.returnType.getMethod("getFailedReason")
    private val targetName = takeScreenshot.returnType.getMethod("getTargetWindowName")
    private val secureName = takeScreenshot.returnType.getMethod("getSecuredWindowName")
    private val image = takeScreenshot.returnType.getMethod("getCapturedBitmap")
    private var reported = false

    fun read(display: Int, type: Int, tag: String, crop: Rect): Bitmap? = try {
        val result = takeScreenshot.invoke(manager, display, type, false, crop,
            ceil(crop.width() / 6f).toInt(), ceil(crop.height() / 6f).toInt(), false, false)
        val bitmap = result?.let { image.invoke(it) as? Bitmap }
        val policy = result?.let { failedReason.invoke(it) as Int }
        val targetMatched = result != null && targetName.invoke(result) == "[Window_Target:$tag]"
        val protected = result != null && !(secureName.invoke(result) as? String).isNullOrEmpty()
        if (policy == 0 && targetMatched && !protected) bitmap
        else {
            bitmap?.recycle()
            unavailable("policy=$policy targetMatched=$targetMatched")
            null
        }
    } catch (error: Exception) {
        unavailable(error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName)
        null
    }

    private fun unavailable(reason: String) {
        if (reported) return
        reported = true
        Hooks.log("CAPTURE_RECORDING_START_GLASS_SOURCE_UNAVAILABLE $reason")
    }
}
