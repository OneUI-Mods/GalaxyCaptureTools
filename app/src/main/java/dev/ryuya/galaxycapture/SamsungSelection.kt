package dev.ryuya.galaxycapture

import android.app.Activity
import android.app.Service
import android.content.Context
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import java.io.IOException
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.jvm.Synchronized

object SamsungSelection {
    @Volatile private var target: SamsungSelection.Target? = null
    private var earlyProjection: MediaProjection? = null
    private var earlyOwner: Any? = null
    private var projectionWrapper: Class<*>? = null
    private var picking: Boolean = false
    private var approved: Boolean = false

    @JvmStatic
    fun target(): SamsungSelection.Target? {
        return SamsungSelection.target
    }

    @JvmStatic
    fun installSystemUi(rt: HookRuntime, loader: ClassLoader?) {
        val recorder =
            Reflect.findClass("com.android.systemui.screenrecord.ScreenMediaRecorder", loader)
        Hooks.hookAllMethods(
            recorder,
            "start",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (!rt.options!!.enabled || !rt.options!!.singleWindow) return
                    try {
                        TargetBridge.requireReady(rt)
                    } catch (error: Throwable) {
                        p.setThrowable(IOException("Window recording unavailable", error))
                    }
                }
            },
        )
        RecordingIndicator.install(rt, loader)
        AospRecordingUi.install(rt, loader)
        AudioScope.installAosp(rt, loader)
    }

    @JvmStatic
    fun installRecorder(rt: HookRuntime, loader: ClassLoader?) {
        CaptureControls.install(rt, loader)
        RecordingVolume.install(rt, loader)
        SecureCapture.installRecorder(rt, loader)
        SamsungRecordingUi.install(rt, loader)
        val controller =
            Reflect.findClass(
                "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderController",
                loader,
            )
        Hooks.hookAllMethods(
            controller,
            "startCountDownTimer",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (SamsungSelection.approved) {
                        SamsungSelection.approved = false
                        return
                    }
                    if (!rt.options!!.enabled || !rt.options!!.samsungApp) return
                    val state = SamsungRecordingUi.state(p.thisObject)
                    if (state == null || state.mode < 2) {
                        SamsungSelection.target = null
                        return
                    }
                    p.setResult(null)
                    if (SamsungSelection.picking) return
                    if (state.selected == null) {
                        SamsungSelection.cancel(p.thisObject)
                        return
                    }
                    SamsungSelection.picking = true
                    SamsungSelection.target = null
                    val owner = p.thisObject
                    val request = Bundle()
                    request.putString("id", state.selected)
                    request.putBoolean("windows", state.mode == 3)
                    val result: ResultReceiver =
                        object : ResultReceiver(Handler(Looper.getMainLooper())) {
                            @JvmField var done: Boolean = false

                            override fun onReceiveResult(code: Int, result: Bundle?) {
                                if (done) return
                                done = true
                                SamsungSelection.picking = false
                                if (
                                    code != Activity.RESULT_OK ||
                                        result == null ||
                                        !rt.options!!.enabled ||
                                        !rt.guard.allowed()
                                ) {
                                    SamsungSelection.cancel(owner)
                                    rt.report("Selected target unavailable; recording canceled")
                                    return
                                }
                                try {
                                    val binder = result.getBinder("cookie")
                                    val task = result.getInt("task_id", -1)
                                    val uid = result.getInt("uid", -1)
                                    check(!(binder == null || uid < 0)) { "Invalid capture target" }
                                    val cookie =
                                        Reflect.newInstance(
                                            Reflect.findClass(
                                                "android.app.ActivityOptions\$LaunchCookie",
                                                null,
                                            ),
                                            binder,
                                        )
                                    SamsungSelection.target =
                                        SamsungSelection.Target(cookie, task, uid, state.audioOnly)
                                    rt.guard.arm()
                                    SamsungSelection.approved = true
                                    Reflect.callMethod(owner!!, "startCountDownTimer")
                                    rt.report(
                                        "Selected target; countdown started; app audio=" +
                                            state.audioOnly
                                    )
                                } catch (error: Throwable) {
                                    SamsungSelection.approved = false
                                    SamsungSelection.cancel(owner)
                                    rt.fail(error)
                                }
                            }
                        }
                    try {
                        TargetBridge.request(rt, "activate", request, result)
                        Handler(Looper.getMainLooper())
                            .postDelayed(
                                Runnable { result.send(Activity.RESULT_CANCELED, Bundle.EMPTY) },
                                5000,
                            )
                    } catch (error: Throwable) {
                        SamsungSelection.picking = false
                        SamsungSelection.cancel(owner)
                        rt.fail(error)
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    if (
                        p.hasThrowable() ||
                            !rt.options!!.enabled ||
                            rt.options!!.countdown ||
                            !rt.guard.allowed()
                    )
                        return
                    try {
                        // Selection/consent must have reached the native countdown. A canceled or
                        // pending
                        // asynchronous selection has no timer and cannot start recording here.
                        val timer: CountDownTimer? =
                            Reflect.getObjectField(p.thisObject!!, "mCountDownTimer")
                                as CountDownTimer?
                        if (timer == null) return
                        timer.cancel()
                        // Its own completion removes both progress timers and the countdown window,
                        // creates the native controls, and starts the recorder. No delayed tick
                        // survives.
                        timer.onFinish()
                    } catch (error: Throwable) {
                        SamsungSelection.cancel(p.thisObject)
                        rt.fail(error)
                    }
                }
            },
        )
        SamsungSelection.projectionWrapper =
            Reflect.findClass(
                "com.samsung.android.app.smartcapture.baseutil.sep.SepWrapper\$MediaProjectionManager",
                loader,
            )
        Hooks.hookAllMethods(
            SamsungSelection.projectionWrapper!!,
            "semGetMediaProjection",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (SamsungSelection.earlyProjection != null)
                        p.setResult(SamsungSelection.earlyProjection)
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    val t = SamsungSelection.target
                    if (t == null || p.hasThrowable() || p.getResult() == null) return
                    try {
                        val impl = Reflect.callMethod(p.getResult()!!, "getProjection")
                        Reflect.callMethod(impl!!, "setLaunchCookie", t.cookie)
                        Reflect.callMethod(impl!!, "setTaskId", t.taskId)
                    } catch (error: Throwable) {
                        try {
                            Reflect.callMethod(p.getResult()!!, "stop")
                        } catch (ignored: Throwable) {}
                        p.setThrowable(IllegalStateException("Capture target rejected", error))
                        rt.fail(error)
                    }
                }
            },
        )
        val recorder =
            Reflect.findClass(
                "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.MP4Recorder",
                loader,
            )
        Hooks.hookAllMethods(
            recorder,
            "stopVirtualDisplay",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    SamsungSelection.target = null
                    SamsungSelection.approved = false
                    SamsungSelection.earlyProjection = null
                    SamsungSelection.earlyOwner = null
                    rt.guard.finish()
                    if (rt.guard.allowed()) rt.report("Ready; recording ended")
                }
            },
        )
        AudioScope.installSamsung(rt, loader)
    }

    @JvmStatic
    @Synchronized
    fun audioProjection(owner: Any?): MediaProjection? {
        if (owner === SamsungSelection.earlyOwner && SamsungSelection.earlyProjection != null)
            return SamsungSelection.earlyProjection
        var projection: MediaProjection? =
            Reflect.getObjectField(owner!!, "mMediaProjection") as MediaProjection?
        if (projection == null) {
            val c: Context? = Reflect.getObjectField(owner!!, "mContext") as Context?
            projection =
                Reflect.callStaticMethod(
                    SamsungSelection.projectionWrapper!!,
                    "semGetMediaProjection",
                    c!!.getSystemService<MediaProjectionManager?>(
                        MediaProjectionManager::class.java
                    ),
                    0,
                ) as MediaProjection?
            checkNotNull(projection) { "Audio projection unavailable" }
            Reflect.setObjectField(owner!!, "mMediaProjection", projection)
        }
        SamsungSelection.earlyOwner = owner
        SamsungSelection.earlyProjection = projection
        return projection
    }

    @JvmStatic
    private fun cancel(owner: Any?) {
        SamsungSelection.target = null
        SamsungSelection.approved = false
        try {
            (Reflect.getObjectField(owner!!, "mScreenRecorderService") as Service).stopSelf()
        } catch (ignored: Throwable) {}
    }

    @JvmRecord
    data class Target(val cookie: Any?, val taskId: Int, val uid: Int, val audioOnly: Boolean) {
        override fun toString(): String =
            "Target[cookie=$cookie, taskId=$taskId, uid=$uid, audioOnly=$audioOnly]"
    }
}
