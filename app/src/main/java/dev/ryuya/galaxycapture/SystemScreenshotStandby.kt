package dev.ryuya.galaxycapture

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import java.util.concurrent.Executor

/** Warms the dedicated process once and leaves the screenshot service's normal lifecycle intact. */
internal class SystemScreenshotStandby private constructor(private val runtime: HookRuntime) {
    private val context = runtime.context
    private val activity = context.getSystemService(ActivityManager::class.java)
    private val callbacks = Executor { command -> runtime.worker.post(command) }
    private val importanceToken = Binder()
    private var importantPid: Int? = null
    private var warming: ServiceConnection? = null
    private val warmTimeout = Runnable { warming?.let(::finishWarmup) }

    private val activityService by lazy {
        val manager = Reflect.findClass("android.app.IActivityManager", null)
        val method = manager.getMethod(
            "setProcessImportant", IBinder::class.java, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, String::class.java,
        )
        val service = checkNotNull(Reflect.callStaticMethod(ActivityManager::class.java, "getService"))
        method to service
    }

    private fun refresh() {
        val options = runtime.options
        if (options?.enabled != true || !options.systemScreenshotStandby) {
            warming?.let(::finishWarmup)
            releaseImportance()
            return
        }
        if (warming != null) return
        if (context.checkSelfPermission(SET_PROCESS_LIMIT) != PackageManager.PERMISSION_GRANTED) return
        val service = try {
            context.packageManager.getServiceInfo(SCREENSHOT_SERVICE, PackageManager.ComponentInfoFlags.of(0))
        } catch (_: PackageManager.NameNotFoundException) {
            return
        }
        if (!service.enabled || service.processName != SCREENSHOT_PROCESS ||
            service.applicationInfo.uid != Process.myUid()) return
        // Resolve the optional platform interface before starting a process.
        try {
            activityService
            val existing = screenshotPid()
            if (importantPid != null && importantPid == existing) return
            importantPid = null
            if (existing != null) {
                setImportance(existing, true)
                importantPid = existing
            } else {
                warmProcess()
            }
        } catch (error: Exception) {
            reportUnavailable(error)
        }
    }

    private fun warmProcess() {
        val attempt = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (warming !== this) return
                try {
                    screenshotPid()?.let { pid ->
                        setImportance(pid, true)
                        importantPid = pid
                    }
                } catch (error: Exception) {
                    reportUnavailable(error)
                } finally {
                    // Send no capture message and let Android perform the normal service unbind.
                    finishWarmup(this)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
            override fun onBindingDied(name: ComponentName) {
                if (warming === this) finishWarmup(this)
            }
            override fun onNullBinding(name: ComponentName) {
                if (warming === this) finishWarmup(this)
            }
        }
        warming = attempt
        try {
            val flags = Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND or Context.BIND_NOT_PERCEPTIBLE
            if (context.bindService(Intent().setComponent(SCREENSHOT_SERVICE), flags, callbacks, attempt)) {
                runtime.worker.postDelayed(warmTimeout, 5_000L)
            } else {
                finishWarmup(attempt)
            }
        } catch (error: RuntimeException) {
            finishWarmup(attempt)
            throw error
        }
    }

    private fun screenshotPid(): Int? = activity?.runningAppProcesses?.firstOrNull {
        it.uid == Process.myUid() && it.processName == SCREENSHOT_PROCESS && it.pid != Process.myPid()
    }?.pid

    private fun setImportance(pid: Int, keep: Boolean) {
        val (method, service) = activityService
        method.invoke(service, importanceToken, pid, keep, "Galaxy Capture Tools screenshot standby")
    }

    private fun releaseImportance() {
        val pid = importantPid ?: return
        try {
            if (pid == screenshotPid()) setImportance(pid, false)
            importantPid = null
        } catch (error: Exception) {
            reportUnavailable(error)
        }
    }

    private fun finishWarmup(connection: ServiceConnection) {
        warming = null
        runtime.worker.removeCallbacks(warmTimeout)
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // A failed bind may have no registered connection to release.
        }
    }

    private fun reportUnavailable(error: Exception) {
        Hooks.log("GalaxyCapture SystemUI screenshot standby unavailable: ${error.javaClass.simpleName}")
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val SCREENSHOT_PROCESS = "$SYSTEM_UI:screenshot"
        private const val SET_PROCESS_LIMIT = "android.permission.SET_PROCESS_LIMIT"
        private val SCREENSHOT_SERVICE = ComponentName(SYSTEM_UI, "$SYSTEM_UI.screenshot.TakeScreenshotService")

        fun install(runtime: HookRuntime) {
            if (Application.getProcessName() != SYSTEM_UI) return
            val standby = SystemScreenshotStandby(runtime)
            val refresh = Runnable(standby::refresh)
            runtime.onOptionsChanged(refresh)
            runtime.worker.post(refresh)
        }
    }
}
