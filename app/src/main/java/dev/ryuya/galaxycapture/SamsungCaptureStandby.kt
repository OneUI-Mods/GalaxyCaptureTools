package dev.ryuya.galaxycapture

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.ryuya.galaxycapture.hook.Hooks
import java.util.concurrent.Executor

/** Holds Samsung's existing screenshot service without invoking its capture binder. */
internal class SamsungCaptureStandby private constructor(private val runtime: HookRuntime) {
    private val context = runtime.context
    private val callbacks = Executor { command -> runtime.worker.post(command) }
    private var connection: ServiceConnection? = null

    private fun refresh() {
        val options = runtime.options
        if (options?.enabled != true || !options.samsungCaptureStandby) {
            connection?.let(::release)
            return
        }
        if (connection != null) return
        val candidate = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit

            // Android reconnects an existing binding after ordinary process death.
            override fun onServiceDisconnected(name: ComponentName) = Unit

            override fun onBindingDied(name: ComponentName) {
                if (connection !== this) return
                release(this)
                refresh()
            }

            override fun onNullBinding(name: ComponentName) {
                if (connection === this) release(this)
            }
        }
        val service = try {
            context.packageManager.getServiceInfo(SCREENSHOT_SERVICE, PackageManager.ComponentInfoFlags.of(0))
        } catch (_: PackageManager.NameNotFoundException) {
            return
        }
        if (!service.enabled || !service.exported || service.processName != SAMSUNG_CAPTURE ||
            service.permission != SCREENSHOT_PERMISSION ||
            context.checkSelfPermission(SCREENSHOT_PERMISSION) != PackageManager.PERMISSION_GRANTED) return

        try {
            connection = candidate
            val flags = Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND or Context.BIND_NOT_PERCEPTIBLE
            if (!context.bindService(Intent().setComponent(SCREENSHOT_SERVICE), flags, callbacks, candidate)) {
                release(candidate)
            }
        } catch (error: RuntimeException) {
            release(candidate)
            Hooks.log("GalaxyCapture Samsung screenshot standby unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun release(current: ServiceConnection) {
        connection = null
        try {
            context.unbindService(current)
        } catch (_: IllegalArgumentException) {
            // A failed bind may have no registered connection to release.
        }
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val SAMSUNG_CAPTURE = "com.samsung.android.app.smartcapture"
        private const val SCREENSHOT_PERMISSION = "com.samsung.android.app.scrollcapture.permission.ACCESS_REMOTE_SVC"
        private val SCREENSHOT_SERVICE = ComponentName(
            SAMSUNG_CAPTURE,
            "$SAMSUNG_CAPTURE.screenshot.core.ScreenshotRemoteService",
        )

        fun install(runtime: HookRuntime) {
            if (Application.getProcessName() != SYSTEM_UI) return
            val standby = SamsungCaptureStandby(runtime)
            runtime.ownStandby { standby.connection?.let(standby::release) }
            val refresh = Runnable(standby::refresh)
            runtime.onOptionsChanged(refresh)
            runtime.worker.post(refresh)
        }
    }
}
