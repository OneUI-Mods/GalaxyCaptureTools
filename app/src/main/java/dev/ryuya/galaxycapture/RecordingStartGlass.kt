package dev.ryuya.galaxycapture

import android.app.Dialog
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import dev.ryuya.galaxycapture.hook.Hooks
import java.util.function.Consumer
import kotlin.math.roundToInt

/** Adds a backdrop and edge lighting to Samsung's recording confirmation dialog. */
internal class RecordingStartGlass(private val runtime: HookRuntime) {
    private val main = Handler(Looper.getMainLooper())
    private val blur by lazy { runCatching { SamsungWindowBlur() }.getOrNull() }
    private var active: Session? = null
    private var reportedFailure = false

    fun attach(dialog: Dialog) {
        val window = dialog.window ?: return
        val resources = window.context.resources
        val panelId = resources.getIdentifier("parentPanel", "id", runtime.context.packageName)
        val panel = dialog.findViewById<View>(panelId) ?: return
        if (active?.panel === panel) return
        active?.close()
        val cornerId = resources.getIdentifier("sesl_dialog_background_corner_radius", "dimen", runtime.context.packageName)
        val density = resources.displayMetrics.density
        val corner = if (cornerId != 0) resources.getDimension(cornerId) else 26f * density
        val secure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        val stock = findPlate(window.decorView.background)
        val session = Session(panel, density, corner, secure, stock)
        active = session
        try {
            session.start()
        } catch (error: Exception) {
            runCatching { session.close() }
            reportFailure(error)
        }
    }

    init {
        runtime.onOptionsChanged { main.post { active?.update() } }
    }

    private fun findPlate(drawable: Drawable?): GradientDrawable? = when (drawable) {
        is GradientDrawable -> drawable
        is InsetDrawable -> findPlate(drawable.drawable)
        else -> null
    }

    private inner class Session(
        val panel: View,
        val density: Float,
        val corner: Float,
        val secure: Boolean,
        val stock: GradientDrawable?,
    ) : View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
        private val windowManager = panel.context.getSystemService(WindowManager::class.java)
        private val resolver = panel.context.contentResolver
        private val chrome = Rim(density, corner)
        private val stockColor: ColorStateList? = stock?.color
        private val stockAlpha = stock?.alpha ?: 255
        private var blurInfo: Any? = null
        private var appliedAlpha: Int? = null
        private var styled = false
        private val change = Consumer<Boolean> { update() }
        private val settings = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = update()
        }

        fun start() {
            panel.addOnAttachStateChangeListener(this)
            panel.addOnLayoutChangeListener(this)
            resolver.registerContentObserver(Settings.System.getUriFor(REDUCE_TRANSPARENCY), false, settings)
            if (!secure) windowManager?.addCrossWindowBlurEnabledListener(panel.context.mainExecutor, change)
            update()
        }

        fun update() {
            if (active !== this) return
            try {
                val option = runtime.options
                val reduce = Settings.System.getInt(resolver, REDUCE_TRANSPARENCY, 0) != 0
                if (option?.enabled != true || !option.glassCaptureControls || reduce ||
                    !panel.isAttachedToWindow || panel.width <= 0 || panel.height <= 0) {
                    restore()
                    return
                }
                val dark = panel.resources.configuration.isNightModeActive
                val nativeBlur = updateBlur(dark)
                val alpha = if (nativeBlur) (stockAlpha * 0.78f).roundToInt() else stockAlpha
                if (stockColor != null && appliedAlpha != alpha) {
                    stock?.mutate()?.alpha = alpha
                    appliedAlpha = alpha
                }
                chrome.resize(panel.width, panel.height, dark)
                panel.overlay.add(chrome)
                if (!styled) {
                    styled = true
                    Hooks.log("CAPTURE_RECORDING_START_GLASS_APPLIED blur=$nativeBlur stock=${stock != null}")
                }
            } catch (error: Exception) {
                runCatching { restore() }
                reportFailure(error)
            }
        }

        private fun updateBlur(dark: Boolean): Boolean {
            if (secure || windowManager?.isCrossWindowBlurEnabled != true) {
                clearBlur()
                return false
            }
            val api = blur ?: return false
            val current = api.current(panel)
            if (blurInfo != null && current === blurInfo) return true
            if (current != null || panel.background != null) return false
            val base = stockColor?.getColorForState(panel.drawableState, stockColor.defaultColor)
                ?: if (dark) 0xff222731.toInt() else 0xffedf2f7.toInt()
            val tone = ColorUtils.setAlphaComponent(base, if (dark) 205 else 217)
            blurInfo = api.apply(panel, (23f * density).roundToInt(), corner, tone)
            return blurInfo != null
        }

        private fun clearBlur() {
            val inserted = blurInfo ?: return
            blurInfo = null
            val api = blur ?: return
            if (api.current(panel) === inserted) api.clear(panel)
        }

        private fun restore() {
            panel.overlay.remove(chrome)
            if (appliedAlpha != null && stock?.alpha == appliedAlpha) stock?.alpha = stockAlpha
            appliedAlpha = null
            clearBlur()
            styled = false
        }

        fun close() {
            runCatching { restore() }
            panel.removeOnAttachStateChangeListener(this)
            panel.removeOnLayoutChangeListener(this)
            resolver.unregisterContentObserver(settings)
            if (!secure) windowManager?.removeCrossWindowBlurEnabledListener(change)
            if (active === this) active = null
        }

        override fun onViewAttachedToWindow(v: View) = update()
        override fun onViewDetachedFromWindow(v: View) = close()

        override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int,
            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) = update()
    }

    private fun reportFailure(error: Exception) {
        if (!reportedFailure) {
            reportedFailure = true
            Hooks.log("Recording start glass unavailable: ${error.javaClass.simpleName}")
        }
    }

    private class SamsungWindowBlur {
        private val info = Class.forName("android.view.SemBlurInfo")
        private val builder = Class.forName("android.view.SemBlurInfo\$Builder")
        private val constructor = builder.getConstructor(Int::class.javaPrimitiveType)
        private val radius = builder.getMethod("setRadius", Int::class.javaPrimitiveType)
        private val corner = builder.getMethod("setBackgroundCornerRadius", Float::class.javaPrimitiveType)
        private val tint = builder.getMethod("setBackgroundColor", Int::class.javaPrimitiveType)
        private val build = builder.getMethod("build")
        private val set = View::class.java.getMethod("semSetBlurInfo", info)
        private val installed = View::class.java.getDeclaredField("mBlurInfo").apply { isAccessible = true }
        private val mode = info.getField("BLUR_MODE_WINDOW").getInt(null)

        fun current(view: View): Any? = installed.get(view)

        fun apply(view: View, pixels: Int, cornerPixels: Float, color: Int): Any? {
            val config = constructor.newInstance(mode)
            radius.invoke(config, pixels)
            corner.invoke(config, cornerPixels)
            tint.invoke(config, color)
            val requested = build.invoke(config)
            set.invoke(view, requested)
            return current(view).takeIf { it === requested }
        }

        fun clear(view: View) {
            set.invoke(view, null)
        }
    }

    private class Rim(private val density: Float, private val corner: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val area = RectF()
        private var dark = false
        private var light: LinearGradient? = null
        private var edge: LinearGradient? = null
        private var drawn = false

        fun resize(width: Int, height: Int, night: Boolean) {
            if (bounds.width() == width && bounds.height() == height && dark == night) return
            setBounds(0, 0, width, height)
            dark = night
            // Inset the lighting inside the Samsung contour so its smooth corners stay clean.
            val inset = 1.55f * density
            area.set(inset, inset, width - inset, height - inset)
            light = LinearGradient(0f, inset, width * .32f, height * .9f,
                intArrayOf(if (dark) 0x24b3d8ff else 0x3effffff, Color.TRANSPARENT, 0x140c1830),
                floatArrayOf(0f, .46f, 1f), Shader.TileMode.CLAMP)
            edge = LinearGradient(0f, inset, width * .8f, height.toFloat(),
                intArrayOf(0x91e6f3ff.toInt(), 0x25dceafa, 0x4b6ea0cd), floatArrayOf(0f, .48f, 1f),
                Shader.TileMode.CLAMP)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            if (area.isEmpty) return
            val radius = (corner - 1.55f * density).coerceAtLeast(0f)
            paint.style = Paint.Style.FILL
            paint.shader = light
            canvas.drawRoundRect(area, radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = .85f * density
            paint.shader = edge
            canvas.drawRoundRect(area, radius, radius, paint)
            if (!drawn) {
                drawn = true
                Hooks.log("CAPTURE_RECORDING_START_GLASS_DRAWN")
            }
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Required by Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val REDUCE_TRANSPARENCY = "accessibility_reduce_transparency"
    }
}
