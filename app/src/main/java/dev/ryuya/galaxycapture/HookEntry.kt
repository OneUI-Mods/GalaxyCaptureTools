package dev.ryuya.galaxycapture

import android.app.Application
import android.content.Context
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import kotlin.math.log

class HookEntry : XposedModule() {
    override fun onModuleLoaded(p: ModuleLoadedParam) {
        Hooks.attach(this)
    }

    override fun onPackageReady(p: PackageReadyParam) {
        if (p.isFirstPackage()) loadPackage(p.getPackageName(), p.getClassLoader(), false)
    }

    override fun onSystemServerStarting(p: SystemServerStartingParam) {
        loadPackage("android", p.getClassLoader(), true)
    }

    private fun loadPackage(packageName: String, classLoader: ClassLoader?, systemServer: Boolean) {
        if (!HookCompatibility.isCurrentSupported()) return
        try {
            if (systemServer) {
                FrameworkHooks.install(classLoader)
                SecureCapture.installFramework(classLoader)
                try {
                    CallAudioCapture.installFramework(classLoader)
                } catch (error: Throwable) {
                    Hooks.log("GalaxyCapture call audio unavailable: " + error)
                }
                Hooks.hookAllMethods(
                    Reflect.findClass("com.android.server.SystemServer", classLoader),
                    "startOtherServices",
                    object : HookCallback() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (FrameworkHooks.runtime != null) return
                            try {
                                val c: Context? =
                                    Reflect.getObjectField(param.thisObject!!, "mSystemContext")
                                        as Context?
                                FrameworkHooks.runtime = HookRuntime(c!!, "framework")
                                CaptureTargets.install(FrameworkHooks.runtime!!, classLoader)
                            } catch (e: Throwable) {
                                Hooks.log("GalaxyCapture framework unavailable: " + e)
                            }
                        }
                    },
                )
            } else if (
                packageName == "com.android.systemui" ||
                    packageName == "com.samsung.android.app.smartcapture"
            ) {
                val system: Boolean = packageName == "com.android.systemui"
                Reflect.findAndHookMethod(
                    Application::class.java,
                    "attach",
                    Context::class.java,
                    object : HookCallback() {
                        private var loaded: Boolean = false

                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (loaded) return
                            loaded = true
                            val runtime =
                                HookRuntime(
                                    param!!.args!![0] as Context,
                                    if (system) "systemui" else "samsung",
                                )
                            try {
                                if (system) SamsungSelection.installSystemUi(runtime, classLoader)
                                else SamsungSelection.installRecorder(runtime, classLoader)
                            } catch (e: Throwable) {
                                runtime.fail(e)
                            }
                        }
                    },
                )
            }
        } catch (e: Throwable) {
            Hooks.log("GalaxyCapture hook initialization stopped: " + e)
        }
    }
}
