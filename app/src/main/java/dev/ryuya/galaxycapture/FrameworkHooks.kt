package dev.ryuya.galaxycapture

import android.graphics.Point
import android.graphics.Rect
import android.view.SurfaceControl
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import java.util.WeakHashMap
import kotlin.concurrent.Volatile

/* Only alters mirrors owned by the two supported screen recorders. */
object FrameworkHooks {
    @kotlin.jvm.JvmField @Volatile var runtime: HookRuntime? = null
    private val sessions: MutableMap<Any?, FrameworkHooks.Session?> =
        WeakHashMap<Any?, FrameworkHooks.Session?>()
    private val display: ThreadLocal<Any?> = ThreadLocal<Any?>()
    private var containerClass: Class<*>? = null

    @kotlin.jvm.JvmStatic
    fun field(`object`: Any?, name: String?): Any? {
        return Reflect.getObjectField(`object`!!, name!!)
    }

    @kotlin.jvm.JvmStatic
    fun call(`object`: Any?, name: String?, vararg args: Any?): Any? {
        return Reflect.callMethod(`object`!!, name!!, *args)
    }

    @kotlin.jvm.JvmStatic
    private fun supported(recorder: Any?): Boolean {
        val info =
            FrameworkHooks.field(FrameworkHooks.field(recorder, "mDisplayContent"), "mDisplayInfo")
        val owner = FrameworkHooks.field(info, "ownerPackageName")
        return "com.android.systemui" == owner || "com.samsung.android.app.smartcapture" == owner
    }

    @kotlin.jvm.JvmStatic
    private fun rotation(recorder: Any?): Int {
        val source = FrameworkHooks.field(recorder, "mRecordedWindowContainer")
        return Reflect.getIntField(
            FrameworkHooks.field(FrameworkHooks.field(source, "mDisplayContent"), "mDisplayInfo")!!,
            "rotation",
        )
    }

    @kotlin.jvm.JvmStatic
    private fun stop(recorder: Any?) {
        try {
            FrameworkHooks.call(recorder, "stopMediaProjection", 4)
        } catch (ignored: Throwable) {}
    }

    @kotlin.jvm.JvmStatic
    private fun fail(recorder: Any?, error: Throwable?) {
        if (FrameworkHooks.runtime != null) FrameworkHooks.runtime!!.fail(error!!)
        FrameworkHooks.stop(recorder)
    }

    @kotlin.jvm.JvmStatic
    fun install(loader: ClassLoader?) {
        FrameworkHooks.containerClass =
            Reflect.findClass("com.android.server.wm.WindowContainer", loader)
        val dc = Reflect.findClass("com.android.server.wm.DisplayContent", loader)
        val cr = Reflect.findClass("com.android.server.wm.ContentRecorder", loader)
        Hooks.hookAllMethods(
            dc,
            "updateRecording",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    p.setObjectExtra("previous", FrameworkHooks.display.get())
                    FrameworkHooks.display.remove()
                    val rt = FrameworkHooks.runtime
                    if (rt == null) return
                    val recorder = FrameworkHooks.field(p.thisObject, "mContentRecorder")
                    if (recorder == null) return
                    val nativeSession = FrameworkHooks.field(recorder, "mContentRecordingSession")
                    if (nativeSession == null || !FrameworkHooks.supported(recorder)) return
                    var old = FrameworkHooks.sessions.get(recorder)
                    if (old != null && old.recordingSession !== nativeSession) {
                        FrameworkHooks.sessions.remove(recorder)
                        old = null
                    }
                    if (old == null && !rt.options!!.enabled) return
                    if (!rt.guard.allowed()) {
                        p.setResult(null)
                        FrameworkHooks.stop(recorder)
                        return
                    }
                    try {
                        val type: Int =
                            FrameworkHooks.call(nativeSession, "getContentToRecord") as Int
                        if (type != 0 && type != 1)
                            return // Do not expand Samsung's partial-area/overlay recordings.

                        val options: Options? = if (old == null) rt.options else old.options

                        if (old == null) {
                            val task: Any? =
                                if (type == 1)
                                    Reflect.callStaticMethod(
                                        FrameworkHooks.containerClass!!,
                                        "fromBinder",
                                        FrameworkHooks.call(nativeSession, "getTokenToRecord"),
                                    )
                                else null
                            val owner =
                                Reflect.getIntField(
                                    FrameworkHooks.field(
                                        FrameworkHooks.field(recorder, "mDisplayContent"),
                                        "mDisplayInfo",
                                    )!!,
                                    "ownerUid",
                                )
                            val choice: CaptureTargets.Selection? =
                                if (task == null) null
                                else
                                    CaptureTargets.take(owner, Reflect.getIntField(task, "mTaskId"))
                            val lock: Boolean =
                                if (choice == null) options!!.singleWindow else choice.strict
                            if (
                                !options!!.rotation &&
                                    !options!!.ignoreHalfTurns &&
                                    (task == null || !lock)
                            )
                                return
                            val activity: Any? =
                                if (lock && task != null)
                                    (if (choice != null && choice.activity != null) choice.activity
                                    else FrameworkHooks.call(task, "getTopMostActivity"))
                                else null
                            val window: Any? =
                                if (activity == null) null
                                else
                                    (if (choice != null && choice.window != null) choice.window
                                    else FrameworkHooks.call(activity, "findMainWindow", false))
                            if (
                                lock &&
                                    type == 1 &&
                                    (window == null ||
                                        !FrameworkHooks.validWindow(
                                            window,
                                            options!!.secureRecording,
                                        ))
                            ) {
                                p.setResult(null)
                                return
                            }
                            // Record initial rotation from the chosen source display, before it can
                            // resize.
                            val source: Any? =
                                if (task == null)
                                    FrameworkHooks.call(
                                        FrameworkHooks.field(
                                            FrameworkHooks.field(
                                                FrameworkHooks.field(recorder, "mDisplayContent"),
                                                "mWmService",
                                            ),
                                            "mRoot",
                                        ),
                                        "getDisplayContent",
                                        FrameworkHooks.call(nativeSession, "getDisplayToRecord")
                                            as Int,
                                    )
                                else task
                            val sourceDisplay: Any? =
                                if (task == null) source
                                else FrameworkHooks.field(source, "mDisplayContent")
                            val initial =
                                Reflect.getIntField(
                                    FrameworkHooks.field(sourceDisplay, "mDisplayInfo")!!,
                                    "rotation",
                                )
                            rt.guard.arm()
                            old =
                                FrameworkHooks.Session(
                                    nativeSession,
                                    activity,
                                    window,
                                    options!!,
                                    initial,
                                )
                            FrameworkHooks.sessions.put(recorder, old)
                        }
                        if (old.activity != null) {
                            val current: Any? =
                                if (old.mainWindow)
                                    FrameworkHooks.call(old.activity, "findMainWindow", false)
                                else old.window
                            if (!(FrameworkHooks.call(old.activity, "isAttached") as Boolean)) {
                                p.setResult(null)
                                FrameworkHooks.stop(recorder)
                                return
                            }
                            if (
                                current != null &&
                                    FrameworkHooks.validWindow(
                                        current,
                                        old.options.secureRecording,
                                    ) &&
                                    current !== old.window
                            ) {
                                old.window = current
                                FrameworkHooks.replaceWindowMirror(recorder, old)
                            }
                            if (
                                !FrameworkHooks.validWindow(old.window, old.options.secureRecording)
                            ) {
                                old.needsWindowLayout = true
                                val surface = FrameworkHooks.field(recorder, "mRecordedSurface")
                                if (surface != null) {
                                    val tx = FrameworkHooks.call(old.activity, "getSyncTransaction")
                                    FrameworkHooks.call(tx, "setAlpha", surface, 0f)
                                    FrameworkHooks.call(old.activity, "scheduleAnimation")
                                }
                                p.setResult(null)
                                return
                            }
                        }
                        FrameworkHooks.display.set(p.thisObject)
                        p.setObjectExtra("recorder", recorder)
                    } catch (error: Throwable) {
                        p.setResult(null)
                        FrameworkHooks.fail(recorder, error)
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    var recorder = p.getObjectExtra("recorder")
                    try {
                        if (recorder != null && p.hasThrowable()) {
                            val error = p.getThrowable()
                            p.setResult(null)
                            FrameworkHooks.fail(recorder, error)
                        }
                        val currentRecorder = FrameworkHooks.field(p.thisObject, "mContentRecorder")
                        val current = FrameworkHooks.sessions.get(currentRecorder)
                        if (
                            current != null &&
                                current.needsWindowLayout &&
                                FrameworkHooks.runtime!!.guard.allowed()
                        ) {
                            recorder = currentRecorder
                            FrameworkHooks.resumeWindowLayout(currentRecorder, current)
                        }
                    } catch (error: Throwable) {
                        if (recorder != null) FrameworkHooks.fail(recorder, error)
                    } finally {
                        val previous = p.getObjectExtra("previous")
                        if (previous == null) FrameworkHooks.display.remove()
                        else FrameworkHooks.display.set(previous)
                    }
                }
            },
        )
        Hooks.hookAllMethods(
            SurfaceControl::class.java,
            "mirrorSurface",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val dc = FrameworkHooks.display.get()
                    if (dc == null || p.args!!.size == 0) return
                    val recorder = FrameworkHooks.field(dc, "mContentRecorder")
                    val s = FrameworkHooks.sessions.get(recorder)
                    if (s == null || s.activity == null) return
                    try {
                        val source = FrameworkHooks.field(recorder, "mRecordedWindowContainer")
                        if (
                            source == null ||
                                p!!.args!![0] !== FrameworkHooks.call(source, "getSurfaceControl")
                        )
                            return
                        check(FrameworkHooks.validWindow(s.window, s.options.secureRecording)) {
                            "Selected window unavailable"
                        }
                        p!!.args!![0] = FrameworkHooks.call(s.window, "getSurfaceControl")
                    } catch (error: Throwable) {
                        p.setThrowable(
                            IllegalStateException("Selected window cannot be mirrored", error)
                        )
                    }
                }
            },
        )
        Hooks.hookAllMethods(
            cr,
            "updateMirroredSurface",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val s = FrameworkHooks.sessions.get(p.thisObject)
                    if (s == null) return
                    try {
                        val bounds: Rect? = p!!.args!![1] as Rect?
                        val output: Point? = p!!.args!![2] as Point?
                        if (
                            bounds == null ||
                                bounds.isEmpty() ||
                                output == null ||
                                output.x <= 0 ||
                                output.y <= 0 ||
                                (s.activity != null &&
                                    (!FrameworkHooks.validWindow(
                                        s.window,
                                        s.options.secureRecording,
                                    ) || FrameworkHooks.windowFrame(s)!!.isEmpty()))
                        ) {
                            s.needsWindowLayout = s.activity != null
                            FrameworkHooks.hideWindow(p.thisObject, s, p!!.args!![0])
                            p.setResult(null)
                        }
                    } catch (error: Throwable) {
                        p.setResult(null)
                        FrameworkHooks.fail(p.thisObject, error)
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    var s = FrameworkHooks.sessions.get(p.thisObject)
                    if (
                        s == null &&
                            FrameworkHooks.runtime != null &&
                            FrameworkHooks.runtime!!.options!!.enabled &&
                            (FrameworkHooks.runtime!!.options!!.rotation ||
                                FrameworkHooks.runtime!!.options!!.ignoreHalfTurns) &&
                            FrameworkHooks.supported(p.thisObject)
                    ) {
                        try {
                            val nativeSession =
                                FrameworkHooks.field(p.thisObject, "mContentRecordingSession")
                            if (
                                nativeSession == null ||
                                    FrameworkHooks.call(nativeSession, "getContentToRecord")
                                        as Int != 0
                            )
                                return
                            FrameworkHooks.runtime!!.guard.arm()
                            s =
                                FrameworkHooks.Session(
                                    nativeSession,
                                    null,
                                    null,
                                    FrameworkHooks.runtime!!.options!!,
                                    FrameworkHooks.rotation(p.thisObject),
                                )
                            FrameworkHooks.sessions.put(p.thisObject, s)
                        } catch (error: Throwable) {
                            FrameworkHooks.fail(p.thisObject, error)
                            return
                        }
                    }
                    if (
                        s == null ||
                            FrameworkHooks.runtime == null ||
                            !FrameworkHooks.runtime!!.guard.allowed()
                    )
                        return
                    try {
                        if (p.hasThrowable()) throw p.getThrowable()!!
                        val surface = FrameworkHooks.field(p.thisObject, "mRecordedSurface")
                        if (surface == null) return
                        var source: Rect? = p!!.args!![1] as Rect?
                        if (s.activity != null) {
                            if (!FrameworkHooks.validWindow(s.window, s.options.secureRecording)) {
                                s.needsWindowLayout = true
                                FrameworkHooks.hideWindow(p.thisObject, s, p!!.args!![0])
                                return
                            }
                            source = FrameworkHooks.windowFrame(s)
                        }
                        val output: Point? = p!!.args!![2] as Point?
                        if (
                            source == null ||
                                source.isEmpty() ||
                                output == null ||
                                output.x <= 0 ||
                                output.y <= 0
                        ) {
                            s.needsWindowLayout = s.activity != null
                            FrameworkHooks.hideWindow(p.thisObject, s, p!!.args!![0])
                            return
                        }
                        val q = s.rotationPolicy.update(FrameworkHooks.rotation(p.thisObject))
                        val t =
                            RecordingTransform.fit(
                                source.width(),
                                source.height(),
                                output.x,
                                output.y,
                                q,
                            )
                        FrameworkHooks.call(
                            p!!.args!![0],
                            "setWindowCrop",
                            surface,
                            source.width(),
                            source.height(),
                        )
                        FrameworkHooks.call(
                            p!!.args!![0],
                            "setMatrix",
                            surface,
                            t!!.a,
                            t!!.b,
                            t!!.c,
                            t!!.d,
                        )
                        FrameworkHooks.call(p!!.args!![0], "setPosition", surface, t!!.x, t!!.y)
                        if (s.activity != null)
                            FrameworkHooks.call(p!!.args!![0], "setAlpha", surface, 1f)
                        s.needsWindowLayout = false
                        FrameworkHooks.runtime!!.report(
                            ("Recording; rotation=" +
                                (q * 90) +
                                "; window=" +
                                (s.activity != null) +
                                "; output=" +
                                output.x +
                                "x" +
                                output.y)
                        )
                    } catch (error: Throwable) {
                        FrameworkHooks.fail(p.thisObject, error)
                    }
                }
            },
        )
        Hooks.hookAllMethods(
            cr,
            "clearContentRecordingSession",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    FrameworkHooks.sessions.remove(p.thisObject)
                    if (FrameworkHooks.sessions.isEmpty() && FrameworkHooks.runtime != null) {
                        FrameworkHooks.runtime!!.guard.finish()
                        FrameworkHooks.runtime!!.report(
                            if (FrameworkHooks.runtime!!.guard.allowed()) "Ready; recording ended"
                            else "Stopped by crash protection"
                        )
                    }
                }
            },
        )
    }

    @kotlin.jvm.JvmStatic
    private fun windowFrame(session: FrameworkHooks.Session): Rect? {
        return FrameworkHooks.field(FrameworkHooks.field(session.window, "mWindowFrames"), "mFrame")
            as Rect?
    }

    @kotlin.jvm.JvmStatic
    private fun hideWindow(recorder: Any?, session: FrameworkHooks.Session, tx: Any?) {
        if (session.activity == null) return
        val surface = FrameworkHooks.field(recorder, "mRecordedSurface")
        if (surface != null) FrameworkHooks.call(tx, "setAlpha", surface, 0f)
    }

    @kotlin.jvm.JvmStatic
    private fun resumeWindowLayout(recorder: Any?, session: FrameworkHooks.Session) {
        if (
            session.activity == null ||
                !(FrameworkHooks.call(session.activity, "isAttached") as Boolean)
        )
            return
        val current: Any? =
            if (session.mainWindow) FrameworkHooks.call(session.activity, "findMainWindow", false)
            else session.window
        if (!FrameworkHooks.validWindow(current, session.options.secureRecording)) return
        val frame: Rect? =
            FrameworkHooks.field(FrameworkHooks.field(current, "mWindowFrames"), "mFrame") as Rect?
        val size: Point? = FrameworkHooks.call(recorder, "fetchSurfaceSizeIfPresent") as Point?
        if (frame!!.isEmpty() || size == null || size.x <= 0 || size.y <= 0) return
        if (current !== session.window) {
            session.window = current
            FrameworkHooks.replaceWindowMirror(recorder, session)
        } else if (FrameworkHooks.field(recorder, "mRecordedSurface") != null) {
            val tx = FrameworkHooks.call(session.activity, "getSyncTransaction")
            FrameworkHooks.call(
                recorder,
                "updateMirroredSurface",
                tx,
                FrameworkHooks.call(session.activity, "getBounds"),
                size,
            )
            FrameworkHooks.call(session.activity, "scheduleAnimation")
        }
    }

    @kotlin.jvm.JvmStatic
    private fun replaceWindowMirror(recorder: Any?, session: FrameworkHooks.Session) {
        val previous = FrameworkHooks.field(recorder, "mRecordedSurface")
        if (previous == null) return
        val next: SurfaceControl? =
            Reflect.callStaticMethod(
                SurfaceControl::class.java,
                "mirrorSurface",
                FrameworkHooks.call(session.window, "getSurfaceControl"),
            ) as SurfaceControl?
        val dc = FrameworkHooks.field(recorder, "mDisplayContent")
        val tx = FrameworkHooks.call(session.activity, "getSyncTransaction")
        FrameworkHooks.call(tx, "reparent", next, FrameworkHooks.call(dc, "getSurfaceControl"))
        FrameworkHooks.call(tx, "remove", previous)
        Reflect.setObjectField(recorder!!, "mRecordedSurface", next)
        val size = FrameworkHooks.call(recorder, "fetchSurfaceSizeIfPresent")
        FrameworkHooks.call(
            recorder,
            "updateMirroredSurface",
            tx,
            FrameworkHooks.call(session.activity, "getBounds"),
            size,
        )
        FrameworkHooks.call(session.activity, "scheduleAnimation")
    }

    @kotlin.jvm.JvmStatic
    private fun validWindow(window: Any?, captureSecure: Boolean): Boolean {
        if (window == null || !(FrameworkHooks.call(window, "isAttached") as Boolean)) return false
        val attrs = FrameworkHooks.field(window, "mAttrs")
        if (!captureSecure && (Reflect.getIntField(attrs!!, "flags") and 8192) != 0) return false
        val surface: SurfaceControl? =
            FrameworkHooks.call(window, "getSurfaceControl") as SurfaceControl?
        return surface != null && surface.isValid()
    }

    private class Session(
        recordingSession: Any,
        activity: Any?,
        window: Any?,
        options: Options,
        rotation: Int,
    ) {
        @kotlin.jvm.JvmField val recordingSession: Any
        @kotlin.jvm.JvmField val activity: Any?
        @kotlin.jvm.JvmField val options: Options
        @kotlin.jvm.JvmField val rotation: Int
        @kotlin.jvm.JvmField val rotationPolicy: RotationPolicy
        @kotlin.jvm.JvmField var window: Any?
        @kotlin.jvm.JvmField val mainWindow: Boolean
        @kotlin.jvm.JvmField var needsWindowLayout: Boolean = false

        init {
            this.recordingSession = recordingSession
            this.activity = activity
            this.window = window
            this.mainWindow =
                activity != null &&
                    window === FrameworkHooks.call(activity, "findMainWindow", false)
            this.options = options
            this.rotation = rotation
            this.rotationPolicy =
                RotationPolicy(rotation, options.rotation, options.ignoreHalfTurns)
        }
    }
}
