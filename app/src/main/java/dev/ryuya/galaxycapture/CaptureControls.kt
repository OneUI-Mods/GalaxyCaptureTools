package dev.ryuya.galaxycapture

import android.view.View
import android.view.WindowManager.LayoutParams
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import java.lang.Boolean as JavaBoolean
import java.util.Collections
import java.util.WeakHashMap
import kotlin.jvm.JvmStatic

/* Only the native recorder toolbar and post-screenshot toolbar windows are affected. */
object CaptureControls {
    private const val PREFIX: String = "com.samsung.android.app.smartcapture."
    private const val POLICY: String = "galaxycapture.controlsPolicy"
    private val creating: ThreadLocal<CaptureUiPolicy?> = ThreadLocal<CaptureUiPolicy?>()
    private val windows: MutableMap<View?, CaptureUiPolicy?>? =
        Collections.synchronizedMap<View?, CaptureUiPolicy?>(WeakHashMap<View?, CaptureUiPolicy?>())

    @JvmStatic
    fun install(rt: HookRuntime, loader: ClassLoader?) {
        val floating =
            Reflect.findClass(
                CaptureControls.PREFIX + "screenrecorder.view.FloatingButtonView",
                loader,
            )
        // A redraw during rotation retains the first policy for this recorder instance.
        Hooks.hookAllMethods(floating, "initView", CaptureControls.scoped(rt, true)!!)
        Hooks.hookAllMethods(floating, "showPointerGuidePopup", CaptureControls.scoped(rt, true)!!)
        val fragment =
            Reflect.findClass(
                CaptureControls.PREFIX + "screenshot.fragment.ServiceFragment",
                loader,
            )
        Hooks.hookAllMethods(
            fragment,
            "registerUi",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (
                        p.thisObject!!.javaClass.getName() !=
                            CaptureControls.PREFIX + "screenshot.ServiceFragmentForStandAlone"
                    )
                        return
                    CaptureControls.enter(
                        p,
                        CaptureUiPolicy.from(rt.options!!, false, rt.guard.allowed()),
                    )
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    CaptureControls.leave(p)
                }
            },
        )
        val global = Reflect.findClass("android.view.WindowManagerGlobal", null)
        Hooks.hookAllMethods(
            global,
            "addView",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val policy = CaptureControls.creating.get()
                    if (
                        policy == null ||
                            p.args!!.size < 2 ||
                            (p!!.args!![0] !is View) ||
                            (p!!.args!![1] !is LayoutParams)
                    )
                        return
                    val root = p.args[0] as View
                    val params = p.args[1] as LayoutParams
                    try {
                        CaptureControls.apply(
                            params,
                            policy,
                        ) // Before the first relayout / surface exists, not after display.
                        CaptureControls.windows!!.put(root, policy)
                    } catch (error: Throwable) {
                        rt.fail(error)
                    }
                }
            },
        )
        Hooks.hookAllMethods(
            global,
            "updateViewLayout",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (
                        p.args!!.size < 2 ||
                            (p!!.args!![0] !is View) ||
                            (p!!.args!![1] !is LayoutParams)
                    )
                        return
                    val root = p.args[0] as View
                    val params = p.args[1] as LayoutParams
                    val policy = CaptureControls.windows!!.get(root)
                    if (policy == null) return
                    try {
                        CaptureControls.apply(params, policy)
                    } catch (error: Throwable) {
                        rt.fail(error)
                    }
                }
            },
        )
    }

    @JvmStatic
    private fun scoped(rt: HookRuntime, recording: Boolean): HookCallback? {
        return object : HookCallback() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                try {
                    var policy: CaptureUiPolicy? =
                        Reflect.getAdditionalInstanceField(p.thisObject, CaptureControls.POLICY)
                            as CaptureUiPolicy?
                    if (policy == null) {
                        policy = CaptureUiPolicy.from(rt.options!!, recording, rt.guard.allowed())
                        Reflect.setAdditionalInstanceField(
                            p.thisObject,
                            CaptureControls.POLICY,
                            policy,
                        )
                    }
                    CaptureControls.enter(p, policy)
                } catch (error: Throwable) {
                    rt.fail(error)
                }
            }

            override fun afterHookedMethod(p: MethodHookParam) {
                CaptureControls.leave(p)
            }
        }
    }

    @JvmStatic
    private fun enter(p: MethodHookParam, policy: CaptureUiPolicy?) {
        p.setObjectExtra("controlsPrevious", CaptureControls.creating.get())
        p.setObjectExtra("controlsEntered", true)
        CaptureControls.creating.set(policy)
    }

    @JvmStatic
    private fun leave(p: MethodHookParam) {
        if (JavaBoolean.TRUE != p.getObjectExtra("controlsEntered")) return
        val previous: CaptureUiPolicy? = p.getObjectExtra("controlsPrevious") as CaptureUiPolicy?
        if (previous == null) CaptureControls.creating.remove()
        else CaptureControls.creating.set(previous)
    }

    @JvmStatic
    private fun apply(params: LayoutParams, policy: CaptureUiPolicy) {
        if (policy.exclude) {
            val flags = Reflect.getIntField(params, "privateFlags")
            Reflect.setIntField(params, "privateFlags", policy.privateFlags(flags))
        }
        // Keep the native View and callbacks alive. Window alpha cannot be reset by View
        // animations.
        // NOT_TOUCHABLE prevents an invisible full-screen input window; notification stop is
        // untouched.
        params.flags = policy.flags(params.flags)
        params.alpha = policy.alpha(params.alpha)
    }
}
