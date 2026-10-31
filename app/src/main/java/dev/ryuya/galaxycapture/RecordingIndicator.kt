package dev.ryuya.galaxycapture

import android.os.Handler
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import kotlin.jvm.JvmStatic

/* Removes only the cast icon duplicated by known local recorders; recording UI is untouched. */
object RecordingIndicator {
    @JvmStatic
    fun install(rt: HookRuntime, loader: ClassLoader?) {
        RightRecordingIndicator.install(rt, loader)
        val controller =
            Reflect.findClass(
                "com.android.systemui.statusbar.phone.SamsungCastIconController",
                loader,
            )
        val hooks =
            Hooks.hookAllMethods(
                controller,
                "access\$updateIconVisibility",
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (
                            !rt.options!!.enabled ||
                                (!rt.options!!.hideDuplicateIndicator &&
                                    !rt.options!!.recordingIconRight) ||
                                !rt.guard.allowed()
                        )
                            return
                        try {
                            val owner: Any? = p!!.args!![0]
                            val cast = Reflect.getObjectField(owner!!, "castController")
                            val devices: MutableList<*>? =
                                Reflect.callMethod(cast!!, "getCastDevices") as MutableList<*>?
                            var recorder: Boolean = false
                            var samsungOnly: Boolean = true
                            for (device in devices!!) {
                                if (!Reflect.getBooleanField(device!!, "isCasting")) continue
                                val tag = Reflect.getObjectField(device!!, "tag")
                                if (
                                    tag == null ||
                                        (tag.javaClass.getName() !=
                                            "android.media.projection.MediaProjectionInfo")
                                )
                                    return
                                val pkg: String? =
                                    Reflect.callMethod(tag, "getPackageName") as String?
                                if (
                                    pkg != "com.samsung.android.app.smartcapture" &&
                                        pkg != "com.android.systemui"
                                )
                                    return
                                samsungOnly =
                                    samsungOnly and (pkg == "com.samsung.android.app.smartcapture")
                                recorder = true
                            }
                            if (!recorder || (!rt.options!!.hideDuplicateIndicator && !samsungOnly))
                                return
                            val handler: Handler? =
                                Reflect.getObjectField(owner!!, "mainHandler") as Handler?
                            handler!!.removeCallbacks(
                                Reflect.getObjectField(owner!!, "removeRunnable") as Runnable
                            )
                            val icons = Reflect.getObjectField(owner!!, "iconController")
                            val slot: String? =
                                Reflect.getObjectField(owner!!, "slotName") as String?
                            Reflect.callMethod(icons!!, "setIconVisibility", slot, false)
                            p.setResult(null)
                        } catch (error: Throwable) {
                            // Keep the native indicator on any failure; never alter projection or
                            // notifications.
                            rt.fail(error)
                        }
                    }
                },
            )
        check(!hooks.isEmpty()) { "Cast indicator callback unavailable" }
    }
}
