package dev.ryuya.galaxycapture

import kotlin.jvm.JvmStatic

/* Capturing permission is specific to one operation, never a source-window flag change. */
object SecureCapturePolicy {
    @JvmStatic
    fun recording(
        enabled: Boolean,
        option: Boolean,
        guard: Boolean,
        owner: String?,
        name: String?,
    ): Boolean {
        return enabled &&
            option &&
            guard &&
            (("com.android.systemui" == owner && "Recording Display" == name) ||
                ("com.samsung.android.app.smartcapture" == owner && "ScreenRecorder" == name))
    }

    @JvmStatic
    fun screenshot(enabled: Boolean, option: Boolean, guard: Boolean, owner: String?): Boolean {
        return enabled && option && guard && "com.android.systemui" == owner
    }

    @JvmStatic
    fun onlySecureFlag(reasons: Int, sensitive: Boolean, devicePolicy: Boolean): Boolean {
        return reasons == 16 && !sensitive && !devicePolicy
    }
}
