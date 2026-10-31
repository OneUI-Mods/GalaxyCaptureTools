package dev.ryuya.galaxycapture

import android.app.admin.DevicePolicyManager
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Color
import android.os.Binder
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import java.lang.Boolean as JavaBoolean
import java.util.Arrays
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

/* Preserve FLAG_SECURE on source windows, Recents, casts, and unrelated capture requests. */
object SecureCapture {
    private val screenshot: ThreadLocal<SecureCapture.Shot?> = ThreadLocal<SecureCapture.Shot?>()
    private val projection: ThreadLocal<Any?> = ThreadLocal<Any?>()
    private val preflight: ThreadLocal<Boolean?> = ThreadLocal<Boolean?>()

    @JvmStatic
    private fun enabled(rt: HookRuntime?): Boolean {
        return rt != null && rt.options!!.enabled && rt.guard.allowed()
    }

    @JvmStatic
    private fun owner(rt: HookRuntime, uid: Int, pkg: String?): Boolean {
        val names = rt.context.getPackageManager().getPackagesForUid(uid)
        return names != null && Arrays.asList<String?>(*names).contains(pkg)
    }

    @JvmStatic
    fun installFramework(loader: ClassLoader?) {
        val shots = Reflect.findClass("com.android.server.wm.WmScreenshotController", loader)
        Hooks.hookAllMethods(
            shots,
            "takeScreenshotToTargetWindow",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val rt = FrameworkHooks.runtime
                    if (!SecureCapture.enabled(rt) || !rt!!.options!!.secureScreenshot) return
                    try {
                        if (
                            !SecureCapture.owner(
                                rt!!,
                                Binder.getCallingUid(),
                                "com.android.systemui",
                            )
                        )
                            return
                        rt!!.guard.beginCapture()
                        p.setObjectExtra("previousShot", SecureCapture.screenshot.get())
                        p.setObjectExtra("shotEntered", true)
                        SecureCapture.screenshot.set(SecureCapture.Shot(p.thisObject!!))
                    } catch (e: Throwable) {
                        rt!!.fail(e)
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    if (JavaBoolean.TRUE != p.getObjectExtra("shotEntered")) return
                    val previous: SecureCapture.Shot? =
                        p.getObjectExtra("previousShot") as SecureCapture.Shot?
                    if (previous == null) SecureCapture.screenshot.remove()
                    else SecureCapture.screenshot.set(previous)
                    FrameworkHooks.runtime!!.guard.endCapture()
                    if (p.hasThrowable()) FrameworkHooks.runtime!!.fail(p.getThrowable()!!)
                }
            },
        )
        Hooks.hookAllMethods(
            shots,
            "isScreenshotAllowedByPolicy",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val shot = SecureCapture.screenshot.get()
                    val rt = FrameworkHooks.runtime
                    if (
                        shot == null ||
                            shot.controller !== p.thisObject ||
                            !SecureCapture.enabled(rt) ||
                            p.hasThrowable()
                    )
                        return
                    try {
                        val sensitive =
                            Reflect.getBooleanField(
                                p!!.thisObject!!,
                                "mIsBlockedBySensitiveContents",
                            )
                        val policy =
                            rt!!
                                .context
                                .getSystemService<DevicePolicyManager?>(
                                    DevicePolicyManager::class.java
                                )
                        val disabled: Boolean =
                            policy == null || policy.getScreenCaptureDisabled(null)
                        val reason = Reflect.getIntField(p!!.thisObject!!, "mReasonForFailure")
                        if (SecureCapturePolicy.onlySecureFlag(reason, sensitive, disabled)) {
                            Reflect.setIntField(
                                p!!.thisObject!!,
                                "mReasonForFailure",
                                reason and 16.inv(),
                            )
                            p.setResult(true)
                        }
                        shot.allowed = JavaBoolean.TRUE == p.getResult() && !sensitive && !disabled
                    } catch (e: Throwable) {
                        rt!!.fail(e)
                    }
                }
            },
        )
        Hooks.hookAllMethods(
            shots,
            "screenshot",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val shot = SecureCapture.screenshot.get()
                    if (
                        shot != null &&
                            shot.controller === p.thisObject &&
                            shot.allowed &&
                            SecureCapture.enabled(FrameworkHooks.runtime)
                    )
                        p!!.args!![5] = true
                }
            },
        )

        // Stay inside the already-authorized recorder's createVirtualDisplay Binder request.
        // Neither projection consent/validity checks nor source surfaces are modified.
        val displays =
            Reflect.findClass(
                "com.android.server.display.DisplayManagerService\$BinderService",
                loader,
            )
        Hooks.hookAllMethods(
            displays,
            "createVirtualDisplay",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val rt = FrameworkHooks.runtime
                    if (
                        !SecureCapture.enabled(rt) ||
                            !rt!!.options!!.secureRecording ||
                            p.args!!.size != 4 ||
                            p!!.args!![2] == null
                    )
                        return
                    try {
                        val pkg: String? = p!!.args!![3] as String?
                        val name: String? =
                            Reflect.callMethod(p!!.args!![0]!!, "getName") as String?
                        if (
                            !SecureCapturePolicy.recording(true, true, true, pkg, name) ||
                                !SecureCapture.owner(rt!!, Binder.getCallingUid(), pkg)
                        )
                            return
                        rt!!.guard.beginCapture()
                        p.setObjectExtra("previousProjection", SecureCapture.projection.get())
                        p.setObjectExtra("projectionEntered", true)
                        SecureCapture.projection.set(p!!.args!![2])
                    } catch (e: Throwable) {
                        rt!!.fail(e)
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    if (JavaBoolean.TRUE != p.getObjectExtra("projectionEntered")) return
                    val previous = p.getObjectExtra("previousProjection")
                    if (previous == null) SecureCapture.projection.remove()
                    else SecureCapture.projection.set(previous)
                    FrameworkHooks.runtime!!.guard.endCapture()
                    if (p.hasThrowable()) FrameworkHooks.runtime!!.fail(p.getThrowable()!!)
                }
            },
        )
        val token =
            Reflect.findClass(
                "com.android.server.media.projection.MediaProjectionManagerService\$MediaProjection",
                loader,
            )
        Hooks.hookAllMethods(
            token,
            "applyVirtualDisplayFlags",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (
                        p.thisObject === SecureCapture.projection.get() &&
                            SecureCapture.enabled(FrameworkHooks.runtime) &&
                            !p.hasThrowable()
                    )
                        p.setResult(p.getResult() as Int or 4)
                }
            },
        )
        Hooks.hookAllMethods(
            token,
            "canProjectSecureVideo",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (
                        p.thisObject === SecureCapture.projection.get() &&
                            SecureCapture.enabled(FrameworkHooks.runtime) &&
                            !p.hasThrowable()
                    )
                        p.setResult(true)
                }
            },
        )
        for (method in
            Reflect.findClass("com.android.server.display.DisplayManagerService", loader)!!
                .getDeclaredMethods()) if (
            method.getName().contains("createVirtualDisplayInternal")
        )
            Hooks.deoptimize(method)
        for (method in shots!!.getDeclaredMethods()) if (
            method.getName() == "takeScreenshotToTargetWindow"
        )
            Hooks.deoptimize(method)
    }

    @JvmStatic
    fun installRecorder(rt: HookRuntime, loader: ClassLoader?) {
        val controller =
            Reflect.findClass(
                "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderController",
                loader,
            )
        Hooks.hookAllMethods(
            controller,
            "checkPreConditions",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    p.setObjectExtra("previousPreflight", SecureCapture.preflight.get())
                    SecureCapture.preflight.set(
                        SecureCapture.enabled(rt) && rt.options!!.secureRecording
                    )
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    val previous: Boolean? = p.getObjectExtra("previousPreflight") as Boolean?
                    if (previous == null) SecureCapture.preflight.remove()
                    else SecureCapture.preflight.set(previous)
                }
            },
        )
        // The preflight bitmap is only a recording eligibility probe; never saved as a screenshot.
        // Keep the battery, storage, calls, consent, and native recording-start flow intact.
        Hooks.hookAllMethods(
            Reflect.findClass(
                "com.samsung.android.app.smartcapture.baseutil.view.CaptureUtils",
                loader,
            ),
            "captureScreen",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (
                        (JavaBoolean.TRUE != SecureCapture.preflight.get()) ||
                            !SecureCapture.enabled(rt) ||
                            p.hasThrowable()
                    )
                        return
                    if (p.getResult() == null) {
                        val probe = Bitmap.createBitmap(2, 2, Config.ARGB_8888)
                        probe.eraseColor(Color.WHITE)
                        p.setResult(probe)
                    }
                }
            },
        )
    }

    private class Shot(controller: Any) {
        @JvmField val controller: Any
        @JvmField var allowed: Boolean = false

        init {
            this.controller = controller
        }
    }
}
