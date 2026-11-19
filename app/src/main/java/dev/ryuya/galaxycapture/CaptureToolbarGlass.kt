package dev.ryuya.galaxycapture

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.Hooks
import java.util.WeakHashMap
import kotlin.math.min

/** Styles only Samsung's existing toolbar plates and leaves their native blur and hit areas intact. */
internal class CaptureToolbarGlass(private val runtime: HookRuntime) {
    private val main = Handler(Looper.getMainLooper())
    private val plates = WeakHashMap<View, Plate>()
    private val reported = mutableSetOf<String>()
    private var reduceTransparency = readTransparencySetting()
    private val targets = buildMap {
        val resources = runtime.context.resources
        val packageName = runtime.context.packageName
        for ((name, views) in LAYOUTS) {
            val layout = resources.getIdentifier(name, "layout", packageName)
            if (layout == 0) continue
            val ids = views.map { resources.getIdentifier(it, "id", packageName) }.filter { it != 0 }
            if (ids.isNotEmpty()) put(layout, Targets(name, ids))
        }
    }

    private data class Targets(val name: String, val ids: List<Int>)

    private fun install() {
        if (targets.isEmpty()) return
        val inflate = LayoutInflater::class.java.getMethod("inflate", Int::class.javaPrimitiveType,
            ViewGroup::class.java, Boolean::class.javaPrimitiveType)
        Hooks.hook(inflate, object : HookCallback() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if (targets[p.args?.getOrNull(0) as? Int] == null) return
                val parent = p.args?.getOrNull(1) as? ViewGroup ?: return
                p.setObjectExtra("glassChildCount", parent.childCount)
            }

            override fun afterHookedMethod(p: MethodHookParam) {
                val spec = targets[p.args?.getOrNull(0) as? Int] ?: return
                val inflated = p.result as? View ?: return
                if (inflated.context.packageName != runtime.context.packageName) return
                val parent = p.args?.getOrNull(1) as? ViewGroup
                val before = p.getObjectExtra("glassChildCount") as? Int
                val roots = if (inflated === parent && parent != null && before != null) {
                    (before until parent.childCount).map(parent::getChildAt)
                } else listOf(inflated)
                main.post {
                    for (root in roots) {
                        for (id in spec.ids) root.findViewById<View>(id)?.let { bind(it, spec.name) }
                    }
                }
            }
        })
        runtime.onOptionsChanged { main.post { plates.entries.toList().forEach { (view, effect) -> update(view, effect) } } }
        runtime.context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(REDUCE_TRANSPARENCY), false, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    reduceTransparency = readTransparencySetting()
                    plates.entries.toList().forEach { (view, effect) -> update(view, effect) }
                }
            },
        )
        Hooks.log("CAPTURE_TOOLBAR_GLASS_READY layouts=${targets.size}")
    }

    private fun bind(view: View, layout: String) {
        if (plates.containsKey(view)) return
        val effect = Plate {
            if (reported.add(layout)) Hooks.log("CAPTURE_TOOLBAR_GLASS_DRAWN $layout")
        }
        plates[view] = effect
        view.addOnLayoutChangeListener { target, _, _, _, _, _, _, _, _ -> update(target, effect) }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = update(v, effect)
            override fun onViewDetachedFromWindow(v: View) = v.overlay.remove(effect)
        })
        update(view, effect)
    }

    private fun update(view: View, effect: Plate) {
        val options = runtime.options
        if (options?.enabled != true || !options.glassCaptureControls || !view.isAttachedToWindow ||
            view.width <= 0 || view.height <= 0 || reduceTransparency) {
            view.overlay.remove(effect)
            return
        }
        effect.update(view)
        view.overlay.add(effect)
    }

    private fun readTransparencySetting(): Boolean =
        Settings.System.getInt(runtime.context.contentResolver, REDUCE_TRANSPARENCY, 0) != 0

    private class Plate(private val onDrawn: () -> Unit) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outline = Outline()
        private val stockBounds = Rect()
        private val pathBounds = RectF()
        private lateinit var face: LinearGradient
        private lateinit var rim: LinearGradient
        private var radius = 0f
        private var density = 1f
        private var dark = false

        fun update(view: View) {
            val night = view.resources.configuration.isNightModeActive
            outline.setEmpty()
            view.background?.getOutline(outline)
            val native = outline.radius > 0 && outline.getRect(stockBounds) && !stockBounds.isEmpty
            if (!native) stockBounds.set(0, 0, view.width, view.height)
            val corner = if (native) outline.radius else min(stockBounds.width(), stockBounds.height()) / 2f
            val scale = view.resources.displayMetrics.density
            if (bounds == stockBounds && dark == night && radius == corner && density == scale && ::rim.isInitialized) return
            bounds = stockBounds
            dark = night
            radius = corner
            density = scale
            face = gradient(if (dark) intArrayOf(0x46e8f3ff, 0x0627323c, 0x39101a27)
                else intArrayOf(0x59ffffff, 0x06f2f7ff, 0x24476982))
            rim = gradient(if (dark) intArrayOf(0xcff4f9ff.toInt(), 0x42e2f0ff, 0x72364658)
                else intArrayOf(0xeaffffff.toInt(), 0x509eabb7, 0x795c7a91))
            invalidateSelf()
        }

        private fun gradient(colors: IntArray): LinearGradient =
            LinearGradient(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right * .64f + bounds.left * .36f,
                bounds.bottom.toFloat(), colors, floatArrayOf(0f, .46f, 1f), Shader.TileMode.CLAMP)

        override fun draw(canvas: Canvas) {
            if (!::rim.isInitialized || bounds.isEmpty) return
            val line = density * .76f
            pathBounds.set(bounds)
            pathBounds.inset(line, line)
            val curve = (radius - line).coerceAtLeast(0f)
            paint.style = Paint.Style.FILL
            paint.shader = face
            canvas.drawRoundRect(pathBounds, curve, curve, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = line
            paint.shader = rim
            canvas.drawRoundRect(pathBounds, curve, curve, paint)
            onDrawn()
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Required by Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val REDUCE_TRANSPARENCY = "accessibility_reduce_transparency"
        private val LAYOUTS = mapOf(
            "scroll_capture_action_button_stand_alone" to listOf("screenshot_toolbar_background"),
            "screen_recorder_floating_button" to listOf("button_layout", "pen_setting_layout", "handler_layout"),
            "partial_recording_toolbar" to listOf("partial_toolbar_record_container_view", "partial_toolbar_operation_container_view"),
        )

        fun install(runtime: HookRuntime) = CaptureToolbarGlass(runtime).install()
    }
}
