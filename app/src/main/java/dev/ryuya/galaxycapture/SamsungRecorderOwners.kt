package dev.ryuya.galaxycapture

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.Hooks
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap

/** The service outlives the recording dialog and owns the native recording pipeline. */
internal class SamsungRecorderOwners private constructor() {
    private val running = Collections.newSetFromMap(IdentityHashMap<Service, Boolean>())

    fun idle() = running.isEmpty()

    private fun install(types: Set<Class<out Service>>) {
        val installed = mutableListOf<HookCallback.Unhook>()
        fun observe(methods: List<Method>, created: Boolean) {
            for (method in methods.distinct()) {
                installed += Hooks.hook(method, object : HookCallback() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!created) return
                        val service = param.thisObject as? Service ?: return
                        if (types.any { it.isInstance(service) }) running.add(service)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (created && !param.hasThrowable()) return
                        val service = param.thisObject as? Service ?: return
                        // A failed onDestroy does not establish release of the native recording.
                        if (!created && param.hasThrowable()) return
                        running.remove(service)
                    }
                })
            }
        }
        try {
            observe(types.map { it.getMethod("onCreate") }, created = true)
            observe(types.map { it.getMethod("onDestroy") }, created = false)
        } catch (error: Throwable) {
            installed.forEach { it.unhook() }
            throw error
        }
    }

    companion object {
        const val PACKAGE = "com.samsung.android.app.smartcapture"
        const val PROCESS = "$PACKAGE:screenrecorder"
        private val serviceNames = listOf(
            "$PACKAGE.screenrecorder.ScreenRecorderService",
            "com.samsung.android.app.screenrecorder.ScreenRecorderService",
        )

        fun installedServices(context: Context) = serviceNames.mapNotNull { className ->
            try {
                context.packageManager.getServiceInfo(
                    ComponentName(PACKAGE, className), PackageManager.ComponentInfoFlags.of(0),
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }

        fun start(context: Context, loader: ClassLoader): SamsungRecorderOwners? {
            val services = installedServices(context)
            if (services.isEmpty() || services.any { it.processName != PROCESS }) return null
            val types = services.map {
                Class.forName(it.name, false, loader).asSubclass(Service::class.java)
            }.toSet()
            return SamsungRecorderOwners().also { it.install(types) }
        }
    }
}
