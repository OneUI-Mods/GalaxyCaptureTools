package dev.ryuya.galaxycapture

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import dev.ryuya.galaxycapture.compat.DexSymbols
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import dev.ryuya.galaxycapture.startup.FirstUnlockGate
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import kotlin.math.log

class HookEntry : XposedModule() {
    private val firstUnlock = FirstUnlockGate()
    private val main = Handler(Looper.getMainLooper())
    private var samsungRuntime: HookRuntime? = null
    private var recorderOwners: SamsungRecorderOwners? = null
    private var systemUiRuntime: HookRuntime? = null
    private var recordingIndicator: RightRecordingIndicator? = null
    private var systemServer = false
    @Volatile private var frameworkState: Array<*>? = null
    @Volatile private var frameworkRecovery: Array<*>? = null
    private var reconnectState: Array<*>? = null
    @Volatile private var workerState: Array<*>? = null
    @Volatile private var screenshotRuntime: HookRuntime? = null

    override fun onModuleLoaded(p: ModuleLoadedParam) {
        Hooks.attach(this)
        systemServer = p.isSystemServer
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        if (systemServer) return retireFramework(param)
        val process = Application.getProcessName()
        if (isProcessOf(process, SYSTEM_UI) && !SystemScreenshotStandby.isDedicatedScreenshotProcess())
            return retireSystemUi(param)
        if (isProcessOf(process, SAMSUNG_CAPTURE) && process != ScreenshotAnalysisQuota.WORKER_PROCESS)
            return retireSamsungHost(param)
        val state = workerState ?: return false
        return when {
            process == ScreenshotAnalysisQuota.WORKER_PROCESS ->
                ScreenshotAnalysisQuota.tryRetireHook { param.setSavedInstanceState(state) }
            SystemScreenshotStandby.isDedicatedScreenshotProcess() -> {
                val runtime = screenshotRuntime ?: return false
                runtime.tryRetireStandby { param.setSavedInstanceState(state) }
            }
            else -> false
        }
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val state = requireNotNull(param.savedInstanceState as? Array<*>)
        val context = requireNotNull(state.getOrNull(0) as? Context)
        val loader = requireNotNull(state.getOrNull(1) as? ClassLoader)
        onModuleLoaded(param)
        if (param.isSystemServer) {
            frameworkRecovery = state
            installFrameworkHooks(loader)
            startFrameworkRuntime(context, loader)
            param.oldHookHandles.forEach { it.unhook() }
            frameworkRecovery = null
            reportHotUpdate()
            return
        }
        check(param.processName == Application.getProcessName())
        when {
            param.processName == ScreenshotAnalysisQuota.WORKER_PROCESS -> {
                check(ScreenshotAnalysisQuota.installWorkerHook(context, loader))
                workerState = arrayOf(context, loader)
            }
            SystemScreenshotStandby.isDedicatedScreenshotProcess() -> installScreenshotWorker(context, loader)
            isProcessOf(param.processName, SYSTEM_UI) -> {
                check(context.packageName == SYSTEM_UI)
                check(MainThreadReload.run(main) {
                    reconnectState = state
                    check(DexSymbols.canInstallNow(context))
                    installSystemUi(context, loader, state.getOrNull(2))
                    param.oldHookHandles.forEach { it.unhook() }
                    reconnectState = null
                    reportHotUpdate()
                    true
                }) { "SystemUI main thread was unavailable" }
                return
            }
            isProcessOf(param.processName, SAMSUNG_CAPTURE) -> {
                check(context.packageName == SAMSUNG_CAPTURE)
                check(MainThreadReload.run(main) {
                    reconnectState = state
                    val recordingProcess = param.processName == SamsungRecorderOwners.PROCESS
                    if (recordingProcess) check(trackRecorder(context, loader))
                    else check(samsungRecordingIsSeparate(context))
                    check(DexSymbols.canInstallNow(context))
                    check(installSamsungHost(context, loader, recordingProcess))
                    param.oldHookHandles.forEach { it.unhook() }
                    reconnectState = null
                    reportHotUpdate()
                    true
                }) { "Samsung Capture main thread was unavailable" }
                return
            }
            else -> error("Unsupported capture host")
        }
        param.oldHookHandles.forEach { it.unhook() }
        reportHotUpdate()
    }

    private fun reportHotUpdate() {
        Hooks.log("ONEUI_MODS_HOT_RELOAD package=${BuildConfig.APPLICATION_ID} " +
            "version=${BuildConfig.VERSION_CODE} pid=${Process.myPid()}")
    }

    private fun retireSamsungHost(param: HotReloadingParam): Boolean =
        MainThreadReload.run(main) retire@{
            val runtime = samsungRuntime
            val state = workerState ?: reconnectState ?: return@retire false
            if (runtime == null) {
                param.setSavedInstanceState(state)
                DexSymbols.detachObserver()
                reconnectState = null
                return@retire true
            }
            if (!Hooks.tryRetire {
                    recorderOwners?.idle() != false && !SamsungSelection.ownsRecordingState() && !SamsungRecordingUi.hasAttachedPopup() &&
                        runtime.tryPauseWorker {
                            if (runtime.guard.canRetire()) {
                                param.setSavedInstanceState(state)
                                true
                            } else {
                                false
                            }
                        }
                }) return@retire false
            runtime.closeRetiredWorker()
            SamsungRecordingUi.retire()
            DexSymbols.detachObserver()
            samsungRuntime = null
            workerState = null
            true
        }

    private fun samsungRecordingIsSeparate(context: Context): Boolean {
        if (context.packageName != SAMSUNG_CAPTURE) return false
        val services = SamsungRecorderOwners.installedServices(context)
        return services.isNotEmpty() && services.all { it.processName == SamsungRecorderOwners.PROCESS }
    }

    private fun trackRecorder(context: Context, loader: ClassLoader): Boolean {
        Hooks.trackForHotReload()
        return try {
            recorderOwners = SamsungRecorderOwners.start(context, loader)
            recorderOwners != null
        } catch (error: Throwable) {
            Hooks.log("GalaxyCapture recorder lifetime unavailable: ${error.javaClass.simpleName}")
            false
        }
    }

    private fun installSamsungHost(context: Context, loader: ClassLoader, recordingProcess: Boolean): Boolean {
        Hooks.trackForHotReload()
        val runtime = HookRuntime(context, "samsung")
        val ready = try {
            val screenshotHooksReady = SamsungSelection.installRecorder(runtime, loader)
            recordingProcess || screenshotHooksReady
        } catch (error: Throwable) {
            runtime.fail(error)
            false
        }
        // Preserve the ability to retry with the next APK if stock contracts change.
        samsungRuntime = runtime
        workerState = arrayOf(context, loader)
        return ready
    }

    private fun projectionIdle(context: Context): Boolean = runCatching {
        val manager = requireNotNull(context.getSystemService(Context.MEDIA_PROJECTION_SERVICE))
        // The public-side wrapper also returns null after a RemoteException. Consult its
        // existing service proxy directly, so an unavailable service cannot look idle.
        val service = requireNotNull(Reflect.getObjectField(manager, "mService"))
        Reflect.callMethod(service, "getActiveProjectionInfo") == null
    }.getOrDefault(false)

    private fun retireSystemUi(param: HotReloadingParam): Boolean {
        val state = workerState ?: reconnectState ?: return false
        val context = state.getOrNull(0) as? Context ?: return false
        if (!projectionIdle(context)) return false
        return MainThreadReload.run(main) retire@{
            val runtime = systemUiRuntime
            val indicator = recordingIndicator
            if (!Hooks.tryRetire {
                    if (runtime == null) {
                        param.setSavedInstanceState(state)
                        true
                    } else {
                        indicator?.canRetire() != false && AospRecordingUi.idleForHotReload() &&
                            runtime.tryPauseWorker {
                                if (!runtime.guard.canRetire()) false
                                else {
                                    param.setSavedInstanceState(arrayOf(context, state[1], indicator?.stateForReload()))
                                    true
                                }
                            }
                    }
                }) return@retire false
            runtime?.closeRetiredWorker()
            indicator?.close()
            DexSymbols.detachObserver()
            recordingIndicator = null
            systemUiRuntime = null
            workerState = null
            reconnectState = null
            true
        }
    }

    private fun installSystemUi(context: Context, loader: ClassLoader, previous: Any?) {
        Hooks.trackForHotReload()
        val runtime = HookRuntime(context, "systemui")
        systemUiRuntime = runtime
        workerState = arrayOf(context, loader, previous)
        try {
            SamsungCaptureStandby.install(runtime)
            SystemScreenshotStandby.install(runtime)
            recordingIndicator = SamsungSelection.installSystemUi(runtime, loader, previous)
            workerState = arrayOf(context, loader)
        } catch (error: Throwable) {
            runtime.fail(error)
            throw error
        }
    }

    private fun retireFramework(param: HotReloadingParam): Boolean {
        val state = frameworkState ?: frameworkRecovery ?: return false
        val context = state.getOrNull(0) as? Context ?: return false
        // A stock projection can be active even when none of our mirror hooks claimed it.
        if (!projectionIdle(context)) return false
        val runtime = FrameworkHooks.runtime
        if (!Hooks.tryRetire {
                if (runtime == null) {
                    param.setSavedInstanceState(state)
                    true
                } else {
                    FrameworkHooks.idleForHotReload() && runtime.tryPauseWorker {
                        if (runtime.guard.canRetire() && CaptureTargets.idleForHotReload()) {
                            param.setSavedInstanceState(state)
                            true
                        } else {
                            false
                        }
                    }
                }
            }) return false
        runtime?.closeRetiredWorker()
        FrameworkHooks.runtime = null
        DexSymbols.detachObserver()
        frameworkState = null
        frameworkRecovery = null
        return true
    }

    private fun installFrameworkHooks(loader: ClassLoader) {
        Hooks.trackForHotReload()
        FrameworkHooks.install(loader)
        SecureCapture.installFramework(loader)
        try {
            CallAudioCapture.installFramework(loader)
        } catch (error: Throwable) {
            Hooks.log("GalaxyCapture call audio unavailable: " + error)
        }
    }

    private fun startFrameworkRuntime(context: Context, loader: ClassLoader) {
        val runtime = HookRuntime(context, "framework")
        try {
            CaptureTargets.install(runtime, loader)
        } catch (error: Throwable) {
            runtime.tryRetireStandby {}
            throw error
        }
        FrameworkHooks.runtime = runtime
        frameworkState = arrayOf(context, loader)
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
                val loader = requireNotNull(classLoader)
                installFrameworkHooks(loader)
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
                                startFrameworkRuntime(requireNotNull(c), loader)
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
                            val hostContext = param.args!![0] as Context
                            if (Application.getProcessName() == SamsungRecorderOwners.PROCESS && classLoader != null) {
                                trackRecorder(hostContext, classLoader)
                            }
                            firstUnlock.run(hostContext) {
                                if (Application.getProcessName() == ScreenshotAnalysisQuota.WORKER_PROCESS) {
                                    if (classLoader != null &&
                                        ScreenshotAnalysisQuota.installWorkerHook(hostContext, classLoader)) {
                                        workerState = arrayOf(hostContext, classLoader)
                                    }
                                } else {
                                    installHost(hostContext, classLoader, system)
                                }
                            }
                        }
                    },
                )
            }
        } catch (e: Throwable) {
            Hooks.log("GalaxyCapture hook initialization stopped: " + e)
        }
    }

    private fun installHost(hostContext: Context, classLoader: ClassLoader?, system: Boolean) {
        if (system && SystemScreenshotStandby.isDedicatedScreenshotProcess()) {
            if (classLoader != null) installScreenshotWorker(hostContext, classLoader)
            return
        }
        DexSymbols.whenPrepared(hostContext) {
            val process = Application.getProcessName()
            val canUpdateRecorder = process == SamsungRecorderOwners.PROCESS && recorderOwners != null
            val canUpdateScreenshot = process != SamsungRecorderOwners.PROCESS &&
                isProcessOf(process, SAMSUNG_CAPTURE) && samsungRecordingIsSeparate(hostContext)
            if (!system && classLoader != null && (canUpdateRecorder || canUpdateScreenshot)) {
                installSamsungHost(hostContext, classLoader, canUpdateRecorder)
                return@whenPrepared
            }
            if (system && hostContext.packageName == SYSTEM_UI && isProcessOf(process, SYSTEM_UI) && classLoader != null) {
                runCatching { installSystemUi(hostContext, classLoader, null) }
                    .onFailure { Hooks.log("GalaxyCapture SystemUI install failed: $it") }
                return@whenPrepared
            }
            val runtime = HookRuntime(hostContext, if (system) "systemui" else "samsung")
            try {
                if (system) SamsungSelection.installSystemUi(runtime, classLoader)
                else SamsungSelection.installRecorder(runtime, classLoader)
            } catch (error: Throwable) {
                runtime.fail(error)
            }
        }
    }
    private fun installScreenshotWorker(context: Context, loader: ClassLoader) {
        // The dedicated renderer path only calls a platform preload method.
        val runtime = HookRuntime(context, "systemui", configureSymbols = false)
        SystemScreenshotStandby.install(runtime)
        screenshotRuntime = runtime
        workerState = arrayOf(context, loader)
    }
    private fun isProcessOf(process: String, name: String) =
        process == name || process.startsWith("$name:")

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
        const val SAMSUNG_CAPTURE = SamsungRecorderOwners.PACKAGE
    }
}
