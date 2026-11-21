package dev.ryuya.galaxycapture

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Display
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import java.lang.reflect.Method

internal object GameScreenshotToolbar {
    private const val SCREENSHOT_UI_SERVICE =
        "com.samsung.android.app.smartcapture.screenshot.ScreenshotUiService"
    private const val SCREENSHOT_PARAMS = "globalScreenshotParams"
    private const val FOCUSED_PACKAGE = "extraCurrentFocusedPackage"
    private const val SHOW_TOOLBAR = "isSmartCaptureVisible"

    fun install(runtime: HookRuntime, loader: ClassLoader?) {
        try {
            val service = Reflect.findClass(SCREENSHOT_UI_SERVICE, loader)
            val createFragment = service.getDeclaredMethod(
                "createServiceFragmentForStandAlone",
                Intent::class.java,
            )
            val createServiceFragment =
                service.getDeclaredMethod("createServiceFragment", Intent::class.java)
            val samsungGameLookup = findSamsungGameLookup()

            Hooks.deoptimize(createServiceFragment)
            Hooks.hook(createFragment, object : HookCallback() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val options = runtime.options
                    if (options?.enabled != true || !options.hideGameScreenshotToolbar) return

                    val context = param.thisObject as? Context ?: return
                    val intent = param.args?.firstOrNull() as? Intent ?: return
                    val screenshot = intent.getBundleExtra(SCREENSHOT_PARAMS) ?: return

                    // The focused package supplied by Samsung describes the phone display.
                    if (screenshot.getInt("captureDisplay") != Display.DEFAULT_DISPLAY ||
                        !screenshot.getBoolean(SHOW_TOOLBAR, true)
                    ) return
                    val packageName = intent.getStringExtra(FOCUSED_PACKAGE)
                        ?.takeIf(String::isNotBlank) ?: return
                    if (!isGame(context.packageManager, packageName, samsungGameLookup)) return

                    // Keep Samsung's save, clipboard, announcement and cleanup paths intact.
                    val hiddenToolbar = Bundle(screenshot).apply { putBoolean(SHOW_TOOLBAR, false) }
                    param.args[0] = Intent(intent).putExtra(SCREENSHOT_PARAMS, hiddenToolbar)
                }
            })
        } catch (error: Throwable) {
            Hooks.log("GalaxyCapture in-game screenshot toolbar control unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun findSamsungGameLookup(): Method? =
        runCatching {
            Class.forName("com.samsung.android.game.SemGameManager")
                .getMethod("isGamePackage", String::class.java)
        }.getOrNull()

    @Suppress("DEPRECATION")
    private fun isGame(manager: PackageManager, packageName: String, samsungLookup: Method?): Boolean {
        val app = try {
            manager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
        if (app != null &&
            (app.category == ApplicationInfo.CATEGORY_GAME || app.flags and ApplicationInfo.FLAG_IS_GAME != 0)
        ) return true

        return runCatching { samsungLookup?.invoke(null, packageName) == true }.getOrDefault(false)
    }
}
