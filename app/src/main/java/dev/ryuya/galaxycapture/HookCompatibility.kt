package dev.ryuya.galaxycapture

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Selects a single version profile; unsupported versions never inherit another profile. */
object HookCompatibility {
    @JvmStatic
    @ChecksSdkIntAtLeast(api = 36)
    fun isCurrentSupported(): Boolean {
        return HookCompatibility.supports(
            Build.VERSION.SDK_INT,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.DEVICE,
            Build.DISPLAY,
        )
    }

    @JvmStatic
    fun samsungDialog(sdk: Int): HookCompatibility.SamsungDialog? {
        return when (HookCompatibility.platform(sdk)) {
            HookCompatibility.Platform.ANDROID_16 ->
                HookCompatibility.SamsungDialog(
                    Android16Hooks.DIALOG_BUILDER,
                    Android16Hooks.DIALOG_CREATE,
                )
            HookCompatibility.Platform.ANDROID_17 ->
                HookCompatibility.SamsungDialog(
                    Android17Hooks.DIALOG_BUILDER,
                    Android17Hooks.DIALOG_CREATE,
                )
            HookCompatibility.Platform.UNSUPPORTED ->
                throw UnsupportedOperationException("Unreviewed Samsung dialog platform")
        }
    }

    @JvmStatic
    fun platform(sdk: Int): HookCompatibility.Platform {
        return when (sdk) {
            36 -> HookCompatibility.Platform.ANDROID_16
            37 -> HookCompatibility.Platform.ANDROID_17
            else -> HookCompatibility.Platform.UNSUPPORTED
        }
    }

    @JvmStatic
    fun supports(
        sdk: Int,
        manufacturer: String?,
        model: String?,
        device: String?,
        display: String?,
    ): Boolean {
        return when (HookCompatibility.platform(sdk)) {
            HookCompatibility.Platform.ANDROID_16 ->
                Android16Hooks.supports(sdk, manufacturer, model, device, display)
            HookCompatibility.Platform.ANDROID_17 ->
                Android17Hooks.supports(sdk, manufacturer, model, device, display)
            HookCompatibility.Platform.UNSUPPORTED -> false
        }
    }

    enum class Platform {
        ANDROID_16,
        ANDROID_17,
        UNSUPPORTED,
    }

    @JvmRecord
    data class SamsungDialog(val builderClass: String, val createMethod: String) {
        override fun toString(): String =
            "SamsungDialog[builderClass=$builderClass, createMethod=$createMethod]"
    }
}
