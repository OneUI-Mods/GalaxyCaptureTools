package dev.ryuya.galaxycapture

import android.content.res.Configuration
import android.content.res.Resources.NotFoundException
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.View.OnClickListener
import android.view.View.OnLayoutChangeListener
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnPreDrawListener
import android.widget.LinearLayout
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import java.lang.ref.WeakReference
import java.util.ArrayList
import java.util.WeakHashMap
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.log
import kotlin.math.min

/*
 * Moves the actual Samsung recording chip, including its native gesture detector and stop dialog.
 */
class RightRecordingIndicator private constructor(runtime: HookRuntime) {
    private val rt: HookRuntime
    private val main: Handler = Handler(Looper.getMainLooper())
    private val states: MutableMap<Any?, RightRecordingIndicator.State?> =
        WeakHashMap<Any?, RightRecordingIndicator.State?>()
    private var nativeChip: Class<*>? = null
    private var failed: Boolean = false

    init {
        rt = runtime
    }

    @Throws(Exception::class)
    private fun install(loader: ClassLoader?) {
        if (
            HookCompatibility.platform(Build.VERSION.SDK_INT) ==
                HookCompatibility.Platform.ANDROID_17
        ) {
            installAndroid17(loader)
        } else {
            installAndroid16(loader)
        }
    }

    /* Preserves the Android 16 Fragment lifecycle and binding contract. */
    @Throws(Exception::class)
    private fun installAndroid16(loader: ClassLoader?) {
        val fragment =
            Reflect.findClass(
                "com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment",
                loader,
            )
        val controller =
            Reflect.findClass(
                "com.android.systemui.statusbar.phone.ongoingcast.SamsungOngoingCastController",
                loader,
            )
        nativeChip =
            Reflect.findClass(
                "com.android.systemui.statusbar.phone.TouchInterceptFrameLayout",
                loader,
            )
        check(
            !(!ViewGroup::class.java.isAssignableFrom(nativeChip) ||
                (nativeChip!!.getDeclaredField("customClickListener").getType() !=
                    OnClickListener::class.java) ||
                nativeChip!!.getDeclaredField("touchForwardView").getType() != View::class.java)
        ) {
            "Native interactive chip contract changed"
        }
        fragment!!.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
        fragment!!.getDeclaredMethod("onDestroyView")
        controller!!.getDeclaredMethod("updateAll", String::class.java)
        Hooks.hookAllMethods(
            fragment,
            "onViewCreated",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (failed || p.hasThrowable()) return
                    try {
                        val owner = Reflect.getObjectField(p.thisObject!!, "mOngoingCastController")
                        val root: View? =
                            Reflect.getObjectField(p.thisObject!!, "mStatusBar") as View?
                        val chip: View? =
                            Reflect.getObjectField(p.thisObject!!, "mOngoingCastChip") as View?
                        check(
                            !(!controller!!.isInstance(owner) ||
                                !nativeChip!!.isInstance(chip) ||
                                ("samsung_ongoing_cast_chip" !=
                                    RightRecordingIndicator.name(chip!!)))
                        ) {
                            "Recording chip binding changed"
                        }
                        val id =
                            root!!
                                .getResources()
                                .getIdentifier(
                                    "status_bar_end_side_content",
                                    "id",
                                    "com.android.systemui",
                                )
                        val end: View? = if (id == 0) null else root!!.findViewById<View?>(id)
                        check(
                            !((end !is LinearLayout) ||
                                end.getOrientation() != LinearLayout.HORIZONTAL ||
                                (chip!!.getParent() !is LinearLayout))
                        ) {
                            "Status-bar chip containers changed"
                        }
                        val prior = states.remove(p.thisObject)
                        if (prior != null) prior.destroy()
                        val original = chip!!.parent as LinearLayout
                        val state = State(root!!, chip, original, end!!, owner)
                        states.put(p.thisObject, state)
                        state.observe()
                        state.queue()
                    } catch (error: Throwable) {
                        fail(error)
                    }
                }
            },
        )
        Hooks.hookAllMethods(
            fragment,
            "onDestroyView",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val state = states.remove(p.thisObject)
                    if (state != null) state.destroy()
                }
            },
        )
        Hooks.hookAllMethods(
            controller,
            "updateAll",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (failed || p.hasThrowable()) return
                    for (state in ArrayList<RightRecordingIndicator.State?>(states.values)) if (
                        state!!.owner.get() === p.thisObject
                    )
                        state!!.nativeUpdated()
                }
            },
        )
        rt.onOptionsChanged(
            Runnable {
                main.post(
                    Runnable {
                        for (state in ArrayList<RightRecordingIndicator.State?>(states.values)) {
                            state!!.fitRejected = false
                            state!!.queue()
                        }
                    }
                )
            }
        )
        Hooks.log("GalaxyCapture: native Samsung recording chip relocation ready")
    }

    /* Android 17 binds the native chip through its controller instead of the removed Fragment. */
    @Throws(Exception::class)
    private fun installAndroid17(loader: ClassLoader?) {
        val controller =
            Reflect.findClass(
                "com.android.systemui.statusbar.phone.ongoingcast.SamsungOngoingCastController",
                loader,
            )
        nativeChip =
            Reflect.findClass(
                "com.android.systemui.statusbar.phone.TouchInterceptFrameLayout",
                loader,
            )
        controller!!.getDeclaredMethod("setChipView", View::class.java)
        controller!!.getDeclaredMethod("updateAll", String::class.java)
        check(
            !(!ViewGroup::class.java.isAssignableFrom(nativeChip) ||
                (nativeChip!!.getDeclaredField("customClickListener").getType() !=
                    OnClickListener::class.java) ||
                nativeChip!!.getDeclaredField("touchForwardView").getType() != View::class.java)
        ) {
            "Android 17 native interactive chip contract changed"
        }
        Hooks.hookAllMethods(
            controller,
            "setChipView",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (failed || p.hasThrowable()) return
                    val previous = states.remove(p.thisObject)
                    if (previous != null) previous.destroy()
                    if (p!!.args!![0] !is View) return
                    val chip = p.args[0] as View
                    val owner = p.thisObject
                    if (chip.isAttachedToWindow()) {
                        bindAndroid17(owner, chip)
                    } else {
                        chip.addOnAttachStateChangeListener(
                            object : OnAttachStateChangeListener {
                                override fun onViewAttachedToWindow(view: View) {
                                    view.removeOnAttachStateChangeListener(this)
                                    bindAndroid17(owner, view)
                                }

                                override fun onViewDetachedFromWindow(view: View) {}
                            }
                        )
                    }
                }
            },
        )
        Hooks.hookAllMethods(
            controller,
            "updateAll",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val state = states.get(p.thisObject)
                    if (!failed && !p.hasThrowable() && state != null) state.nativeUpdated()
                }
            },
        )
        rt.onOptionsChanged(
            Runnable {
                main.post(
                    Runnable {
                        for (state in ArrayList<RightRecordingIndicator.State?>(states.values)) {
                            state!!.fitRejected = false
                            state!!.queue()
                        }
                    }
                )
            }
        )
        Hooks.log(
            "GalaxyCapture: Android 17 controller chip binding registered; visual test pending"
        )
    }

    private fun bindAndroid17(owner: Any?, chip: View) {
        if (failed || !chip.isAttachedToWindow()) return
        try {
            if (Reflect.getObjectField(owner!!, "chipView") !== chip) return
            var previous = states.get(owner)
            if (previous != null && previous.chip.get() === chip) {
                previous.queue()
                return
            }
            previous = states.remove(owner)
            if (previous != null) previous.destroy()
            val root = chip.getRootView()
            check(
                !(!nativeChip!!.isInstance(chip) ||
                    "samsung_ongoing_cast_chip" != RightRecordingIndicator.name(chip))
            ) {
                "Android 17 cast chip view changed"
            }
            val id =
                root
                    .getResources()
                    .getIdentifier("status_bar_end_side_content", "id", "com.android.systemui")
            val end: View? = if (id == 0) null else root.findViewById<View?>(id)
            check(
                !((end !is LinearLayout) ||
                    end.getOrientation() != LinearLayout.HORIZONTAL ||
                    (chip.getParent() !is LinearLayout))
            ) {
                "Android 17 chip containers require further validation"
            }
            val original = chip!!.parent as LinearLayout
            val state = State(root!!, chip, original, end!!, owner)
            states.put(owner, state)
            state.observe()
            state.attachment =
                object : OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {}

                    override fun onViewDetachedFromWindow(view: View) {
                        // Moving the chip between its native containers also dispatches a detach.
                        if (state.moving) return
                        val boundOwner = state.owner.get()
                        state.destroy()
                        if (boundOwner != null && states.get(boundOwner) == state)
                            states.remove(boundOwner)
                    }
                }
            chip.addOnAttachStateChangeListener(state.attachment)
            state.queue()
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun samsungRecording(controller: Any?): Boolean {
        if (Reflect.getBooleanField(controller!!, "isLinkToWindowing")) return false
        val raw =
            Reflect.callMethod(
                Reflect.getObjectField(controller!!, "castController")!!,
                "getCastDevices",
            )
        check(raw is MutableList<*>) { "Cast device list changed" }
        var recording: Boolean = false
        for (device in raw) {
            if (!Reflect.getBooleanField(device!!, "isCasting")) continue
            val tag = Reflect.getObjectField(device!!, "tag")
            if (
                tag == null ||
                    ("android.media.projection.MediaProjectionInfo" != tag.javaClass.getName()) ||
                    ("com.samsung.android.app.smartcapture" !=
                        Reflect.callMethod(tag, "getPackageName"))
            )
                return false
            recording = true
        }
        return recording
    }

    private fun fail(error: Throwable) {
        failed = true
        for (state in ArrayList<RightRecordingIndicator.State?>(states.values)) state!!.restore()
        rt.report("Ready; recordingChip=unavailable: " + error.javaClass.getSimpleName())
        Hooks.log("GalaxyCapture: chip relocation disabled; native controls retained: " + error)
    }

    private inner class State(
        bar: View?,
        view: View,
        start: LinearLayout,
        end: LinearLayout?,
        controller: Any?,
    ) {
        @JvmField val root: WeakReference<View?>
        @JvmField val chip: WeakReference<View?>
        @JvmField val original: WeakReference<LinearLayout?>
        @JvmField val target: WeakReference<LinearLayout?>
        @JvmField val owner: WeakReference<Any?>
        @JvmField val index: Int
        @JvmField val originalParams: android.view.ViewGroup.LayoutParams
        @JvmField val layout: OnLayoutChangeListener
        @JvmField var attachment: OnAttachStateChangeListener? = null
        @JvmField val apply: Runnable = Runnable { this.apply() }
        @JvmField val beforeDraw: OnPreDrawListener = OnPreDrawListener { this.beforeDraw() }
        @JvmField var moved: Boolean = false
        @JvmField var moving: Boolean = false
        @JvmField var fitRejected: Boolean = false
        @JvmField var destroyed: Boolean = false
        @JvmField var pending: Boolean = false
        @JvmField var parentRevision: Int = 0
        @JvmField var diagnostic: String = ""

        init {
            root = WeakReference<View?>(bar)
            chip = WeakReference<View?>(view)
            original = WeakReference<LinearLayout?>(start)
            target = WeakReference<LinearLayout?>(end)
            owner = WeakReference<Any?>(controller)
            index = start.indexOfChild(view)
            originalParams = view.getLayoutParams()
            layout =
                OnLayoutChangeListener {
                    v: View?,
                    l: Int,
                    t: Int,
                    r: Int,
                    b: Int,
                    ol: Int,
                    ot: Int,
                    or: Int,
                    ob: Int ->
                    if (r - l != or - ol || b - t != ob - ot) fitRejected = false
                    queue()
                }
        }

        fun observe() {
            val bar = root.get()
            if (bar == null) return
            bar.addOnLayoutChangeListener(layout)
            bar.getViewTreeObserver().addOnPreDrawListener(beforeDraw)
        }

        fun queue() {
            main.removeCallbacks(apply)
            if (!destroyed) {
                pending = true
                main.post(apply)
            }
        }

        fun nativeUpdated() {
            // updateAll has already installed the native click listener and visibility.
            // Reparent in the same main-thread call, before the first visible frame.
            if (Looper.myLooper() == Looper.getMainLooper()) apply() else queue()
        }

        fun beforeDraw(): Boolean {
            if (destroyed || !pending) return true
            val revision: Int = parentRevision
            main.removeCallbacks(apply)
            apply()
            val bar = root.get()
            // If attachment or a layout event moved the chip during this traversal,
            // wait for its new parent to measure it. Never render the old left layout.
            return parentRevision == revision || bar == null || !bar.isLayoutRequested()
        }

        fun apply() {
            pending = false
            if (moving || destroyed) return
            try {
                val bar = root.get()
                val view = chip.get()
                val start = original.get()
                val end = target.get()
                val controller = owner.get()
                val on: Boolean =
                    !failed &&
                        rt.options!!.enabled &&
                        rt.guard.allowed() &&
                        rt.options!!.recordingIconRight &&
                        !fitRejected
                if (
                    !on ||
                        bar == null ||
                        view == null ||
                        start == null ||
                        end == null ||
                        controller == null ||
                        !bar.isAttachedToWindow() ||
                        bar.getDisplay() == null ||
                        bar.getDisplay().getDisplayId() != 0 ||
                        (bar.getResources().getConfiguration().orientation !=
                            Configuration.ORIENTATION_PORTRAIT) ||
                        view.getRootView() !== end.getRootView() ||
                        !samsungRecording(controller)
                ) {
                    restore()
                    report(if (fitRejected) "left-space-limited" else "left")
                    return
                }
                if (
                    Reflect.getObjectField(controller, "chipView") !== view ||
                        Reflect.getObjectField(view, "customClickListener") == null
                ) {
                    restore()
                    report("waiting-native-click")
                    return
                }
                if (!moved) {
                    check(
                        !(view.getParent() !== start ||
                            originalParams !is android.widget.LinearLayout.LayoutParams)
                    ) {
                        "Chip parent changed"
                    }
                    val next = android.widget.LinearLayout.LayoutParams(originalParams)
                    next.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    next.weight = 0f
                    moving = true
                    start.removeView(view)
                    try {
                        end.addView(view, 0, next)
                        moved = true
                        parentRevision++
                    } catch (error: Throwable) {
                        start.addView(view, min(index, start.getChildCount()), originalParams)
                        throw error
                    } finally {
                        moving = false
                    }
                    // It is the identical View instance; no click listener, clock or dialog is
                    // replaced.
                    queue()
                    report("right; nativeView=true; nativeClick=true")
                } else if (
                    view.getParent() === end &&
                        view.getVisibility() == View.VISIBLE &&
                        view.getWidth() > 0
                ) {
                    val visible = Rect()
                    if (
                        view.isShown() &&
                            (!view.getGlobalVisibleRect(visible) ||
                                visible.width() < view.getWidth() ||
                                visible.height() < view.getHeight())
                    ) {
                        fitRejected = true
                        restore()
                        report("left-space-limited")
                    }
                }
            } catch (error: Throwable) {
                fail(error)
            }
        }

        fun report(value: String) {
            if (value == diagnostic) return
            diagnostic = value
            rt.report("Ready; recordingChip=" + value)
            Hooks.log("GalaxyCapture: recordingChip=" + value)
        }

        fun restore() {
            val view = chip.get()
            val start = original.get()
            val end = target.get()
            if (!moved || moving || view == null || start == null) return
            moving = true
            try {
                if (view.getParent() === end) {
                    end.removeView(view)
                    start.addView(view, min(index, start.getChildCount()), originalParams)
                    parentRevision++
                }
                moved = false
            } catch (error: Throwable) {
                // Preserve the interactive view even if an external layout changes during
                // restoration.
                if (view.getParent() == null && end != null) {
                    try {
                        end.addView(view)
                    } catch (ignored: Throwable) {}
                }
                Hooks.log("GalaxyCapture: native chip restoration failed: " + error)
            } finally {
                moving = false
            }
        }

        fun destroy() {
            destroyed = true
            main.removeCallbacks(apply)
            val bar = root.get()
            if (bar != null) {
                bar.removeOnLayoutChangeListener(layout)
                val observer = bar.getViewTreeObserver()
                if (observer.isAlive()) observer.removeOnPreDrawListener(beforeDraw)
            }
            val view = chip.get()
            if (view != null && attachment != null)
                view.removeOnAttachStateChangeListener(attachment)
            restore()
        }
    }

    companion object {
        @JvmStatic
        fun install(rt: HookRuntime, loader: ClassLoader?) {
            val bridge = RightRecordingIndicator(rt)
            try {
                bridge.install(loader)
            } catch (error: Throwable) {
                bridge.fail(error)
            }
        }

        @JvmStatic
        private fun name(view: View): String? {
            try {
                return view.getResources().getResourceEntryName(view.getId())
            } catch (ignored: NotFoundException) {
                return ""
            }
        }
    }
}
