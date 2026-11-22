package dev.ryuya.galaxycapture

import android.content.Context
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect

/** Takes the toolbar's existing no-auto-analysis path for regular full screenshots. */
internal object ScreenshotToolbarAnalysis {
    private const val CAPTURE = "com.samsung.android.app.smartcapture"
    private const val SCOPE_ENTERED = "screenshotToolbarAnalysisScope"

    fun install(runtime: HookRuntime, loader: ClassLoader?): Boolean {
        try {
            val toolbar = Reflect.findClass("$CAPTURE.screenshot.MainUiViewForStandAlone", loader)
            val savedFile = Reflect.findClass(
                "$CAPTURE.baseutil.scrollcapture.util.GlobalScreenshotFileManager\$ScreenshotFileState", loader,
            )
            val features = Reflect.findClass("$CAPTURE.baseutil.feature.FeatureUtils", loader)
            val onFileSaved = toolbar.getDeclaredMethod("onGlobalScreenshotFileSaveCompleted", savedFile)
            val isPartial = toolbar.getMethod("isPartialCaptureMode")
            val isFlex = toolbar.getMethod("isFlexPanelCaptureMode")
            val textSupported = features.getDeclaredMethod("isTextExtractionSupported", Context::class.java)
            if (onFileSaved.returnType != Void.TYPE || textSupported.returnType != Boolean::class.javaPrimitiveType) return false

            Hooks.deoptimize(onFileSaved)
            val suppress = ThreadLocal<Boolean>()
            val featureHook = Hooks.hook(textSupported, object : HookCallback() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (suppress.get() == true) param.setResult(false)
                }
            })
            try {
                Hooks.hook(onFileSaved, object : HookCallback() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val options = runtime.options
                        if (options?.enabled != true || !options.skipScreenshotToolbarAnalysis ||
                            suppress.get() == true) return
                        val view = param.thisObject
                        if (isPartial.invoke(view) != false || isFlex.invoke(view) != false) return
                        param.setObjectExtra(SCOPE_ENTERED, true)
                        suppress.set(true)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.getObjectExtra(SCOPE_ENTERED) == true) suppress.remove()
                    }
                })
            } catch (error: Throwable) {
                featureHook.unhook()
                throw error
            }
            return true
        } catch (error: Throwable) {
            Hooks.log("GalaxyCapture screenshot toolbar analysis control unavailable: ${error.javaClass.simpleName}")
            return false
        }
    }
}
